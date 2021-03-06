/* ==================================================================   
 * Created [2016-3-5] by Jon.King 
 * ==================================================================  
 * TSS 
 * ================================================================== 
 * mailTo:boubei@163.com
 * Copyright (c) boubei.com, 2015-2018 
 * ================================================================== 
 */

package com.boubei.tss.dm.record;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dom4j.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.boubei.tss.EX;
import com.boubei.tss.PX;
import com.boubei.tss.cache.Cacheable;
import com.boubei.tss.cache.Pool;
import com.boubei.tss.cache.extension.CacheHelper;
import com.boubei.tss.dm.DMConstants;
import com.boubei.tss.dm.DMUtil;
import com.boubei.tss.dm.DataExport;
import com.boubei.tss.dm.ddl._Database;
import com.boubei.tss.dm.ddl._Field;
import com.boubei.tss.dm.dml.SQLExcutor;
import com.boubei.tss.dm.record.file.RecordAttach;
import com.boubei.tss.dm.record.permission.RecordPermission;
import com.boubei.tss.dm.record.permission.RecordResource;
import com.boubei.tss.dm.record.workflow.WFLog;
import com.boubei.tss.dm.record.workflow.WFManager;
import com.boubei.tss.dm.record.workflow.WFStep;
import com.boubei.tss.dm.report.log.AccessLogRecorder;
import com.boubei.tss.framework.SecurityUtil;
import com.boubei.tss.framework.exception.BusinessException;
import com.boubei.tss.framework.exception.ExceptionEncoder;
import com.boubei.tss.framework.persistence.pagequery.PageInfo;
import com.boubei.tss.framework.sso.Environment;
import com.boubei.tss.framework.web.display.grid.DefaultGridNode;
import com.boubei.tss.framework.web.display.grid.GridDataEncoder;
import com.boubei.tss.framework.web.display.grid.IGridNode;
import com.boubei.tss.framework.web.mvc.BaseActionSupport;
import com.boubei.tss.modules.HitRateManager;
import com.boubei.tss.modules.param.ParamManager;
import com.boubei.tss.um.permission.PermissionHelper;
import com.boubei.tss.util.DateUtil;
import com.boubei.tss.util.EasyUtils;
import com.boubei.tss.util.FileHelper;

@Controller
@RequestMapping( {"/auth/xdata", "/xdata/api", "/xdata"})
public class _Recorder extends BaseActionSupport {
	
	public static final int PAGE_SIZE = 50;
	
	@Autowired RecordService recordService;
	
	_Database getDB(Long recordId, String... permitOptions) {
		// 检测当前用户对当前录入表是否有指定的操作权限
		boolean flag = permitOptions.length == 0;
		for(String permitOption : permitOptions) {
			flag = flag || checkPermission( recordId, permitOption );
		}
		if(!flag) {
			throw new BusinessException( EX.parse(EX.DM_09, recordId, Arrays.asList(permitOptions).toString()) );
		}
		
		Pool cache = CacheHelper.getLongCache();
		String cacheKey = "_db_record_" + recordId;
		if( !cache.contains(cacheKey) ) {
			cache.putObject(cacheKey, recordService.getDB(recordId));
		} 
		
		Cacheable cacheItem = cache.getObject(cacheKey);
		return (_Database) cacheItem.getValue();
	}
	
	@RequestMapping("/id")
    @ResponseBody
    public List<Long> getRecordIDs( String nameOrTables ) {
		String[] list = nameOrTables.split(",");
		List<Long> idList = new ArrayList<Long>();
		for(String nt : list) {
			Long id = recordService.getRecordID(nt, Record.TYPE1);
			idList.add(id);
		}
		
		return idList;
    }
	
	@RequestMapping("/define/{record}")
    @ResponseBody
    public Object getDefine(@PathVariable("record") Object record) {
		Long recordId  = recordService.getRecordID(record);
		Record _record = recordService.getRecord( recordId );
		if(!_record.isActive()) {
			throw new BusinessException(EX.DM_10);
		}
		
        _Database _db = getDB(recordId, Record.OPERATION_CDATA, Record.OPERATION_EDATA, Record.OPERATION_VDATA);
		return new Object[] { 
        		_db.getFields(), 
        		_record.getCustomizeJS(), 
        		_record.getCustomizeGrid(),
        		_record.getNeedFile(),
        		_record.getBatchImp(),
        		_record.getName(),
        		_record.getCustomizePage(),
        		_db.getVisiableFields(false)
        	};
    }
	
	/**
	 * 当recordId < 0 时，通常是为定制的表（比如万马的员工表）虚拟一张录入表，用以保存附件
	 */
	public Map<String, String> prepareParams(HttpServletRequest request, Long recordId) {
		Map<String, String> requestMap = DMUtil.getRequestMap(request, false);
		
		/* 其它系统调用接口时，传入其在TSS注册的用户ID; 检查令牌，令牌有效则自动完成登陆 */
    	String uName  = requestMap.get("uName"), uToken = requestMap.get("uToken");
    	if( !EasyUtils.isNullOrEmpty(uToken) && !EasyUtils.isNullOrEmpty(uName) && recordId > 0 ) {
    		Record record = recordService.getRecord(recordId);
        	if( !DMUtil.checkAPIToken(record, uName, uToken) ) {
    			throw new BusinessException(EX.DM_11);
    		}
    	} 
		                                                                                                                                                                                                                                                 
    	return requestMap;
    }
	
    @RequestMapping("/xml/{record}/{page}")
    public void showAsGrid(HttpServletRequest request, HttpServletResponse response, 
            @PathVariable("record") Object record, 
            @PathVariable("page") int page) {
    	
    	Long recordId = recordService.getRecordID(record);
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	boolean pointedFileds = requestMap.containsKey("fields") ;
    	_Database _db = getDB(recordId);
    	
        SQLExcutor ex = queryRecordData(_db, page, PAGE_SIZE, requestMap, pointedFileds);
 
        List<IGridNode> temp = new ArrayList<IGridNode>();
		for(Map<String, Object> item : ex.result) {
			 
            DefaultGridNode gridNode = new DefaultGridNode();
            gridNode.getAttrs().putAll(item);
            
            temp.add(gridNode);
        }
		
        Document gridTemplate = pointedFileds ? ex.getGridTemplate(_db.cnm, _db.ctm, _db.cpm) : _db.getGridTemplate();
		GridDataEncoder gEncoder = new GridDataEncoder(temp, gridTemplate);
        
        PageInfo pageInfo = new PageInfo();
        pageInfo.setPageSize(PAGE_SIZE);
        pageInfo.setTotalRows(ex.count);
        pageInfo.setPageNum(page);
        
        print(new String[] {"RecordData", "PageInfo"}, new Object[] {gEncoder, pageInfo});
    }
    
    private SQLExcutor queryRecordData(_Database _db, int page, int pagesize, Map<String, String> requestMap, boolean pointedFileds) {
    	
    	long start = System.currentTimeMillis();
    	SQLExcutor ex = _db.select(page, pagesize, requestMap);
    	AccessLogRecorder.outputAccessLog(_db.recordName, _db.recordName, "select_"+pointedFileds, requestMap, start);
    	
    	if( pointedFileds || requestMap.containsKey("id")) {
    		return ex;
    	}
    	
    	/* 读取记录的附件信息 */
        Map<Object, Object> itemAttach = new HashMap<Object, Object>();
        if(_db.needFile) {
        	String sql = "select itemId item, count(*) num from dm_record_attach where recordId = ? group by itemId";
			List<Map<String, Object>> attachResult = SQLExcutor.query(DMConstants.LOCAL_CONN_POOL, sql, _db.recordId);
        	for(Map<String, Object> temp : attachResult) {
        		itemAttach.put(temp.get("item").toString(), temp.get("num"));
        	}
        }
		for(Map<String, Object> item : ex.result) {
			// 把附件字段替换为链接
            int index = 0;
    		for(String field : _db.fieldCodes) {
    			boolean isFileField = _Field.TYPE_FILE.equals(_db.fieldTypes.get(index++));
    			if( isFileField ) {
    				String[] values = EasyUtils.obj2String(item.get(field)).split(",");
    				String urls = "";
    				for(String value : values) {
    					int splitIndex = value.indexOf("#");
        				if(splitIndex < 0) continue;
        				
        				String name = value.substring(0, splitIndex);
        				String id = value.substring(splitIndex + 1);
        				urls += "<a href='/tss/auth/xdata/attach/download/" +id+ "' target='_blank'>" +name+ "</a>&nbsp&nbsp";
    				}

    				item.put(field, urls);
    			}
    		}
    		
            Object itemId = item.get("id").toString();
            Object attachNum = itemAttach.get(itemId);
            if(attachNum != null) {
            	item.put("fileNum", "<a href='javascript:void(0)' onclick='manageAttach(" + itemId + ")'>" + attachNum + "</a>");
            }
            
            /* 添加工作流信息 */
            if( _db.wfDefine != null ) {
            	String currStatus = WFManager.getCurrStatus(_db, item);
				item.put("wfstatus", "<a href='javascript:void(0)' onclick='showDetail()'>" + currStatus + "</a>");
            }
        }
		
		return ex;
    }
    
    @RequestMapping("/json/{record}/{page}")
    @ResponseBody
    public Object showAsJSON(HttpServletRequest request, 
    		@PathVariable("record") Object record, @PathVariable("page") int page) {
    	
    	// 如果同时传递了录入表名，优先用之（适合中文名表名）
    	String recordName = request.getParameter("recordName");
    	if( !EasyUtils.isNullOrEmpty(recordName)  ) {
    		record = recordName;
    	}
    	Long recordId = recordService.getRecordID(record);
    	
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	boolean pointedFileds = requestMap.containsKey("fields") ;
    	int _pagesize = getPageSize(requestMap, PAGE_SIZE*20);
    	
        _Database _db = getDB(recordId);
        
        SQLExcutor ex = queryRecordData(_db, page, _pagesize, requestMap, pointedFileds);
        
        if( requestMap.containsKey("rows") ) { // for EasyUI
        	Map<String, Object> returlVal = new HashMap<String, Object>();
        	returlVal.put("total", ex.count);
        	returlVal.put("rows", ex.result);
        	return returlVal;
        }
        
		return ex.result;
    }
    
    /** EasyUI 翻页调用此处接口 */
    @RequestMapping("/json/{record}")
    @ResponseBody
    public Object showAsJSON(HttpServletRequest request, 
    		@PathVariable("record") String record) {
    	
    	Long recordId = recordService.getRecordID(record);
    	
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	Object page = EasyUtils.checkNull(requestMap.get("page"), 1);
        return showAsJSON(request, recordId, EasyUtils.obj2Int(page));
    }
    
    private int getPageSize(Map<String, String> m, int defaultSize) {
    	Object pagesize = EasyUtils.checkNull(m.get("pagesize"), m.get("rows"), defaultSize);
    	return EasyUtils.obj2Int(pagesize);
    }
    
    @RequestMapping("/export/{record}")
    public void exportAsCSV(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record) {
        
    	long start = System.currentTimeMillis();
    	
    	Map<String, String> requestMap = DMUtil.getRequestMap(request, true);  // GET Method Request
    	boolean pointed = requestMap.containsKey("fields") ;
    	Long recordId = recordService.getRecordID(record);
    	_Database _db = getDB(recordId);
        
    	int _page = EasyUtils.obj2Int( EasyUtils.checkNull(requestMap.get("page"), "1") );
    	int _pagesize = getPageSize(requestMap, 10*10000);
    	
		SQLExcutor ex = _db.select(_page, _pagesize, requestMap);

		String fileName = DateUtil.format(new Date()) +"_"+ recordId + Environment.getUserId() + ".csv";
        for (Map<String, Object> row : ex.result) { // 剔除
        	row.remove("domain");
        	row.remove("createtime");
        	row.remove("creator");
        	row.remove("updatetime");
        	row.remove("updator");
        	row.remove("version");
        	row.remove("id");
        }
        
        // 过滤出用户可见的表头列
		List<String> visiableFields = _db.getVisiableFields(true, pointed ? ex.selectFields : _db.fieldCodes);
		String exportPath = DataExport.exportCSV(fileName, ex.result, visiableFields);
        DataExport.downloadFileByHttp(response, exportPath);

        AccessLogRecorder.outputAccessLog("record-" + recordId, _db.recordName, "exportAsCSV", requestMap, start);
    }
    
    @RequestMapping(value = "/{record}/{id}", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> get(HttpServletRequest request, 
    		@PathVariable("record") Object record, @PathVariable("id") Long id) {
    	
    	Long recordId = recordService.getRecordID(record);
    	prepareParams(request, recordId);
    	_Database _db = getDB(recordId, Record.OPERATION_CDATA, Record.OPERATION_VDATA, Record.OPERATION_EDATA);

        Map<String, Object> result = _db.get(id);
        if(result == null) {
        	throw new BusinessException( EX.parse(EX.DM_13_2, id) );
        }
        
        /* 添加工作流信息 */
        if( _db.wfDefine != null ) {
        	WFStep currStep = WFManager.getCurrStep(_db, result);
			result.put("wfStep", currStep);
        }
        
        result.put("id", id);
		return result;
    }
	
    @RequestMapping(value = "/{record}", method = RequestMethod.POST)
    public void create(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record) {
    	
    	Object newID = createAndReturnID(request, record);
    	printSuccessMessage( String.valueOf(newID) );
    }
    
    @RequestMapping(value = "/rid/{record}", method = RequestMethod.POST)
    @ResponseBody
    public Object createAndReturnID(HttpServletRequest request, 
    		@PathVariable("record") Object record) {
    	
    	Long recordId = recordService.getRecordID(record);
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	Long _tempID = EasyUtils.obj2Long(requestMap.remove("_tempID"));
    	
    	_Database _db = getDB(recordId, Record.OPERATION_CDATA);
    	Long newID = null;
    	try {
    		newID = _db.insertRID( requestMap );
    		
    		File attachDir1 = new File(RecordAttach.getAttachDir(recordId, _tempID));
    		if( attachDir1.exists() ) { // 将挂在临时记录ID下的附件挂到新生成的记录ID上
    			Map<Integer, Object> paramsMap = new HashMap<Integer, Object>();
    			paramsMap.put(1, newID);
    			paramsMap.put(2, recordId);
    			paramsMap.put(3, _tempID);
				SQLExcutor.excute("update dm_record_attach set itemId=? where recordId=? and itemId=?", paramsMap, DMConstants.LOCAL_CONN_POOL);
				
				File attachDir2 = new File(RecordAttach.getAttachDir(recordId, newID));
				attachDir1.renameTo(attachDir2);
    		}
    	}
    	catch(Exception e) {
    		_db.delete(newID); // 回滚
    		throwEx(e, _db + " create ");
    	}
    	return newID;
    }
    
    private void throwEx(Exception e, String op) {
    	Throwable firstCause = ExceptionEncoder.getFirstCause(e);
		String errorMsg = op + " error: " + firstCause;
		log.error(errorMsg);
		throw new BusinessException(errorMsg);
    }
    
    @RequestMapping(value = "/{record}/{id}", method = RequestMethod.POST)
    public void update(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, 
    		@PathVariable("id") Long id) {
    	
    	Long recordId = recordService.getRecordID(record);
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	
    	// 检查用户对当前记录是否有编辑权限，防止篡改别人创建的记录
    	checkRowEditable(recordId, id);
    	
    	_Database _db = getDB(recordId);
    	try {
    		Map<String, Object> old = _db.get(id);
			_db.update(id, requestMap );
			
			/* 生成工作流日志， TODO 如何含附件信息，附件还是挂在主记录上?? */
            if( _db.wfDefine != null && requestMap.containsKey("wfUpdate") ) {
            	WFLog wlog = new WFLog();
            	wlog.setTableId(recordId);
            	wlog.setItemId(id);
            	wlog.setProcesser( Environment.getUserName() );
            	wlog.setProcesserTime( new Date() );
            	wlog.setProcessResult( requestMap.get("processResult") );
            	wlog.setAttachFile( requestMap.get("attachFile") );  // TODO
            	
            	WFStep currStep = WFManager.getCurrStep(_db, old);
            	wlog.setOnStep(currStep.status);
            	
            	WFStep nextStep = WFManager.getCurrStep(_db, _db.get(id));
            	wlog.setNextStep(nextStep.status);
            	
            	Set<String> roles = nextStep.role_btn.keySet();  // 优先使用同部门的，没有则取其它部门
            	Set<String> users = nextStep.user_btn.keySet();
//            	wlog.setNextStepProcesser(processer); // 计算下一步处理人
            }
			
			printSuccessMessage();
    	}
    	catch(Exception e) {
    		throwEx(e, _db + " update ");
    	}
    }
    
    /**
     * 批量更新选中记录行的某个字段值，用在批量审批等场景
     * $.post("/tss/xdata/batch/13", {'ids': '1,2,3,4', 'field': 'brand', 'value': '农夫'} , function(data) {});
     */
    @RequestMapping(value = "/batch/{record}", method = RequestMethod.POST)
    public void updateBatch(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, 
    		String ids, String field, String value) {
    	
    	Long recordId = recordService.getRecordID(record);
    	prepareParams(request, recordId);
    	
    	// 检查用户对当前记录是否有编辑权限，防止篡改别人创建的记录
		if( !checkPermission(recordId, Record.OPERATION_EDATA) && !checkPermission(recordId, Record.OPERATION_CDATA) ) {
			throw new BusinessException(EX.DM_12);
		}
    	
		getDB(recordId).updateBatch(ids, field, value);
        printSuccessMessage();
    }
    
    @RequestMapping(value = "/{record}/{id}", method = RequestMethod.PUT)
    public void restore(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, 
    		@PathVariable("id") Long id) {
    	
    	Long recordId = recordService.getRecordID(record);
    	prepareParams(request, recordId);
    	
    	checkRowEditable(recordId, id);
    	_Database db = getDB(recordId);
    	db.restore(id);
    	
        printSuccessMessage();
    }
 
    @RequestMapping(value = "/{record}/{id}", method = RequestMethod.DELETE)
    public void delete(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, 
    		@PathVariable("id") Long id) {
    	
    	Long recordId = recordService.getRecordID(record);
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	
    	exeDelete(recordId, id, requestMap);
        printSuccessMessage();
    }
    
    private void exeDelete(Long recordId, Long id, Map<String, String> requestMap) {
		// 检查用户对当前记录是否有编辑权限
    	checkRowEditable(recordId, id);
    	
    	_Database db = getDB(recordId);
    	
    	// 判断是逻辑删除还是物理删除
    	boolean loginDel = "true".equals( ParamManager.getValue(PX.LOGIC_DEL, "false") ) 
    					|| "true".equals( requestMap.get(PX.LOGIC_DEL) )
    					|| "true".equals( DMUtil.getExtendAttr(db.remark, PX.LOGIC_DEL) );
    	if(loginDel) {
    		db.logicDelete(id);
    	}
    	else {
    		db.delete(id);
        	
    	    // 删除附件
        	List<?> attachs = recordService.getAttachList(recordId, id);
        	for(Object obj : attachs) {
        		RecordAttach attach = (RecordAttach)obj;
        		recordService.deleteAttach( attach.getId() );
        		FileHelper.deleteFile(new File(attach.getAttachPath()));
        	}
    	}
    }
    
    // for 不支持method=DELETE的客户端，微信小程序等
    @RequestMapping(value = "/batch/{record}/del", method = RequestMethod.POST)
    public void deleteBatchII(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, String ids) {
    	deleteBatch(request, response, record, ids);
    }
    
    @RequestMapping(value = "/batch/{record}", method = RequestMethod.DELETE)
    public void deleteBatch(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, String ids) {
    	
    	Long recordId = recordService.getRecordID(record);
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	
    	String[] idArray = ids.split(",");
    	for(String id : idArray) {
    		exeDelete(recordId, EasyUtils.obj2Long(id), requestMap);
    	}
        printSuccessMessage();
    }
    
    /**
     * 批量新增、修改、删除，All in one。
     * @param request
     * @param recordId
     * @param csv
     * @return
     */
    @RequestMapping(value = "/cud/{record}", method = RequestMethod.POST)
    @ResponseBody
    public Object cudBatch(HttpServletRequest request, @PathVariable("record") Object record) {
    	
    	Long recordId = recordService.getRecordID(record);
    	Map<String, String> requestMap = prepareParams(request, recordId);
    	String csv = requestMap.get("csv");
    	
    	_Database _db = getDB(recordId, Record.OPERATION_CDATA, Record.OPERATION_EDATA);
    	
		String[] rows = EasyUtils.split(csv, "\n");
		List<Map<String, String>> insertList = new ArrayList<Map<String, String>>();
		int updateCount = 0, deleteCount = 0;;
		
		String[] headers = rows[0].split(",");
		for(int index = 1; index < rows.length; index++) { // 第一行为表头，不要
			String row = rows[index];
			String[] fields = row.split(",");
			
			Map<String, String> item = new HashMap<String, String>();
			for(int j = 0; j < fields.length; j++) {
    			item.put(headers[j], fields[j]);
        	}
			
			String _itemID = item.get("id");
			if( EasyUtils.isNullOrEmpty(_itemID) ) {
				insertList.add(item);
			} else {
				Long itemID = EasyUtils.obj2Long( _itemID );
				if( row.replaceAll(",", "").trim().equals(_itemID.trim()) ) { // 除了ID其它都为空
					exeDelete(recordId, itemID, requestMap);
					deleteCount++;
				} else {
					checkRowEditable(recordId, itemID);
					_db.update(itemID, item);
					updateCount ++;
				}
			}
		}
    	_db.insertBatch(insertList); // 所有新增是一个事务的，但删除和修改不在一个事务内
    	
    	Map<String, Object> rtMap = new HashMap<String, Object>();
    	rtMap.put("created", insertList.size());
    	rtMap.put("updated", updateCount);
    	rtMap.put("deleted", deleteCount);
    	return rtMap;
    }
    
    /************************************* record batch import **************************************/
    
    /**
     * 将前台（一般为生成好的table数据）数据导出成CSV格式
     */
    @RequestMapping("/import/tl/{record}")
    public void getImportTL(HttpServletResponse response, 
    		@PathVariable("record") Object record) {
    	
    	Long recordId = recordService.getRecordID(record);
    	 _Database _db = getDB(recordId, Record.OPERATION_CDATA, Record.OPERATION_EDATA, Record.OPERATION_VDATA);
		
		String fileName = _db.recordName + "-tl.csv";
        String exportPath = DataExport.getExportPath() + "/" + fileName;
 
        DataExport.exportCSV(exportPath, EasyUtils.list2Str(_db.fieldNames));
        
        DataExport.downloadFileByHttp(response, exportPath);
    }
    
    /************************************* record attach operation **************************************/
    
	@RequestMapping("/attach/json/{record}/{itemId}")
    @ResponseBody
    public List<?> getAttachList(HttpServletRequest request, 
    		@PathVariable("record") Object record, @PathVariable("itemId") Long itemId) {
		
		Long recordId = recordService.getRecordID(record);
		prepareParams(request, recordId);
		
		// 检查用户对当前记录是否有查看权限
		if( !checkRowVisible(recordId, itemId) ) {
			throw new BusinessException(EX.DM_08);
		}
		
		return recordService.getAttachList(recordId, itemId);
    }

	@RequestMapping("/attach/xml/{record}/{itemId}")
    public void getAttachListXML(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("record") Object record, @PathVariable("itemId") Long itemId) {
		
		Long recordId = recordService.getRecordID(record);
		prepareParams(request, recordId);
		
		// 检查用户对当前记录是否有查看权限
		if( !checkRowVisible(recordId, itemId) ) {
			throw new BusinessException(EX.DM_08);
		}
		
		List<?> list = recordService.getAttachList(recordId, itemId);
        GridDataEncoder attachGrid = new GridDataEncoder(list, DMConstants.GRID_RECORD_ATTACH);
        print("RecordAttach", attachGrid);
    }
	
	@RequestMapping(value = "/attach/{id}", method = RequestMethod.DELETE)
    public void deleteAttach(HttpServletRequest request, HttpServletResponse response, @PathVariable("id") Long id) {
		// 注：远程访问时需校验Token需要用到recordId；本地访问可不传
		Long recordId = recordService.getRecordID( request.getParameter("recordId") );
		prepareParams(request, recordId); // 远程访问预登录
		
		RecordAttach attach = recordService.getAttach(id);
		if(attach == null) {
			throw new BusinessException(EX.DM_06);
		}
		
		// 检查用户对当前附件所属记录是否有编辑权限
		checkRowEditable(attach.getRecordId(), attach.getItemId());
		
		recordService.deleteAttach(id);
		FileHelper.deleteFile(new File(attach.getAttachPath()));
		
		printSuccessMessage();
    }
	
	@RequestMapping("/attach/download/{id}")
	public void downloadAttach(HttpServletRequest request, HttpServletResponse response, @PathVariable("id") Long id) throws IOException {
		// 注：远程访问时需校验Token需要用到recordId；本地访问可不传
		prepareParams(request, EasyUtils.obj2Long(request.getParameter("recordId")));
		
		RecordAttach attach = recordService.getAttach(id);
		if(attach == null) {
			throw new BusinessException(EX.DM_06);
		}
		
		// 检查用户对当前附件所属记录是否有查看权限
		if( !checkRowVisible(attach.getRecordId(), attach.getItemId()) ) {
			throw new BusinessException(EX.DM_07);
		} 
		
		FileHelper.downloadFile(response, attach.getAttachPath(), attach.getName());
		HitRateManager.getInstanse("dm_record_attach").output(id);
	}
	
	/************************************* check permissions：安全级别 >= 6 才启用 **************************************/
	
	private boolean checkPermission(Long recordId, String permitOption) {
		if( !SecurityUtil.isHardMode() || recordId < 0 ) return true;
		
		PermissionHelper helper = PermissionHelper.getInstance();
		String permissionTable = RecordPermission.class.getName();
		return helper.checkPermission(recordId, permissionTable, RecordResource.class, permitOption);
	}
	
	/**
	 * 检查用户对当前记录是否有编辑权限，防止篡改别人创建的记录
	 * @param recordId
	 * @param itemId
	 */
	private void checkRowEditable(Long recordId, Long itemId) {
		if( !SecurityUtil.isHardMode() || recordId < 0 ) return;
		
		boolean flag = false;
		if( checkPermission(recordId, Record.OPERATION_EDATA) ) {
			flag = checkRowVisible(recordId, itemId); // 如果有【维护数据】权限，则只要可见就能编辑
		}
		if( !flag && checkPermission(recordId, Record.OPERATION_CDATA) ) {
			flag = checkRowAuthor(recordId, itemId); // 如果没有【维护数据】只有【新建】权限，则只能编辑自己创建的记录
		}
		
		if(!flag) {
			throw new BusinessException(EX.parse(EX.DM_05, itemId));
		}
	}
	
	/**
	 * 因db.select方法里对数据进行了权限过滤，所以能按ID查询出来的都是有权限查看的
	 */
	private boolean checkRowVisible(Long recordId, Long itemId) {
		if( !SecurityUtil.isHardMode() || recordId < 0 
				|| itemId > 1510000000000L) { // 临时记录ID，新建时
			return true;
		}
		
		Map<String, String> requestMap = new HashMap<String, String>();
		requestMap.put("id", EasyUtils.obj2String(itemId));
		
        SQLExcutor ex = getDB(recordId).select( 1, 1, requestMap );
		return !ex.result.isEmpty();
	}
	
	private boolean checkRowAuthor(Long recordId, Long itemId) {
		Map<String, String> requestMap = new HashMap<String, String>();
		requestMap.put("id", EasyUtils.obj2String(itemId));
		requestMap.put("creator", Environment.getUserCode());
		
        SQLExcutor ex = getDB(recordId).select( 1, 1, requestMap );
        return !ex.result.isEmpty();
	}
}
