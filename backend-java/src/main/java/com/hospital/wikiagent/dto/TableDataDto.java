package com.hospital.wikiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Date;

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
