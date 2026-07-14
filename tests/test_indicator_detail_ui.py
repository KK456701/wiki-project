from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_page_loads_indicator_detail_assets_before_inline_application() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

    stylesheet = '<link rel="stylesheet" href="/static/indicator-details.css" />'
    script = '<script src="/static/indicator-details.js"></script>'
    assert stylesheet in html
    assert script in html
    assert html.index(script) < html.index("<script>")
    assert 'id="logoutButton"' in html
    assert 'id="loginPassword2"' in html
    assert "暂不校验" not in html


def test_detail_ui_has_doctor_friendly_tabs_paging_and_export_confirmation() -> None:
    javascript = (ROOT / "web" / "indicator-details.js").read_text(encoding="utf-8")
    css = (ROOT / "web" / "indicator-details.css").read_text(encoding="utf-8")

    for text in (
        "统计范围",
        "达到要求",
        "未达到要求",
        "正在读取本次计算明细",
        "本组没有记录",
        "文件含完整患者级明细",
        "indicator_detail_export",
        "hospitalAuthToken",
        "/api/auth/hospital/change-password",
    ):
        assert text in javascript
    assert "detail-table-scroll" in css
    assert ".indicator-detail-empty[hidden]" in css
    assert "@media (max-width: 900px)" in css
    assert "@media (max-width: 760px)" in css
    assert "prefers-reduced-motion" in css


def test_detail_ui_never_uses_inner_html_for_patient_rows() -> None:
    javascript = (ROOT / "web" / "indicator-details.js").read_text(encoding="utf-8")

    assert "rowCell.textContent" in javascript
    assert "item.innerHTML" not in javascript
