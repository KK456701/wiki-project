package com.hospital.wikiagent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 核心制度指标数据同步请求体，包含医院 SOID 及待写入的表数据列表。
 *
 * <p>职责边界：仅作为 HTTP 接口的入参载体，不包含任何业务逻辑；
 * 字段校验由 Jakarta Validation 注解在控制器层完成。</p>
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
