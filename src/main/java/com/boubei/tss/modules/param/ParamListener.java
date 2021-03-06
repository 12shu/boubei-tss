/* ==================================================================   
 * Created [2016-06-22] by Jon.King 
 * ==================================================================  
 * TSS 
 * ================================================================== 
 * mailTo:boubei@163.com
 * Copyright (c) boubei.com, 2015-2018 
 * ================================================================== 
 */

package com.boubei.tss.modules.param;

/**
 * 系统参数监听器接口。
 * 系统参数的变动将会促发监听器动作。
 */
public interface ParamListener {
	
	void afterChange(Param param);
	
}
