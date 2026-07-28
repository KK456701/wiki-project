package com.hospital.wikiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Date;

/**
 * 单张业务表的同步数据载体，包含表名、列名映射及行数据。
 *
 * <p>职责边界：仅描述一张表的结构与内容，由 SyncDataDto 聚合多张表后统一提交；
 * 本类不做任何持久化或网络调用。</p>
 */
@Data
public class TableDataDto {

    private String eventNo;

    @NotBlank(message = "表名不能为空")
    private String table;

    @NotBlank(message = "SQL脚本不能为空")
    private String sqlScript;

    private Date startTime;

    private Date endTime;
}
