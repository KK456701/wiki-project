# Java 迁移服务

这是渐进迁移骨架，不会替换当前 `8765` 端口上的 FastAPI。第一批只冻结契约、提供兼容健康接口，并验证 Java 可以继续调用现有 DBHub sidecar。

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

配置在 `src/main/resources/application.yml`。真实密码和令牌不得写入本目录；Java 服务不直连医院 SQL Server。

后续批次会按照 `docs/migration/java-vue-migration.md` 逐步迁移登录、规则查询、Agent IR、工具网关、Evidence 和 Trace。只有同一接口通过契约对比后，才允许在入口层切流。
