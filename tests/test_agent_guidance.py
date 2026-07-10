import unittest
from pathlib import Path


class AgentGuidanceTest(unittest.TestCase):
    def test_agent_guidance_requires_product_usability(self) -> None:
        text = (Path(__file__).resolve().parents[1] / "agent.md").read_text(
            encoding="utf-8"
        )

        self.assertIn("## 产品工程原则", text)
        self.assertIn("### 可用性", text)
        self.assertIn("### 可维护性", text)
        self.assertIn("### 易上手", text)
        self.assertIn("不得把命令行作为普通用户的主要操作入口", text)
        self.assertIn("加载中、空数据、成功、失败和权限失效", text)


if __name__ == "__main__":
    unittest.main()
