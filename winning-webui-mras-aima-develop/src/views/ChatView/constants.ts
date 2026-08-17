export interface RecommendedQuestion {
  id: string;
  text: string;
}

export const CHAT_WORKSPACE_ROUTE = {
  INDICATOR_DIAGNOSIS: '/diagnosis',
} as const;

/**
 * 推荐问题列表（静态兜底）
 *
 * 当前为前端硬编码兜底文案；后端若提供「推荐问题」接口，应改为调用该接口获取并替换本常量。
 * 在此之前保留静态列表，避免首页推荐区出现空态。
 */
export const RECOMMENDED_QUESTIONS: RecommendedQuestion[] = [
  {
    id: 'q1',
    text: '首诊责任制指标的口径是什么',
  },
  {
    id: 'q2',
    text: '急会诊有效率的口径是什么',
  },
  {
    id: 'q3',
    text: '计算患者入院 48小时内转科的比例，时间范围是 2025.01.01——2025.03.31',
  },
];

/** 批量指标核算 7 个步骤的 nodeName 列表（按顺序） */
export const EXECUTION_STEP_NAMES = [
  'batch_indicator_enumerate',
  'batch_data_initialization_validation',
  'source_data_extraction',
  'real_snapshot_data_validation',
  'real_database_overview',
  'batch_indicator',
  'batch_result_merge',
];

/** 步骤名称 → 中文标签 */
export const EXECUTION_STEP_LABELS: Record<string, string> = {
  batch_indicator_enumerate: '确认本次指标清单',
  batch_data_initialization_validation: '数据初始化校验',
  source_data_extraction: '抽取数据到真实库',
  real_snapshot_data_validation: '真实库计算前校验',
  real_database_overview: '执行真实库概览 SQL',
  batch_indicator: '完成单项指标计算',
  batch_result_merge: '汇总本次计算结果',
};

/** 步骤名称 → Material Icons */
export const EXECUTION_STEP_ICONS: Record<string, string> = {
  batch_indicator_enumerate: 'mdi-format-list-checks',
  batch_data_initialization_validation: 'mdi-shield-check-outline',
  source_data_extraction: 'mdi-database-arrow-right-outline',
  real_snapshot_data_validation: 'mdi-database-check-outline',
  real_database_overview: 'mdi-table-eye',
  batch_indicator: 'mdi-calculator-variant-outline',
  batch_result_merge: 'mdi-chart-box-outline',
};
