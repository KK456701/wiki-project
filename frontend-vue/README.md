# Vue 3 迁移前端

Vue 3 + TypeScript 前端当前可代理 FastAPI，也可切到 Java 影子服务；已经覆盖登录、模型列表、SSE 对话、Excel 上传、Trace、运行观察、元数据、医学术语、指标监控和指标实施完整闭环。它不会在迁移验收前替换 `web/` 原生页面。

`/implementation` 按设计稿版本推进取数要求、字段映射、确定性 SQL、DBHub 试运行、提交审批、批准/驳回和本院新增指标历史恢复。管理员写操作需要页面内单独登录，医院范围仍由当前医院会话限定。

```powershell
cd F:\A-wiki-project\frontend-vue
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。Vite 把 `/api` 代理到 `http://127.0.0.1:8765`；先确保现有 FastAPI 已启动。

生产迁移完成后执行 `npm run build`，再把 `dist/` 作为静态资源放入 Spring Boot 最终 JAR。生产部署不需要 Node.js 常驻运行。
