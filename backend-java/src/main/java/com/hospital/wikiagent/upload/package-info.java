/**
 * Excel 上传文件的隔离存储与无第三方依赖解析。
 *
 * <p>负责上传文件的落盘、格式读取和生命周期，文件名不作为可信路径；医院隔离和大小限制在写入前执行。</p>
 */
package com.hospital.wikiagent.upload;
