## Windows / PowerShell 约束

当前为原生 Windows 环境。执行命令前不要假定 PowerShell 版本；必要时使用 `$PSVersionTable.PSVersion` 检查。

- 默认生成 PowerShell 命令。只有明确通过 WSL、Git Bash 或 Linux 环境执行时，才使用 Bash 语法。
- 不要使用 Bash heredoc、`export`、`source`、`$(pwd)` 等 Bash 专用写法。
- 不要依赖 PowerShell 7 专属语法，除非已经确认当前进程由 `pwsh` 运行。
- 不需要变量插值的正则表达式和字面量优先使用单引号，避免 PowerShell 提前解释 `$`、反引号等字符。
- 向 Python 传递多行代码时，使用 PowerShell here-string。
- 使用 ripgrep 筛选文件时优先使用 `-g/--glob`。
- 对可能包含通配符或特殊字符的真实文件路径，优先使用 `-LiteralPath`。
- 启动、重启、停止服务或创建后台进程时，禁止依赖 PATH 中的 `pwsh.exe`，也不要调用 `C:\Users\*\AppData\Local\Microsoft\WindowsApps\pwsh.exe`。该路径是 WindowsApps 应用执行别名，桌面执行策略会反复拦截。
- 上述进程控制命令统一显式使用系统 PowerShell 真实路径：`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File <脚本路径>`。复杂启动逻辑必须先写入项目脚本，再通过 `-File` 执行；不要把长篇 `Start-Process` 逻辑内联到命令参数中。
- Java 服务优先使用项目已有的稳定启动/重启脚本。若开发热启动链路异常但 JAR 已构建成功，可改用 `F:\kaifa\jdk\bin\java.exe -jar <jar>` 的项目脚本启动，并继续显式通过上述系统 PowerShell 执行脚本。

## 多仓库提交与推送规则

- `F:\A-wiki-project` 是个人 GitHub 主仓，远端为 `origin`，日常目标分支为 `main`。
- `F:\A-wiki-project\backend-java` 是独立的公司后端仓，远端为 `origin`，日常目标分支为 `test`。
- `F:\A-wiki-project\winning-webui-mras-aima-develop` 是当前唯一继续开发的公司前端仓，远端为 `origin`；默认在 `codex/kk` 分支提交并推送到 `origin/codex/kk`，由其他同事发起或处理 PR。不要直接向 `develop`、`test` 提交前端改动。
- `F:\A-wiki-project\frontend-vue` 是已停用的旧前端，不再作为开发、构建或部署来源。
- 每次完成需要提交和推送的开发任务时，必须分别检查实际涉及的仓库；本次改动分别提交后，在同一批次把新前端推送到 `origin/codex/kk`、个人仓推送到 `origin/main`、后端仓推送到 `origin/test`。即使某个仓库没有新提交，也要确认其目标分支与远端同步，并在交付说明中明确写出结果。
- 三个仓库必须独立执行 `status`、暂存、提交和推送。禁止在个人主仓中暂存或提交 `backend-java`、`winning-webui-mras-aima-develop` 下的变化；它们只由各自独立仓提交。
- 只暂存当前任务明确涉及的文件，保留用户和同事的其他未提交修改；不得使用 `git add -A`、`git add .` 等扩大暂存范围的命令。
- 不提交运行数据库、构建产物、日志、本地凭据或临时文件，尤其不得提交 `backend-java/runtime/wiki_agent_runtime.db`。
- 推送前允许执行只读的 `git fetch` 检查远端状态。除非用户明确要求，不执行 `pull`、强制推送、重置或覆盖远端历史。
