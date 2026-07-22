# 医院认证与规则只读契约 v1

## 认证约束

- 登录：`POST /api/auth/hospital/login`
- 修改密码：`POST /api/auth/hospital/change-password`
- 退出：`POST /api/auth/hospital/logout`
- 请求字段采用 `snake_case`；未知身份字段必须拒绝。
- 密码摘要为 PBKDF2-HMAC-SHA256、310000 次、256 bit、Base64 编码。
- 会话令牌为 32 字节随机数的无填充 Base64URL；数据库只保存 SHA-256 十六进制摘要。
- Java 运行时把会话摘要保存到 SQLite；任何接口都不得保存或回传明文令牌。
- 冻结密码与令牌测试向量见 `auth-crypto-vector.json`，该文件只含测试数据。

登录成功响应：

```json
{
  "token": "opaque-token",
  "token_type": "bearer",
  "expires_at": "2026-07-22T08:00:00",
  "user_id": "user_001",
  "account_id": "doctor",
  "hospital_id": "hospital_001",
  "permissions": ["indicator_detail_view"],
  "must_change_password": false
}
```

认证失败统一返回 `{"detail":"中文说明"}`。401 响应必须带 `WWW-Authenticate: Bearer`。

## 规则只读

- 搜索：`GET /api/kb/rules/search?query=...&limit=5`
- 生效口径：`GET /api/kb/rules/{rule_id}/effective`
- 两个规则接口都要求 `Authorization: Bearer ...`。
- 医院范围只从已认证会话注入；查询参数中的 `hospital_id` 仅用于兼容检查，若与主体医院不同必须返回 403。
- 搜索顺序为当前医院已审批自定义指标优先，然后是国标指标。
- 生效口径为本院有效覆盖后回退国标；截止时间使用左闭右开边界。
- 规则内容直接读取 `core-rules-wiki`，不依赖 Python 服务或 MySQL 知识库。
