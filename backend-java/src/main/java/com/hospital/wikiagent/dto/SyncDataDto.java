package com.hospital.wikiagent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SyncDataDto {

    @NotNull(message = "SOID不能为空")
    private Long hospitalSOID;

    @NotEmpty(message = "核心制度表数据不能为空")
    private List<TableDataDto> eventDataList;

    private List<TableDataDto> bizDataList;

    private List<TableDataDto> eventTableList;
}
