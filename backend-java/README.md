# Java 迁移服务

这是渐进迁移影子服务，不会替换当前 `8765` 端口上的 FastAPI。前两批已完成基础契约、DBHub 客户端、医院认证兼容和规则只读接口；规则写入、审批和 Agent 执行仍由 Python 负责。

## 本地运行

```powershell
cd F:\A-wiki-project\backend-java
mvn -s maven-settings.xml test
mvn -s maven-settings.xml spring-boot:run
```

仓库内的 `maven-settings.xml` 修正本机已失效的 HTTP 阿里云镜像，使用 Maven Central HTTPS，并沿用当前 Windows 本地代理 `127.0.0.1:7897`；不包含凭据。若部署机不使用该代理，删除其中 `<proxies>` 段即可。

默认监听 `http://127.0.0.1:8766`：

- `GET /api/health`：与现有 FastAPI 的基础健康契约一致。
- `GET /api/migration/status`：查看当前迁移阶段，明确当前权威运行时仍是 Python。
- `GET /api/mcp/dbhub/sources`：通过 Java 客户端访问现有 DBHub sidecar。
- `POST /api/auth/hospital/login`：使用现有 MySQL 医院账号登录，签发 Python 可识别的共享会话。
- `POST /api/auth/hospital/change-password`、`POST /api/auth/hospital/logout`：兼容现有认证语义。
- `GET /api/kb/rules/search`：按登录主体所在医院搜索规则。
- `GET /api/kb/rules/{rule_id}/effective`：读取本院生效口径；客户端传入其他医院会被拒绝。

配置在 `src/main/resources/application.yml`。运行库凭据通过 `WIKI_RUNTIME_DB_URL`、`WIKI_RUNTIME_DB_USER` 和 `WIKI_RUNTIME_DB_PASSWORD` 提供，真实密码和令牌不得写入本目录；Java 服务不直连医院 SQL Server。

已登录情况下可以运行 Python/Java 双跑：

```powershell
$env:MIGRATION_HOSPITAL_TOKEN = '<当前登录令牌>'
$env:MIGRATION_HOSPITAL_ID = 'hospital_001'
python ..\scripts\compare_java_python_read_api.py
```

脚本只输出安全字段的差异，不打印令牌。跨语言密码算法测试使用 `contracts/migration/v1/auth-crypto-vector.json` 中的非生产测试向量。

后续批次会按照 `docs/migration/java-vue-migration.md` 迁移术语与只读元数据、Agent IR、工具网关、Evidence 和 Trace。只有同一接口通过契约对比后，才允许在入口层切流。
