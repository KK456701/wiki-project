# Vue 3 前端

Vue 3 + TypeScript 是当前唯一前端，与 Java 17 + Spring Boot 单运行时配套；已经覆盖登录、模型列表、SSE 对话、Excel 上传、Trace、运行观察、元数据、医学术语、指标监控和指标实施完整闭环。

`/implementation` 按设计稿版本推进取数要求、字段映射、确定性 SQL、DBHub 试运行、提交审批、批准/驳回和本院新增指标历史恢复。管理员写操作需要页面内单独登录，医院范围仍由当前医院会话限定。

```powershell
cd F:\A-wiki-project\frontend-vue
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。Vite 把 `/api` 代理到 `http://127.0.0.1:8765`；先确保 Java 服务已启动。

生产构建由根目录 `scripts/build-java-vue.ps1` 完成，并把 `dist/` 放入 Spring Boot 最终 JAR。生产部署不需要 Node.js 常驻运行。
