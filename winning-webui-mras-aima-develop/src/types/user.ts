/** 用户信息（来自 60 统一登录，存储于 sessionStorage 的 key 为 userInfo） */
export interface UserInfo {
  employeeId: string;
  userId: string;
  employeeNo: string;
  employeeName: string;
  hospitalSOID: string;
  userHospitalSOID: string;
  orgName: string;
  isGroup: boolean;
  currentOrgId: string;
  multiHospitalFlag: string;
  groupOrgId: string;
  orgId: string;
  hospitalName: string;
  orgAlias: string;
}
