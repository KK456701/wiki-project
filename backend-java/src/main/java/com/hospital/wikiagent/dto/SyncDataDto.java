package com.hospital.wikiagent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 数据同步请求 DTO：描述一次抽取任务所需的医院 SOID、事件表、业务表列表。
 *
 * <p>由 {@code SyncDataController} 接收外部 JSON 或由抽取网关内部构建，
 * 传递给 {@code SyncDataService.syncEventData} 执行清库重写。</p>
 */
@Data
public class SyncDataDto {

    @NotNull(message = "SOID不能为空")
    private Long hospitalSOID;

    @NotEmpty(message = "核心制度表数据不能为空")
    private List<TableDataDto> eventDataList;

    private List<TableDataDto> bizDataList;

    private List<TableDataDto> eventTableList;
}
