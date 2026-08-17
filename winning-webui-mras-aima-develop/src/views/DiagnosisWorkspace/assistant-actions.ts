import { DIAGNOSIS_ASSISTANT_ACTION } from '@/constants/diagnosis';

export const ASSISTANT_QUICK_ACTIONS = [
  {
    value: DIAGNOSIS_ASSISTANT_ACTION.PATIENT_CLARIFICATION,
    label: '患者澄清',
    icon: 'mdi-account-question-outline',
  },
  {
    value: DIAGNOSIS_ASSISTANT_ACTION.AI_GENERATE_SQL,
    label: 'AI 生成对应 SQL',
    icon: 'mdi-robot-outline',
  },
  {
    value: DIAGNOSIS_ASSISTANT_ACTION.UPLOAD_SQL,
    label: '手动上传 SQL',
    icon: 'mdi-file-upload-outline',
  },
  {
    value: DIAGNOSIS_ASSISTANT_ACTION.AUTONOMOUS,
    label: 'AI 自主排查',
    icon: 'mdi-text-box-search-outline',
  },
] as const;
