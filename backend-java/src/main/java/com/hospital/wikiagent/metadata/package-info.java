/**
 * 经 DBHub 获取医院业务库元数据并维护本地快照。
 *
 * <p>元数据经 DBHub 同步并形成带版本缓存，SQL 准备只能消费已确认映射；过期或缺失映射必须阻止执行。</p>
 */
package com.hospital.wikiagent.metadata;
