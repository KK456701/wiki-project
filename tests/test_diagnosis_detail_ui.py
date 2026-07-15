from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_page_loads_diagnosis_detail_assets_before_inline_application():
    html = (ROOT / "web/index.html").read_text(encoding="utf-8")

    stylesheet = '<link rel="stylesheet" href="/static/diagnosis-details.css" />'
    script = '<script src="/static/diagnosis-details.js"></script>'
    assert stylesheet in html
    assert script in html
    assert html.index(script) < html.index("<script>")


def test_diagnosis_detail_ui_has_aggregate_summary_difference_tabs_and_paging():
    javascript = (ROOT / "web/diagnosis-details.js").read_text(encoding="utf-8")
    css = (ROOT / "web/diagnosis-details.css").read_text(encoding="utf-8")

    for text in (
        "用户 SQL",
        "当前生效 SQL",
        "全部差异",
        "仅用户 SQL 纳入",
        "仅当前口径纳入",
        "用户 SQL 计入分子",
        "当前口径计入分子",
        "差异原因",
        "hospitalAuthToken",
        "/api/diagnosis-comparisons/",
        "24小时后自动清理",
    ):
        assert text in javascript
    assert ".diagnosis-detail-overlay" in css
    assert ".diagnosis-summary-grid" in css
    assert "@media (max-width: 760px)" in css


def test_diagnosis_detail_rows_use_text_content_instead_of_inner_html():
    javascript = (ROOT / "web/diagnosis-details.js").read_text(encoding="utf-8")

    assert "cell.textContent" in javascript
    assert "row.innerHTML" not in javascript
