from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
KB_ROOT = ROOT / "core-rules-wiki"


def read_json(rel_path: str) -> Any:
    return json.loads((KB_ROOT / rel_path).read_text(encoding="utf-8"))


def read_text(rel_path: str) -> str:
    return (KB_ROOT / rel_path).read_text(encoding="utf-8")


def emit(data: Any) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2))


def normalize(text: str) -> str:
    return re.sub(r"\s+", "", text.lower())


def section(markdown: str, title: str) -> str:
    pattern = re.compile(rf"^## {re.escape(title)}\s*\n(?P<body>.*?)(?=^## |\Z)", re.M | re.S)
    match = pattern.search(markdown)
    return match.group("body").strip() if match else ""


def resolve_rule(query: str) -> dict[str, Any] | None:
    rules = read_json("indexes/rule_index.json")["rules"]
    q = normalize(query)
    for rule in rules:
        candidates = [rule["rule_id"], rule["rule_name"], *rule.get("aliases", [])]
        if any(normalize(str(candidate)) == q for candidate in candidates):
            return rule
    for rule in rules:
        candidates = [rule["rule_id"], rule["rule_name"], *rule.get("aliases", [])]
        if any(q in normalize(str(candidate)) or normalize(str(candidate)) in q for candidate in candidates):
            return rule
    return None


def kb_search(query: str, limit: int = 5) -> dict[str, Any]:
    rule = resolve_rule(query)
    chunks = read_json("indexes/search_index.json")
    q = normalize(query)
    scored = []
    for chunk in chunks:
        haystack = normalize(" ".join(str(v) for v in [
            chunk.get("rule_id", ""),
            chunk.get("title", ""),
            chunk.get("section", ""),
            " ".join(chunk.get("keywords", [])),
            chunk.get("content", ""),
        ]))
        score = 0
        if rule and chunk.get("rule_id") == rule["rule_id"]:
            score += 10
        if q and q in haystack:
            score += 5
        if score:
            scored.append((score, chunk))
    scored.sort(key=lambda item: item[0], reverse=True)
    return {
        "tool": "kb_search",
        "query": query,
        "resolved_rule_id": rule["rule_id"] if rule else None,
        "matches": [chunk for _, chunk in scored[:limit]],
    }


def kb_get_rule(rule_id: str, level: str = "company") -> dict[str, Any]:
    rule = resolve_rule(rule_id)
    if not rule:
        return {"tool": "kb_get_rule", "rule_id": rule_id, "error": "rule_not_found"}
    path = rule["company_path"] if level == "company" else rule["national_path"]
    markdown = read_text(path)
    national_markdown = read_text(rule["national_path"])
    company_markdown = read_text(rule["company_path"])
    return {
        "tool": "kb_get_rule",
        "rule_id": rule["rule_id"],
        "rule_name": rule["rule_name"],
        "level": level,
        "path": path,
        "national_path": rule["national_path"],
        "company_path": rule["company_path"],
        "definition": section(markdown, "指标定义") or section(national_markdown, "指标定义"),
        "formula": section(markdown, "计算公式") or section(national_markdown, "计算公式"),
        "implementation_status": section(company_markdown, "公司标准 SQL"),
        "field_status": "待医院字段映射确认" if "待医院字段映射确认" in company_markdown else "已配置",
        "markdown_excerpt": markdown[:1200],
    }


def kb_get_field_mapping(rule_id: str) -> dict[str, Any]:
    index = read_json("indexes/field_index.json")
    items = [item for item in index["field_roles"] if item["rule_id"] == rule_id]
    return {
        "tool": "kb_get_field_mapping",
        "rule_id": rule_id,
        "status": index["status"],
        "items": items,
    }


def demo_question(question: str, hospital_id: str | None = None) -> dict[str, Any]:
    search = kb_search(question, limit=3)
    rule_id = search["resolved_rule_id"]
    if not rule_id:
        return {
            "workflow": "demo_question",
            "question": question,
            "answer": "未命中规则。请换用指标名称、rule_id 或别名查询。",
            "trace": [search],
        }
    rule = kb_get_rule(rule_id, level="company")
    field_mapping = kb_get_field_mapping(rule_id)
    relation = read_json("indexes/relation_index.json").get(rule_id, {})
    answer_parts = [
        f"命中指标：{rule['rule_name']}（{rule_id}）。",
        f"公司标准页：{rule['path']}。",
    ]
    if rule.get("definition"):
        answer_parts.append(f"定义：{rule['definition']}")
    if rule.get("formula"):
        answer_parts.append(f"计算公式：{rule['formula']}")
    if rule.get("implementation_status"):
        answer_parts.append(f"实现状态：{rule['implementation_status']}")
    if field_mapping["status"] == "pending_field_mapping":
        answer_parts.append("字段和 SQL 状态：原文未明确，待医院字段映射确认；当前不能生成可执行 SQL。")
    if hospital_id:
        answer_parts.append(f"医院口径：{hospital_id} 当前未配置个性化 override。")
    return {
        "workflow": "demo_question",
        "question": question,
        "hospital_id": hospital_id,
        "answer": "\n".join(answer_parts),
        "trace": [
            {"step": "kb_search", "output": search},
            {"step": "kb_get_rule", "output": rule},
            {"step": "kb_get_field_mapping", "output": field_mapping},
            {"step": "relation_expand", "output": relation},
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Demo Agent tools for core-rules-wiki")
    sub = parser.add_subparsers(dest="cmd", required=True)

    search_cmd = sub.add_parser("kb_search")
    search_cmd.add_argument("--query", required=True)
    search_cmd.add_argument("--limit", type=int, default=5)

    rule_cmd = sub.add_parser("kb_get_rule")
    rule_cmd.add_argument("--rule-id", required=True)
    rule_cmd.add_argument("--level", choices=["national", "company"], default="company")

    field_cmd = sub.add_parser("kb_get_field_mapping")
    field_cmd.add_argument("--rule-id", required=True)

    demo_cmd = sub.add_parser("demo_question")
    demo_cmd.add_argument("--question", required=True)
    demo_cmd.add_argument("--hospital-id")

    args = parser.parse_args()
    if args.cmd == "kb_search":
        emit(kb_search(args.query, args.limit))
    elif args.cmd == "kb_get_rule":
        emit(kb_get_rule(args.rule_id, args.level))
    elif args.cmd == "kb_get_field_mapping":
        emit(kb_get_field_mapping(args.rule_id))
    elif args.cmd == "demo_question":
        emit(demo_question(args.question, args.hospital_id))


if __name__ == "__main__":
    main()


