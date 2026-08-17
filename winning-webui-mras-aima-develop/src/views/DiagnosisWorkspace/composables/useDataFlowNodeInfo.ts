import type { DataFlowNode } from '@/types/chat';
import { DATA_FLOW_NODE_TYPE } from '@/types/chat';
import {
  DATA_FLOW_DB_ROLE_LABEL,
  DATA_FLOW_NODE_ID,
  DATA_FLOW_TABLE_PURPOSES,
} from '@/views/DiagnosisWorkspace/constants';

/** 可编辑节点所属层（用于区分「抽取」与「概览」两处改写入口） */
export type DataFlowNodeLayer = 'SOURCE_EXTRACT' | 'OVERVIEW' | 'STATISTICS' | '';

/** 依据节点类型推导可编辑层；只读节点返回空串 */
export function dataFlowNodeLayer(node: DataFlowNode | null | undefined): DataFlowNodeLayer {
  if (!node) return '';
  if (node.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL) return 'SOURCE_EXTRACT';
  if (node.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL) return 'OVERVIEW';
  if (
    node.nodeType === DATA_FLOW_NODE_TYPE.DEPARTMENT_SQL ||
    node.nodeType === DATA_FLOW_NODE_TYPE.PATIENT_SQL
  )
    return 'STATISTICS';
  return '';
}

/** 数据库角色中文标签（兜底未登记） */
function databaseLabel(role: string): string {
  return (DATA_FLOW_DB_ROLE_LABEL[role] ?? role) || '未登记';
}

/** 节点业务用途描述（对齐参考实现 nodePurpose，结合当前项目节点 ID 与类型） */
export function dataFlowNodePurpose(node: DataFlowNode | null | undefined): string {
  if (!node) return '';
  const tables = node.tableNames ?? [];
  const tableText = tables.slice(0, 2).join('、');

  if (node.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL) {
    return `从${databaseLabel(node.databaseRole)}读取当前指标需要的原始记录，按统计窗口、排除和去重规则整理后，生成供正式统计使用的指标中间数据。`;
  }
  if (node.nodeType === DATA_FLOW_NODE_TYPE.EXTENDED_EVENT_SQL) {
    const title = node.title || '当前事件';
    return `从医院业务表识别「${title.replace('拓展事件 SQL · ', '')}」事件，并写入患者事件表，供后续抽取 SQL 继续关联。`;
  }
  if (node.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL) {
    return '读取当前指标中间数据，按照生效口径计算分子、分母、结果值和是否达标；这里决定指标卡片最终显示的合计结果。';
  }
  if (node.nodeType === DATA_FLOW_NODE_TYPE.DEPARTMENT_SQL) {
    return '按科室重新聚合当前指标中间数据，用于查看每个科室的分子、分母和结果，帮助定位某个科室漏数或多算。';
  }
  if (node.nodeType === DATA_FLOW_NODE_TYPE.PATIENT_SQL) {
    return '按当前口径查询进入分子或分母的患者记录，为数据确认、患者澄清和具体记录追溯提供明细。';
  }
  if (node.nodeType === DATA_FLOW_NODE_TYPE.TABLE) {
    if (node.id === DATA_FLOW_NODE_ID.BUSINESS_TABLES) {
      return `展示当前抽取链路在医院业务库实际读取的${tableText || '原始业务表'}，用于核对患者、医嘱、转科等原始记录是否存在。`;
    }
    if (node.id === DATA_FLOW_NODE_ID.PATIENT_EVENT) {
      return '保存由各类拓展事件 SQL 生成的标准患者事件；当前指标的抽取 SQL 从这里读取已经整理好的事件记录。';
    }
    if (node.id === DATA_FLOW_NODE_ID.TARGET_TABLE) {
      return `保存当前指标抽取后的标准化记录${tableText ? `（${tableText}）` : ''}，概览、科室和患者明细 SQL 都以这里的数据为统计基础。`;
    }
    if (node.id === DATA_FLOW_NODE_ID.STATISTIC_PARAMETERS) {
      return '提供目标值、比较方向等指标参数，供概览统计判断是否达标；这里不保存患者或业务明细。';
    }
    if (node.id === DATA_FLOW_NODE_ID.REAL_EXISTING_TABLES) {
      return `展示当前指标直接使用的真实库现有数据表${tableText ? `（${tableText}）` : ''}；本指标不再单独执行源表抽取。`;
    }
  }

  return (
    node.description ||
    (tableText
      ? `当前节点使用${tableText}完成本环节的数据处理。`
      : '当前知识库尚未补充该节点的具体业务作用。')
  );
}

/** 节点操作提示（对齐参考实现 nodeHint） */
export function dataFlowNodeHint(node: DataFlowNode | null | undefined): string {
  if (!node) return '';
  switch (node.nodeType) {
    case DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL:
      return '建议先查看当前正式 SQL，再按需重新抽取或修改 SQL。';
    case DATA_FLOW_NODE_TYPE.OVERVIEW_SQL:
      return '只有中间数据正确、但分子分母计算不对时，才修改本节点。';
    case DATA_FLOW_NODE_TYPE.DEPARTMENT_SQL:
      return '用于核对科室汇总，不在这里修改正式口径。';
    case DATA_FLOW_NODE_TYPE.PATIENT_SQL:
      return '用于查看分子分母明细，不在这里修改正式口径。';
    case DATA_FLOW_NODE_TYPE.EXTENDED_EVENT_SQL:
      return '用于核对业务事件怎样生成；当前标准模式只读查看。';
    default:
      return '这是只读数据节点，用于确认本环节实际使用的数据。';
  }
}

/** 表用途：优先知识库内置说明，其次表字典描述，最后兜底文案 */
export function dataFlowTablePurpose(table: string, description: string | undefined): string {
  return (
    DATA_FLOW_TABLE_PURPOSES[table.toUpperCase()] ??
    description ??
    '知识库只登记了该表名称，尚未补充业务用途。'
  );
}
