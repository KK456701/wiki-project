"""将术语快照生成可读 Wiki 页面和只读索引。"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from app.terminology.contracts import TermCorpus


def write_terminology_wiki(corpus: TermCorpus, kb_root: str | Path) -> dict[str, int]:
    root = Path(kb_root)
    page_root = root / "wiki" / "terminology"
    index_root = root / "indexes"
    page_root.mkdir(parents=True, exist_ok=True)
    index_root.mkdir(parents=True, exist_ok=True)
    index: list[dict[str, Any]] = []
    for concept in corpus.concepts:
        aliases = [alias.model_dump(exclude={"concept_code"}) for alias in concept.aliases]
        links = []
        for rule_id, coverage in corpus.rule_coverage.items():
            if any(link.concept_code == concept.concept_code for link in coverage.concept_links):
                links.append(rule_id)
        page = _render_page(concept.model_dump(exclude={"aliases"}), aliases, links)
        filename = f"{concept.concept_code}_{_safe_filename(concept.canonical_name)}.md"
        (page_root / filename).write_text(page, encoding="utf-8")
        index.append(
            {
                "concept_code": concept.concept_code,
                "canonical_name": concept.canonical_name,
                "concept_type": concept.concept_type,
                "definition": concept.definition,
                "aliases": aliases,
                "linked_rule_ids": links,
                "path": f"wiki/terminology/{filename}",
            }
        )
    (index_root / "term_index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return {"concept_count": len(index), "page_count": len(index)}


def _render_page(concept: dict[str, Any], aliases: list[dict[str, Any]], links: list[str]) -> str:
    alias_lines = "\n".join(
        f"- {item['alias_text']}：{item['relation_type']}；"
        f"检索={'是' if item['retrieval_enabled'] else '否'}；"
        f"SQL={'是' if item['sql_safe'] else '否'}"
        for item in aliases
    ) or "- 无"
    rule_lines = "\n".join(f"- {rule_id}" for rule_id in links) or "- 无"
    return (
        f"---\nconcept_code: {concept['concept_code']}\n"
        f"concept_type: {concept['concept_type']}\nstatus: {concept['status']}\n---\n"
        f"# {concept['canonical_name']}\n\n## 定义\n\n{concept['definition']}\n\n"
        f"## 同义词与相关表达\n\n{alias_lines}\n\n"
        f"## 关联指标\n\n{rule_lines}\n\n"
        f"## 来源\n\n{concept['source_reference']}\n"
    )


def _safe_filename(value: str) -> str:
    return re.sub(r"[\\/:*?\"<>|\s]+", "_", value).strip("_")[:80]
