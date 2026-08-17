/**
 * 数据链路核查（第 3 步）——本地常量
 *
 * 仅属于 DiagnosisWorkspace，不复用 ChatView 的展示常量，
 * 满足「views 一级目录之间不得相互引用」约束。
 */

/** 数据链路节点类型 → 中文标签 */
export const DATA_FLOW_NODE_TYPE_LABEL: Record<string, string> = {
  TABLE: '数据表',
  SOURCE_EXTRACT_SQL: '源表抽取 SQL',
  EXTENDED_EVENT_SQL: '拓展事件 SQL',
  OVERVIEW_SQL: '概览统计 SQL',
  DEPARTMENT_SQL: '科室统计 SQL',
  PATIENT_SQL: '患者明细 SQL',
  RESULT: '指标结果',
  CONFIGURATION: '配置状态',
};

/** 数据库角色 → 中文标签 */
export const DATA_FLOW_DB_ROLE_LABEL: Record<string, string> = {
  BUSINESS: '业务库',
  SYNC: '同步/ETL',
  REAL: '真实库',
  KNOWLEDGE: '知识库',
};

/** 数据链路图例项 */
export const DATA_FLOW_LEGEND_ITEMS = [
  { label: '数据表', color: 'primary' },
  { label: '源表抽取', color: 'warning' },
  { label: '拓展事件', color: 'secondary' },
  { label: '概览统计', color: 'success' },
  { label: '科室/患者统计', color: 'success' },
  { label: '指标结果', color: 'primary' },
] as const;

/**
 * 节点类型 → Vuetify 语义色（与图例 DATA_FLOW_LEGEND_ITEMS、G6 配色 NODE_TYPE_VAR 保持一致）。
 * 用于节点标签 chip、详情头部等处的统一着色。
 */
export const DATA_FLOW_NODE_TYPE_COLOR: Record<string, string> = {
  TABLE: 'primary',
  SOURCE_EXTRACT_SQL: 'warning',
  EXTENDED_EVENT_SQL: 'secondary',
  OVERVIEW_SQL: 'success',
  DEPARTMENT_SQL: 'success',
  PATIENT_SQL: 'success',
  RESULT: 'primary',
  CONFIGURATION: 'grey',
};

/** 数据链路固定节点 ID（对齐后端 dataflow-spec 约定，用于节点用途描述等逻辑判断） */
export const DATA_FLOW_NODE_ID = {
  BUSINESS_TABLES: 'business-tables',
  PATIENT_EVENT: 'patient-event',
  TARGET_TABLE: 'target-table',
  STATISTIC_PARAMETERS: 'statistic-parameters',
  REAL_EXISTING_TABLES: 'real-existing-tables',
  SOURCE_EXTRACT_SQL: 'source-extract-sql',
  OVERVIEW_SQL: 'overview-sql',
} as const;

/** 知识库内置表名的业务用途兜底说明（表字典缺失描述时使用） */
export const DATA_FLOW_TABLE_PURPOSES: Record<string, string> = {
  INPATIENT_ENCOUNTER: '住院患者主表：记录每次住院就诊、入出区时间、患者和当前科室等基础信息。',
  INPAT_TRANSFER: '转科转区明细表：记录患者每次转科或转区的时间、转出科室和转入科室。',
  ORGANIZATION: '科室病区字典表：把科室、病区编码转换为实施人员可读的名称。',
  INPATIENT_PARTICIPANT: '住院参与人员表：记录经治、主管等参与本次住院的医务人员。',
  EMPLOYEE_INFO: '员工信息表：把员工标识转换为医生或职工姓名。',
};
