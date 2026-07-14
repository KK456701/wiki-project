import json
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ChatMarkdownUiTest(unittest.TestCase):
    def test_page_loads_renderer_before_inline_chat_script(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        renderer = '<script src="/static/chat-markdown.js"></script>'
        self.assertIn(renderer, html)
        self.assertLess(html.index(renderer), html.index("<script>"))
        self.assertIn("return renderAssistantMarkdown(text || \"\")", html)
        self.assertIn(".message-table", html)
        self.assertIn(".message-code", html)

    def test_renderer_builds_tables_and_code_while_escaping_html(self) -> None:
        markdown = (
            "| 统计项 | 数量 |\n"
            "|---|---:|\n"
            "| 分子 | 8 |\n\n"
            "```sql\nSELECT 1\n```\n\n"
            "<script>alert(1)</script>"
        )
        script = f"""
const renderer = require('./web/chat-markdown.js');
const html = renderer.renderAssistantMarkdown({json.dumps(markdown, ensure_ascii=False)});
process.stdout.write(html);
"""

        result = subprocess.run(
            ["node", "-e", script],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=True,
        )

        self.assertIn('<table class="message-table">', result.stdout)
        self.assertIn('<pre class="message-code"><code class="language-sql">', result.stdout)
        self.assertNotIn("<script>", result.stdout)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", result.stdout)

    def test_renderer_collapses_technical_details_without_allowing_html(self) -> None:
        markdown = (
            "医生可见说明\n\n"
            ":::details 查看技术详情（供信息科和实施人员）\n"
            "| 本院数据库位置 |\n"
            "|---|\n"
            "| consult_record.request_time |\n\n"
            "```sql\nSELECT 1\n```\n\n"
            "<script>alert(1)</script>\n"
            ":::"
        )
        script = f"""
const renderer = require('./web/chat-markdown.js');
const html = renderer.renderAssistantMarkdown({json.dumps(markdown, ensure_ascii=False)});
process.stdout.write(html);
"""

        result = subprocess.run(
            ["node", "-e", script],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=True,
        )

        self.assertIn('<details class="message-details">', result.stdout)
        self.assertIn(
            "<summary>查看技术详情（供信息科和实施人员）</summary>",
            result.stdout,
        )
        self.assertNotIn('<details class="message-details" open', result.stdout)
        self.assertIn('<table class="message-table">', result.stdout)
        self.assertIn('<pre class="message-code"><code class="language-sql">', result.stdout)
        self.assertNotIn("<script>", result.stdout)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", result.stdout)

    def test_renderer_only_builds_strict_indicator_detail_buttons(self) -> None:
        markdown = (
            "{{detail:RUN_80:denominator}}\n\n"
            "{{detail:<script>:numerator}}\n\n"
            "{{detail:RUN_80:result}}"
        )
        script = f"""
const renderer = require('./web/chat-markdown.js');
process.stdout.write(renderer.renderAssistantMarkdown({json.dumps(markdown)}));
"""

        result = subprocess.run(
            ["node", "-e", script],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=True,
        )

        self.assertIn('class="indicator-detail-trigger"', result.stdout)
        self.assertIn('data-run-id="RUN_80"', result.stdout)
        self.assertIn('data-detail-group="denominator"', result.stdout)
        self.assertEqual(result.stdout.count("indicator-detail-trigger"), 1)
        self.assertNotIn("<script>", result.stdout)


if __name__ == "__main__":
    unittest.main()
