STATUS: done

修改文件列表:
- `app/db_access/query_result.py`
- `app/db_access/business_db.py`
- `tests/test_business_db_mcp.py`
- `.superpowers/sdd/task-2-report.md`

提交:
- 初始实现: `b0584d1 feat: 增加业务库 MCP 只读客户端`
- 本次修复: 当前提交 `fix: 修正业务库 MCP SQL 分号校验`

实际测试命令和结果:
- `python -B -m unittest tests.test_business_db_mcp -v` -> PASS，4 个测试通过
- `python -B -m py_compile app\db_access\business_db.py app\db_access\query_result.py` -> PASS

修复内容:
- `BusinessDBClient._assert_select` 允许单条 SELECT 末尾携带一个分号，例如 `SELECT 1;`。
- 仍然拒绝多语句 SQL，例如 `SELECT 1; SELECT 2`。
- 仍然在调用 MCP 前拒绝非 SELECT、写入和结构变更 SQL。
- 错误提示已恢复为中文。

自审结论:
- 业务库访问仍然只通过注入的 MCP 执行函数，不直接连接业务库。
- 只读边界测试已覆盖：正常 SELECT、末尾分号 SELECT、非 SELECT、多语句。

concerns:
- 当前只做轻量 SQL 边界校验，后续如果要支持更复杂 SQL 语法，可再接入 SQL parser 做更严格 AST 级校验。
