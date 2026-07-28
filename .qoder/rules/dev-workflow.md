---
trigger: always_on
---

# 开发验证与协作规则

## 修改功能后的验证流程（热重启，不要每次打包）

- 日常修改后端代码后，**默认走热重启验证**，不要执行 `mvn package` 重新打 jar：
  1. 服务未运行时：`pwsh scripts/dev-run.ps1` 启动开发服务（`mvn spring-boot:run -DskipTests`，免打包，默认端口 8765）
  2. 服务运行中改完代码后：`pwsh scripts/dev-run.ps1 -Recompile`（增量编译），devtools 检测到 class 变化后约 10 秒自动热重启，无需手动杀进程
- 只有正式发布/交付时才用 `scripts/build-java-vue.ps1` 打完整 jar 包。
- 后端服务端口固定为 **8765**。

## 沟通与提交规范

- 所有回复、说明、进度汇报、总结**必须使用中文**。
- 每完成一批更新或修复后，提交并推送到 GitHub（origin main），提交信息用清晰的中文总结；只提交源码改动，不提交构建产物和临时文件。
- 内层 `backend-java/.git` 是 TFS 仓库，**永远不要向 TFS 推送**。
