import type { FlowStage, TraceEdgeType, TraceNodeStatus, TraceNodeType } from './types';

/** flow_stage → 配置映射 */
export const FLOW_STAGE_CONFIG: Record<FlowStage, { title: string; color: string; order: number }> =
  {
    context: { title: '上下文与指标识别', color: '#4caf50', order: 1 },
    planning: { title: '规划与目标校验', color: '#2196f3', order: 2 },
    compilation: { title: 'IR编译与能力选择', color: '#9c27b0', order: 3 },
    execution: { title: '工具与数据库执行', color: '#ff9800', order: 4 },
    verification: { title: 'Evidence验证与安全检查', color: '#f44336', order: 5 },
    answer: { title: '回答组织与会话保存', color: '#009688', order: 6 },
  } as const;

/** node_type → 图标 */
export const NODE_TYPE_ICON: Record<TraceNodeType, string> = {
  llm: 'mdi-brain',
  tool: 'mdi-wrench',
  database: 'mdi-database',
  code: 'mdi-code-braces',
  storage: 'mdi-folder',
};

/** node_type → 颜色 */
export const NODE_TYPE_COLOR: Record<TraceNodeType, string> = {
  llm: 'primary',
  tool: 'success',
  database: 'warning',
  code: 'info',
  storage: 'grey',
};

/** node_type → 耗时汇总 key */
export const NODE_TYPE_TIMING_KEY: Record<TraceNodeType, keyof import('./types').TimingSummary> = {
  llm: 'llmMs',
  tool: 'toolMs',
  database: 'toolMs',
  code: 'codeMs',
  storage: 'storageMs',
};

/** node status → 图标 */
export const NODE_STATUS_ICON: Record<TraceNodeStatus, string> = {
  success: 'mdi-check-circle',
  failed: 'mdi-close-circle',
  error: 'mdi-alert-circle',
};

/** node status → 颜色 */
export const NODE_STATUS_COLOR: Record<TraceNodeStatus, string> = {
  success: 'success',
  failed: 'error',
  error: 'error',
};

/** edge_type → 线条样式 */
export const EDGE_TYPE_STYLE: Record<
  TraceEdgeType,
  { color: string; animated: boolean; strokeDasharray?: string }
> = {
  parent: { color: '#999', animated: false },
  sequence: { color: '#666', animated: false },
  replan: { color: '#ff9800', animated: true },
  failure: { color: '#f44336', animated: true, strokeDasharray: '6 3' },
};

/** edge_type → 中文标签 */
export const EDGE_TYPE_LABEL: Record<TraceEdgeType, string> = {
  parent: '父子',
  sequence: '顺序',
  replan: '重规划',
  failure: '失败',
};

/** final_status → 映射 */
export const FINAL_STATUS_MAP: Record<
  import('./types').TraceFinalStatus,
  { label: string; color: string }
> = {
  running: { label: '运行中', color: 'info' },
  success: { label: '成功', color: 'success' },
  failed: { label: '失败', color: 'error' },
  incomplete: { label: '需澄清', color: 'warning' },
};
