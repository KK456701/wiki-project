# Vue 3 迁移前端

第一批 Vue 3 + TypeScript 前端外壳直接代理当前 FastAPI，因此登录、模型列表、SSE 对话、Excel 上传和 Trace 仍使用现有生产接口。它不会替换 `web/` 原生页面。

```powershell
cd F:\A-wiki-project\frontend-vue
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。Vite 把 `/api` 代理到 `http://127.0.0.1:8765`；先确保现有 FastAPI 已启动。

生产迁移完成后执行 `npm run build`，再把 `dist/` 作为静态资源放入 Spring Boot 最终 JAR。生产部署不需要 Node.js 常驻运行。
