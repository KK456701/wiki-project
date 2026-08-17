const FIELD_LABELS: Record<string, string> = {
  PERSON_ID: '患者标识',
  PERSON_NAME: '患者姓名',
  PATIENT_ID: '患者标识',
  PATIENT_NAME: '患者姓名',
  FULL_NAME: '患者姓名',
  ENCOUNTER_ID: '就诊标识',
  ENCOUNTER_NO: '就诊号',
  VISIT_NO: '就诊号',
  INPATIENT_NO: '住院号',
  MEDICAL_RECORD_NO: '病案号',
  IMRN: '住院号',
  ENCOUNTER_CAUSE: '就诊原因',
  CURRENT_DEPT_ID: '当前科室标识',
  CURRENT_DEPT_NAME: '当前科室名称',
  CURRENT_WARD_ID: '当前病区标识',
  CURRENT_WARD_NAME: '当前病区名称',
  ORIGIN_DEPT_ID: '原科室标识',
  ORIGIN_DEPT_NAME: '原科室名称',
  ORIGIN_WARD_ID: '原病区标识',
  ORIGIN_WARD_NAME: '原病区名称',
  TARGET_DEPT_ID: '转入科室标识',
  TARGET_DEPT_NAME: '转入科室名称',
  TARGET_WARD_ID: '转入病区标识',
  TARGET_WARD_NAME: '转入病区名称',
  CURRENT_MEDICAL_GROUP_ID: '当前医疗组标识',
  CURRENT_MEDICAL_GROUP_NAME: '当前医疗组名称',
  ADMITTED_AT: '入院时间',
  ADMITTED_TO_WARD_AT: '入区时间',
  DISCHARGED_AT: '出院时间',
  DISCHARGED_FROM_WARD_AT: '出区时间',
  TRANSFER_AT: '转科时间',
  TRANSFER_TYPE: '转科类型',
  TRANSFER_WITHIN_TWO_DAY: '48小时内转科判定',
  CURRENT_ADMITTER_ID: '当前入院医师标识',
  CURRENT_ADMITTER_NAME: '当前入院医师姓名',
  CURRENT_ATTENDER_NAME: '当前主治医师姓名',
  CHIEF_DOCTOR_NAME: '主任医师姓名',
  DUTY_DOCTOR_ID: '值班医师标识',
  DUTY_DOCTOR_NO: '值班医师编号',
  DUTY_DOCTOR_NAME: '值班医师姓名',
  EMPLOYEE_ID: '医务人员标识',
  EMPLOYEE_NAME: '医务人员姓名',
  ORGANIZATION_ID: '机构标识',
  ORGANIZATION_NAME: '机构名称',
  HOSPITAL_AREA_ID: '院区标识',
  HOSPITAL_SOID: '医院机构标识',
  MRAS_TARGET_DEFINITION_ID: '目标定义标识',
  MRAS_EVENT_DEFINE_ID: '事件定义标识',
  EVENT_NO: '事件编号',
  EVENT_NAME: '事件名称',
  EVENT_AT: '事件时间',
  EXTRACT_AT: '抽取时间',
  PRESCRIBED_AT: '医嘱开立时间',
  SUBMIT_AT: '提交时间',
  TIMEOUT_AT: '超时时间',
  WARD_DISCHARGED_AT: '出区时间',
  CREATED_AT: '创建时间',
  CREATED_BY: '创建人',
  MODIFIED_AT: '修改时间',
  MODIFIED_BY: '修改人',
  BIZ_ROLE_ID: '业务角色标识',
  BIZ_ID: '业务记录标识',
  MEMO: '备注',
  IS_DEL: '删除标志',
  STAND_FLAG: '标准标志',
  VERSION: '版本',
  NUMERATOR: '分子',
  NUMERATOR_COUNT: '分子数量',
  DENOMINATOR: '分母',
  DENOMINATOR_COUNT: '分母数量',
  RESULT_VALUE: '指标结果',
  TARGET_VALUE: '目标值',
  DEPT_ID: '科室标识',
  DEPT_NAME: '科室名称',
  WARD_ID: '病区标识',
  WARD_NAME: '病区名称',
  MRAS_BUSINESS_FIRSTVISIT_ID: '首诊业务记录标识',
  __numerator_contribution: '分子贡献值',
  __denominator_contribution: '分母贡献值',
  __sample_minutes: '样本值（分钟）',
  __sample_order: '样本排名',
  __sample_count: '样本总数',
  __is_median_sample: '中位样本',
};

const HIDDEN_FIELDS = new Set(['__detail_id', '__meets_numerator']);
const NORMALIZED_FIELD_LABELS = Object.fromEntries(
  Object.entries(FIELD_LABELS).map(([key, label]) => [
    key.replaceAll('_', '').toUpperCase(),
    label,
  ]),
);

export function visibleDetailKeys(row: Record<string, unknown> | undefined): string[] {
  return row ? Object.keys(row).filter((key) => !HIDDEN_FIELDS.has(key)) : [];
}

export function detailFieldLabel(key: string): string {
  const upperKey = key.toUpperCase();
  return (
    FIELD_LABELS[key] ??
    FIELD_LABELS[upperKey] ??
    NORMALIZED_FIELD_LABELS[upperKey.replaceAll('_', '')] ??
    key
  );
}

export function formatDetailCell(key: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  if (key === '__is_median_sample') return value === true || value === 1 ? '是' : '否';
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}
