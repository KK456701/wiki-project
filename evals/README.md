# Agent 轻量 Eval

本目录只使用项目现有的 PyYAML、Pydantic 和 pytest，不引入新的运行依赖。

- `cases.yaml`：业务语义与安全样例。
- `run_model_matrix.py`：按需调用指定模型生成 RequestPlan，并用当前术语库的混合指标识别器核对复合请求；没有显式传入 `--models` 时不会调用任何模型。
- `test_eval_dataset.py`：只校验数据集结构和覆盖面，不调用模型。

手动运行模型矩阵：

```powershell
python evals\run_model_matrix.py --models ollama-qwen3-4b ollama-qwen3-8b-thinking deepseek-v4-flash
```

模型 ID 以本机 `config.yaml` 的 `models` 配置为准。结果默认写入 `evals/results/`，该目录不应提交。
