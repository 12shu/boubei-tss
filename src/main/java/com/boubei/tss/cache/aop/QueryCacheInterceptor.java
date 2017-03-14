package com.boubei.tss.cache.aop;

import java.lang.reflect.Method;
import java.util.Collection;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.boubei.tss.PX;
import com.boubei.tss.cache.AbstractPool;
import com.boubei.tss.cache.Cacheable;
import com.boubei.tss.cache.Pool;
import com.boubei.tss.cache.extension.CacheHelper;
import com.boubei.tss.framework.exception.BusinessException;
import com.boubei.tss.framework.sso.Environment;
import com.boubei.tss.modules.param.ParamManager;
import com.boubei.tss.util.EasyUtils;
 
/**
 * 将耗时查询（如report）的执行中的查询缓存起来。
 * 如果同一个用户对同一报表，相同查询条件的查询还在执行中，则让后面的请求进入等待状态。
 * 等第一次的查询执行完成，然后后续的查询直接取缓存里的数据。
 * 这样可以防止用户重复点击查询（以及频繁且耗时的查询），造成性能瓶颈。 
 * 
 * QC_cache项数(I) + QC_cache项的命中次数之和(H) == 当前等待线程数X，当X如果非常大，系统会崩溃，
 * 需要设定一个阈值V，当 X > V , 直接抛出异常
 * 
 * 测试时，可以把第一次查询设置断点断住，来模拟耗时的report查询过程。
 */
@Component("queryCacheInterceptor")
public class QueryCacheInterceptor implements MethodInterceptor {

    protected Logger log = Logger.getLogger(this.getClass());
    
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method targetMethod = invocation.getMethod(); /* 获取目标方法 */
        Object[] args = invocation.getArguments();    /* 获取目标方法的参数 */
        
        QueryCached annotation = targetMethod.getAnnotation(QueryCached.class); // 取得注释对象
        if (annotation == null) {
        	return invocation.proceed(); /* 如果没有配置缓存，则直接执行方法并返回结果 */
        }
 
        int limit = annotation.limit();
		AbstractPool qCache = (AbstractPool) CacheHelper.getShortCache(); /* 使用10分钟Cache */
		
		// 检查当前等待线程数（执行中 + 等待中）
		int X = countThread(qCache), 
			V = EasyUtils.obj2Int( ParamManager.getValue(PX.MAX_QUERY_REQUEST, "100") );
		if( X > V ) {
			throw new BusinessException("当前应用服务器资源紧张，请稍后再查询。" + X + ">" + V);
		}
		
		/* 检查当前查询服务（报表服务等）在等待队列中是否超过了阈值（X）25%，超过则不再接受新的查询请求，
		 * 以防止单个服务耗尽队列 
		 */
		if( limit >= 0) {
			Object limitArg = (args.length > limit ? args[limit] : "" );
			int count = countService(qCache, targetMethod.getName(), limitArg);
			if(count > V*0.25) {
				throw new BusinessException("当前您查询的数据服务响应缓慢，前面还有" + count + "个人在等待，请稍后再查询。");
			}
		}
        
        String qKey = "QC_" + CacheInterceptor.cacheKey(targetMethod, args);
        Cacheable qcItem = qCache.getObject(qKey); // item.hit++
		
		Object returnVal;
		long currentThread = Environment.threadID();
		String visitor = Environment.getUserName();
		
		if (qcItem != null) {
			String visitors = qcItem.getValue() + "," +  visitor;
			qcItem.update( visitors ); // 记录是哪几个人、及第几个到访
			log.debug( currentThread + " QueryCache【"+qKey+"】= " + qcItem.getHit() );
			
			// 等待执行中的上一次请求先执行完成； 
			long start = System.currentTimeMillis();
			while( qCache.contains(qKey) ) { // 说明NO.1 Query还在执行中
				log.debug(currentThread + " QueryCache waiting...");
				Thread.sleep( 500 * Math.min(20, qcItem.getHit()) );  // 等待的线程越多，则sleep时间越长
				
				// 超过10分钟，说明执行非常缓慢，则不再继续等待，同时抛错提示用户。
				if(System.currentTimeMillis() - start > 10*60*1000) {
					throw new BusinessException("本次请求执行缓慢，请稍后再查询。");
				}
			}
			
			// QC_cache 已经被destroy
			returnVal = invocation.proceed(); // 此时去执行查询，结果已经在3分钟的cache中
		} 
		else {
			Cacheable item = qCache.putObject(qKey, visitor); // 缓存访问用户作为【执行中标记】
			
			/* 放到using池中，以免出现以下情形:
			 * 如果一次报表查询超过了10分钟，其QC_对象已经被清除，但SQL还在继续执行，此时将捕捉不到是哪个Report异常 */
			qCache.getFree().remove(qKey);
			qCache.getUsing().put(qKey, item);
			
			log.debug(currentThread + " QueryCache【"+qKey+"】first time executing...");
			
			/* 执行方法，进入CacheInterceptor，查询成功后结果将被缓存3分钟  */
			try {
				returnVal = invocation.proceed();
			} catch(Exception e) {
				throw e;
			} finally {
				qCache.destroyByKey(qKey); // 移除销毁缓存的执行信息（出现异常时也要移除）
			}
		}
		
        return returnVal;
    }
    
    private int countThread(Pool cache) {
    	int I = 0, H = 0;
    	Collection<Cacheable> items = cache.listItems();
    	for(Cacheable item : items) {
    		String key = item.getKey().toString();
			if( key.startsWith("QC_") ) {
				I++;
	    		H += item.getHit();
			}
    	}
    	return I + H;
    }
    
    // 计算当前某个服务的等待人数
    private int countService(Pool cache, String method, Object limitArg) {
    	int count = 0;
    	Collection<Cacheable> items = cache.listItems();
    	for(Cacheable item : items) {
    		String key = item.getKey().toString();
			if( key.indexOf(method + "(" + limitArg) > 0) { // TODO 用正则表达式，可以不是第一个参数
				String visitors = (String) item.getValue() + "";
				count += visitors.split(",").length;
			}
    	}
    	return count;
    }
}