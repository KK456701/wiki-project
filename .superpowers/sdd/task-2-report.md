STATUS: done

修改文件列表:
- `app/db_access/query_result.py`
- `app/db_access/business_db.py`
- `tests/test_business_db_mcp.py`
- `.superpowers/sdd/task-2-report.md`

提交哈希:
- TBD

实际测试命令和结果:
- `python -B -m unittest tests.test_business_db_mcp -v` -> PASS
- `python -B -m unittest` -> PASS, 44 tests

自审结论:
- `BusinessDBClient.execute_select` 在调用 MCP 前完成只读校验，并将返回值封装为 `QueryResult`。
- `check_available` 复用只读查询路径，返回包含 `ok/source/tool_name/row_count/duration_ms` 的状态字典。
- 已用失败测试驱动实现，且全量单测通过。

concerns:
- 当前只覆盖了 `SELECT` 只读约束的主要路径；如果后续 MCP 客户端需要支持更复杂的 SQL 解析规则，可能需要补更细的语法边界测试。
