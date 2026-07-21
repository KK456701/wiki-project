# 医院认证与规则只读契约 v1

## 认证兼容

- 登录：`POST /api/auth/hospital/login`
- 修改密码：`POST /api/auth/hospital/change-password`
- 退出：`POST /api/auth/hospital/logout`
- 请求字段采用 `snake_case`；未知身份字段必须拒绝。
- 密码摘要为 PBKDF2-HMAC-SHA256、310000 次、256 bit、Base64 编码。
- 会话令牌为 32 字节随机数的无填充 Base64URL；数据库只保存 SHA-256 十六进制摘要。
- Python 与 Java 必须能互认对方签发、保存到同一 MySQL 表的会话。
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

认证失败继续返回 FastAPI 兼容形状：`{"detail":"中文说明"}`。401 响应必须带 `WWW-Authenticate: Bearer`。

## 规则只读

- 搜索：`GET /api/kb/rules/search?query=...&limit=5`
- 生效口径：`GET /api/kb/rules/{rule_id}/effective`
- 两个 Java 影子接口都要求 `Authorization: Bearer ...`。
- 医院范围只从已认证会话注入；查询参数中的 `hospital_id` 仅用于迁移期兼容检查，若与主体医院不同必须返回 403。
- 搜索顺序保持 Python 语义：当前医院已审批自定义指标优先，然后是国标指标。
- 生效口径保持 Python 合并语义：本院有效覆盖后回退国标；截止时间使用左闭右开边界。
- 本批只迁移读取，不迁移规则写入、审批、发布和回滚。

双跑对比使用 `scripts/compare_java_python_read_api.py`。令牌和医院编号只通过环境变量传入，不写入仓库或命令行参数。
