package com.hospital.wikiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Date;

/**
 * 单表抽取参数：指定目标表名、抽取 SQL 脚本、时间范围及事件编号。
 *
 * <p>作为 {@link SyncDataDto} 的子项使用，由 SyncDataService 根据表名判断
 * 属于事件表、基础表还是患者表，并自动构建实际执行 SQL。</p>
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
