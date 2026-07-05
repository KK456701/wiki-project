from __future__ import annotations

import argparse
import json
import re
import shutil
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KB_ROOT = ROOT / "core-rules-wiki"
SPEC_PATH = Path(r"C:\Users\lenovo\Downloads\Markdown_Wiki_规则知识库建库规范_v1.1_含文档相关性.md")
SOURCE_PATH = Path(r"F:\VTE-memory\medical_quality_core_system_indicators_2025_dify_cleaned.md")
RAW_REL_PATH = "raw/national/medical_quality_core_system_indicators_2025_dify_cleaned.md"
CREATED_AT = "2026-07-05"


CONCEPT_RULES = [
    ("危急值管理制度", ["危急值"]),
    ("急危重症抢救制度", ["急危重症", "抢救"]),
    ("临床用血管理制度", ["用血", "输血", "自体血"]),
    ("新技术新项目管理制度", ["新技术", "新项目"]),
    ("医嘱管理制度", ["医嘱"]),
    ("会诊制度", ["会诊"]),
    ("疑难病例讨论制度", ["疑难病例", "高额异常费用"]),
    ("死亡病例讨论制度", ["死亡病例", "死亡患者"]),
    ("手术分级管理制度", ["手术", "术前", "术者", "麻醉", "并发症", "床旁交接班"]),
    ("三级查房制度", ["查房", "入院", "转科"]),
]


REQUIRED_ROOT_FILES = [
    "README.md",
    "schema.md",
    "index.md",
    "agent_contract.md",
    "log.md",
]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def write_json(path: Path, data: object) -> None:
    write_text(path, json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def safe_name(name: str) -> str:
    name = re.sub(r"\s+", "", name.strip())
    for old, new in {
        "/": "／",
        "\\": "／",
        ":": "：",
        "*": "＊",
        "?": "？",
        '"': "＂",
        "<": "＜",
        ">": "＞",
        "|": "｜",
    }.items():
        name = name.replace(old, new)
    return name


def yaml_list(items: list[str], indent: int = 2) -> str:
    if not items:
        return " []"
    prefix = " " * indent
    return "\n" + "\n".join(f"{prefix}- {item}" for item in items)


def extract_between(block: str, start_label: str, stop_labels: list[str]) -> str:
    pattern = re.compile(rf"{re.escape(start_label)}\s*(.*?)(?=" + "|".join(map(re.escape, stop_labels)) + r"|\Z)", re.S)
    match = pattern.search(block)
    if not match:
        return ""
    return match.group(1).strip()


def parse_indicators(source: str) -> list[dict[str, object]]:
    heading_pattern = re.compile(r"^## 指标(?P<ordinal>[^、]+)、(?P<title>.+?)\s*$", re.M)
    matches = list(heading_pattern.finditer(source))
    indicators: list[dict[str, object]] = []
    for index, match in enumerate(matches, start=1):
        start = match.end()
        end = matches[index].start() if index < len(matches) else len(source)
        block = source[start:end].strip()
        title = re.sub(r"\s+", " ", match.group("title").strip())
        definition = extract_between(block, "定义：", ["计算公式：", "### 计算公式：", "说明：", "意义：", "---"])
        formula = extract_between(block, "计算公式：", ["说明：", "意义：", "---"])
        if not formula:
            formula = extract_between(block, "### 计算公式：", ["说明：", "意义：", "---"])
        note = extract_between(block, "说明：", ["意义：", "---"])
        meaning = extract_between(block, "意义：", ["---"])
        rule_id = f"MQSI2025_{index:03d}"
        concept = infer_concept(title, block)
        indicators.append(
            {
                "index": index,
                "ordinal": match.group("ordinal").strip(),
                "rule_id": rule_id,
                "title": title,
                "safe_title": safe_name(title),
                "definition": definition or "原文未明确",
                "formula": normalize_formula(formula) or "原文未明确",
                "note": normalize_paragraph(note) or "原文未明确",
                "meaning": normalize_paragraph(meaning) or "原文未明确",
                "source_excerpt": block,
                "concept": concept,
            }
        )
    return indicators


def normalize_formula(text: str) -> str:
    text = text.strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def normalize_paragraph(text: str) -> str:
    text = text.strip()
    text = re.sub(r"\n{2,}", "\n\n", text)
    return text


def infer_concept(title: str, body: str) -> str:
    # Titles carry the strongest business intent; body text is a fallback only.
    for concept, keywords in CONCEPT_RULES:
        if any(keyword in title for keyword in keywords):
            return concept
    for concept, keywords in CONCEPT_RULES:
        if any(keyword in body for keyword in keywords):
            return concept
    return "医疗质量核心制度指标"


def national_path(ind: dict[str, object]) -> str:
    return f"wiki/standards/national/{ind['rule_id']}_{ind['safe_title']}.md"


def company_path(ind: dict[str, object]) -> str:
    return f"wiki/standards/company/{ind['rule_id']}_{ind['safe_title']}_公司标准.md"


def concept_path(concept: str) -> str:
    return f"wiki/concepts/{safe_name(concept)}.md"


def same_theme_rules(ind: dict[str, object], indicators: list[dict[str, object]]) -> list[dict[str, str]]:
    peers = []
    for peer in indicators:
        if peer["rule_id"] == ind["rule_id"]:
            continue
        if peer["concept"] == ind["concept"]:
            peers.append({"rule_id": str(peer["rule_id"]), "rule_name": str(peer["title"])})
    return peers


def frontmatter_common(ind: dict[str, object], level: str, peers: list[dict[str, str]]) -> str:
    peer_ids = [p["rule_id"] for p in peers]
    if level == "national":
        extra = f"""level: national
source_path: {RAW_REL_PATH}
source_page: 原文未分页
"""
        related = f"""  concepts:{yaml_list([str(ind["concept"])], 4)}
  same_theme_rules:{yaml_list(peer_ids, 4)}
  depends_on_fields: []
  uses_tables: []
  source_from:{yaml_list([RAW_REL_PATH], 4)}
"""
    else:
        extra = f"""level: company
base_national_rule: {national_path(ind)}
implementation_status: pending_field_mapping
"""
        related = f"""  base_national_rule:{yaml_list([str(ind["rule_id"])], 4)}
  concepts:{yaml_list([str(ind["concept"])], 4)}
  field_roles: []
  uses_tables: []
"""
    return f"""---
type: standard_rule
{extra}rule_id: {ind['rule_id']}
rule_name: {ind['title']}
aliases:
  - {ind['title']}
category: {ind['concept']}
version: "2025"
status: active
stat_cycle: 原文未明确
stat_dimensions: []
created_at: {CREATED_AT}
updated_at: {CREATED_AT}
related:
{related}---
"""


def national_page(ind: dict[str, object], indicators: list[dict[str, object]]) -> str:
    peers = same_theme_rules(ind, indicators)
    peer_links = "\n".join(f"- [[{p['rule_id']}_{p['rule_name']}]]" for p in peers) or "- 原文未明确"
    return f"""{frontmatter_common(ind, "national", peers)}
# {ind['title']}

## 规则定位

- 规则类型：国标标准规则
- 所属制度：[[{ind['concept']}]]
- 规则编号：{ind['rule_id']}
- 当前状态：active
- 公司标准：[[{ind['rule_id']}_{ind['title']}_公司标准]]

## 指标定义

{ind['definition']}

## 统计周期

原文未明确。

## 统计维度

原文未明确。

## 分子规则

### 分子业务口径

{derive_numerator(ind)}

### 分子判断逻辑

原文未明确。需结合医院字段映射和业务系统事件时间确认。

### 分子依赖字段

| 字段角色 | 标准表 | 标准字段 | 字段含义 | 是否必需 |
|---|---|---|---|---|
| 分子计数对象 | 原文未明确 | 原文未明确 | 待医院字段映射确认 | 是 |

## 分母规则

### 分母业务口径

{derive_denominator(ind)}

### 分母判断逻辑

原文未明确。需结合医院字段映射和统计周期确认。

### 分母依赖字段

| 字段角色 | 标准表 | 标准字段 | 字段含义 | 是否必需 |
|---|---|---|---|---|
| 分母计数对象 | 原文未明确 | 原文未明确 | 待医院字段映射确认 | 是 |

## 通用筛选条件

原文未明确。

## 排除条件

{derive_exclusion(ind)}

## 计算公式

{ind['formula']}

## 标准 SQL 逻辑

原文未明确。不得在未确认字段、表和时间口径前生成可执行 SQL。

## 校验阈值

| 阈值类型 | 阈值 | 说明 |
|---|---|---|
| 分母为零 | 需要提示 | 统计周期内无符合分母条件的记录时不应直接计算比例 |
| 字段缺失 | 需要排查 | 源文档未给出字段名，需医院字段映射确认 |

## 业务释义

{ind['meaning']}

## 依赖资源清单

| 资源类型 | 名称 | 用途 |
|---|---|---|
| 原始规则文档 | [[medical_quality_core_system_indicators_2025_dify_cleaned]] | 指标定义、公式、说明、意义来源 |
| 字段角色映射 | [[核心制度指标字段角色映射_待确认]] | 后续补充字段、表、SQL 口径 |

## 关联知识

- [[{ind['concept']}]]
{peer_links}

## 原始来源

- 来源文件：{RAW_REL_PATH}
- 来源章节：指标{ind['ordinal']}、{ind['title']}
"""


def company_page(ind: dict[str, object], indicators: list[dict[str, object]]) -> str:
    peers = same_theme_rules(ind, indicators)
    return f"""{frontmatter_common(ind, "company", peers)}
# {ind['title']}_公司标准

## 继承国标

[[{ind['rule_id']}_{ind['title']}]]

## 公司实现口径

公司标准 v1.0 暂按国标原文定义和公式继承，不新增原文未提供的表、字段或 SQL。

## 公司推荐字段

| 字段角色 | 推荐表 | 推荐字段 | 字段说明 |
|---|---|---|---|
| 分子计数对象 | 原文未明确 | 原文未明确 | 待医院字段映射确认 |
| 分母计数对象 | 原文未明确 | 原文未明确 | 待医院字段映射确认 |
| 统计周期字段 | 原文未明确 | 原文未明确 | 待医院字段映射确认 |

## 公司标准分子规则

### 业务口径

{derive_numerator(ind)}

### 实现逻辑

原文未明确。进入 [[CR_20260705_001_字段映射与SQL实现待审核]] 后续确认。

## 公司标准分母规则

{derive_denominator(ind)}

## 公司通用筛选条件

原文未明确。

## 公司排除条件

{derive_exclusion(ind)}

## 公司标准 SQL

原文未明确。待医院字段映射确认后，经 review/pending 提交变更申请。

## 与国标差异

| 差异项 | 国标 | 公司标准 |
|---|---|---|
| 指标定义 | 采用原文 | 继承国标 |
| 字段实现 | 原文未明确 | 原文未明确，待医院字段映射确认 |
| SQL | 原文未明确 | 不生成无来源 SQL |

## 适配说明

医院如需落地该指标，应先在 [[核心制度指标字段角色映射_待确认]] 中确认分子、分母、统计周期和排除条件对应的实际表字段，再通过 review 流程形成医院个性化口径。

## 关联知识

- 国标规则：[[{ind['rule_id']}_{ind['title']}]]
- 所属制度：[[{ind['concept']}]]
- 待审核事项：[[CR_20260705_001_字段映射与SQL实现待审核]]
"""


def derive_numerator(ind: dict[str, object]) -> str:
    formula = str(ind["formula"])
    match = re.search(r"=\s*\((.+?)\s*/", formula, re.S)
    if match:
        return match.group(1).strip()
    return "原文未明确。可从计算公式左侧分子含义进一步人工确认。"


def derive_denominator(ind: dict[str, object]) -> str:
    formula = str(ind["formula"])
    match = re.search(r"/\s*(.+?)(?:\)|×|x|X|$)", formula, re.S)
    if match:
        return match.group(1).strip()
    if "：" in formula and "/" not in formula:
        return "该指标为比值或中位数类指标，分母口径需人工确认。"
    return "原文未明确。可从计算公式右侧分母含义进一步人工确认。"


def derive_exclusion(ind: dict[str, object]) -> str:
    note = str(ind["note"])
    if note != "原文未明确" and ("不包括" in note or "除" in note or "只统计" in note or "限" in note):
        return note
    return "原文未明确。"


def root_readme(indicators: list[dict[str, object]]) -> str:
    return f"""# 医务核心制度指标 Markdown Wiki 知识库

本知识库根据《Markdown Wiki 规则知识库建库规范》和 `medical_quality_core_system_indicators_2025_dify_cleaned.md` 初始生成，用于 Agent 检索医务核心制度指标规则。

## 本次建库范围

- 指标数量：{len(indicators)}
- 国标规则页：{len(indicators)}
- 公司标准规则页：{len(indicators)}
- 原始资料：`{RAW_REL_PATH}`
- 字段、表、SQL：原文未明确，已进入 `review/pending/CR_20260705_001_字段映射与SQL实现待审核.md`

## 使用入口

- [[index]]
- [[schema]]
- [[agent_contract]]
- `indexes/rule_index.json`
- `indexes/relation_index.json`
- `indexes/search_index.json`

## 更新原则

正式知识位于 `wiki/`。任何新增字段映射、SQL、医院个性化口径或规则差异都应先进入 `review/pending/`，审核后再写入 `wiki/` 并重建 `indexes/`。
"""


def schema_doc() -> str:
    return """# 知识库 Schema

## 页面类型

| type | 用途 |
|---|---|
| `standard_rule` | 国标规则页或公司标准规则页 |
| `hospital_override` | 医院个性化口径页 |
| `concept` | 制度主题或公共概念页 |
| `table_metadata` | 表结构元数据页 |
| `field_role_mapping` | 字段角色映射页 |
| `change_request` | 待审核变更申请页 |

## 必填关系

正式 Markdown 页必须同时维护：

- frontmatter `related`
- 正文 `[[WikiLink]]`
- `indexes/relation_index.json`
- `indexes/search_index.json`

## 缺失信息处理

当原文没有明确字段、表名或 SQL 时，必须写 `原文未明确` 或 `待医院字段映射确认`，不得编造实现细节。
"""


def index_doc(indicators: list[dict[str, object]]) -> str:
    by_concept = defaultdict(list)
    for ind in indicators:
        by_concept[str(ind["concept"])].append(ind)
    sections = []
    for concept in sorted(by_concept):
        rows = "\n".join(
            f"- [[{ind['rule_id']}_{ind['title']}]] / [[{ind['rule_id']}_{ind['title']}_公司标准]]"
            for ind in by_concept[concept]
        )
        sections.append(f"## [[{concept}]]\n\n{rows}")
    return "# 知识库首页\n\n" + "\n\n".join(sections) + "\n"


def agent_contract() -> str:
    return """# Agent 接入协议

## 检索优先级

1. 通过 `indexes/rule_index.json` 定位 `rule_id`。
2. 通过 `indexes/hospital_override_index.json` 判断是否存在医院个性化口径。
3. 通过 `indexes/relation_index.json` 扩展国标、公司标准、概念、字段角色、表结构关系。
4. 通过 `indexes/search_index.json` 获取章节切片。
5. 必要时读取对应 Markdown 原文。

## 建议工具函数

### `kb_search(query, filters)`

从 `search_index.json` 检索切片，返回命中的 `rule_id`、路径、章节和相关关系。

### `kb_get_rule(rule_id, level="company")`

读取国标或公司标准规则页。若 `level=company` 且公司页存在，优先返回公司页，同时提供国标路径。

### `kb_get_hospital_override(rule_id, hospital_id)`

查询 `hospital_override_index.json`。当前初始库尚未配置医院个性化口径。

### `kb_get_field_mapping(rule_id)`

查询 `field_index.json` 和字段角色页。当前初始库标记为 `待医院字段映射确认`。

### `kb_submit_change_request(payload)`

将新增字段、SQL、医院口径或规则差异写入 `review/pending/`，人工审核通过后再更新 `wiki/` 并重建索引。

## 回答约束

当字段、表名或 SQL 在知识库中为 `原文未明确` 时，Agent 不能生成可执行 SQL，只能说明需要字段映射确认。
"""


def log_doc(indicators: list[dict[str, object]]) -> str:
    return f"""# 变更日志

## {CREATED_AT} 初始建库

- 输入规范：`{SPEC_PATH}`
- 输入原始规则：`{SOURCE_PATH}`
- 生成指标数量：{len(indicators)}
- 生成国标规则页：{len(indicators)}
- 生成公司标准规则页：{len(indicators)}
- 生成医院个性化口径：0
- 待审核问题：1 个汇总变更申请，覆盖字段映射与 SQL 实现确认。
- 说明：原文未提供表名、字段名和 SQL，本次未编造实现细节。
"""


def concept_page(concept: str, rules: list[dict[str, object]]) -> str:
    rule_ids = [str(rule["rule_id"]) for rule in rules]
    links = "\n".join(f"- [[{rule['rule_id']}_{rule['title']}]]" for rule in rules)
    return f"""---
type: concept
concept_name: {concept}
created_at: {CREATED_AT}
updated_at: {CREATED_AT}
related:
  rules:{yaml_list(rule_ids, 4)}
---
# {concept}

## 主题说明

本页汇总属于 [[{concept}]] 的核心制度指标。

## 相关指标

{links}
"""


def field_role_page(indicators: list[dict[str, object]]) -> str:
    rows = "\n".join(
        f"| {ind['rule_id']} | {ind['title']} | 分子计数对象、分母计数对象、统计周期字段 | 原文未明确 | 待医院字段映射确认 |"
        for ind in indicators
    )
    return f"""---
type: field_role_mapping
mapping_name: 核心制度指标字段角色映射_待确认
status: pending_review
created_at: {CREATED_AT}
updated_at: {CREATED_AT}
related:
  used_by_rules:{yaml_list([str(ind["rule_id"]) for ind in indicators], 4)}
---
# 核心制度指标字段角色映射_待确认

## 说明

原始指标文档未给出数据库表名、字段名或 SQL。本页仅记录待确认字段角色，不把推断字段写入正式规则。

## 待确认字段角色

| rule_id | 指标名称 | 字段角色 | 原文字段 | 当前状态 |
|---|---|---|---|---|
{rows}
"""


def table_metadata_page(indicators: list[dict[str, object]]) -> str:
    return f"""---
type: table_metadata
table_name: 原文未明确
business_entity: 核心制度指标待确认业务表
status: pending_review
created_at: {CREATED_AT}
updated_at: {CREATED_AT}
related:
  used_by_rules:{yaml_list([str(ind["rule_id"]) for ind in indicators], 4)}
  field_roles:
    - 核心制度指标字段角色映射_待确认
---
# 核心制度指标表结构_待确认

## 说明

原始文档只提供指标定义、公式、说明和意义，未提供数据库表结构。

## 当前表结构状态

| 表名 | 字段 | 状态 |
|---|---|---|
| 原文未明确 | 原文未明确 | 待医院字段映射确认 |
"""


def pending_change_request(indicators: list[dict[str, object]]) -> str:
    rows = "\n".join(
        f"| {ind['rule_id']} | {ind['title']} | 表名、字段名、统计周期字段、SQL | 待医院字段映射确认 |"
        for ind in indicators
    )
    return f"""---
type: change_request
change_id: CR_20260705_001
status: pending
created_at: {CREATED_AT}
updated_at: {CREATED_AT}
related:
  affects_rules:{yaml_list([str(ind["rule_id"]) for ind in indicators], 4)}
---
# CR_20260705_001_字段映射与SQL实现待审核

## 变更来源

初始建库质量检查发现，原始指标文档未提供数据库表名、字段名和 SQL。

## 待审核范围

| rule_id | 指标名称 | 待确认内容 | 当前处理 |
|---|---|---|---|
{rows}

## 审核要求

补充字段映射和 SQL 前，应由实施人员或医院信息科确认实际业务表、字段、状态值、时间口径、统计周期和排除条件。审核通过后再写入公司标准页、医院个性化口径页或元数据页，并重建索引。
"""


def build_indexes(indicators: list[dict[str, object]]) -> tuple[dict[str, object], dict[str, object], dict[str, object], dict[str, object], list[dict[str, object]]]:
    rule_index = {"generated_at": CREATED_AT, "rules": []}
    field_index = {
        "generated_at": CREATED_AT,
        "status": "pending_field_mapping",
        "field_roles": [],
    }
    relation_index: dict[str, object] = {}
    search_index: list[dict[str, object]] = []
    hospital_override_index = {"generated_at": CREATED_AT, "hospital_overrides": [], "note": "初始库未配置医院个性化口径"}

    for ind in indicators:
        peers = same_theme_rules(ind, indicators)
        n_path = national_path(ind)
        c_path = company_path(ind)
        rule_index["rules"].append(
            {
                "rule_id": ind["rule_id"],
                "rule_name": ind["title"],
                "aliases": [ind["title"]],
                "category": ind["concept"],
                "national_path": n_path,
                "company_path": c_path,
                "status": "active",
                "source_path": RAW_REL_PATH,
            }
        )
        field_index["field_roles"].append(
            {
                "rule_id": ind["rule_id"],
                "rule_name": ind["title"],
                "roles": ["分子计数对象", "分母计数对象", "统计周期字段"],
                "standard_fields": [],
                "status": "待医院字段映射确认",
                "field_role_path": "wiki/metadata/field_roles/核心制度指标字段角色映射_待确认.md",
            }
        )
        relation_index[str(ind["rule_id"])] = {
            "rule_name": ind["title"],
            "category": ind["concept"],
            "national_rule_path": n_path,
            "company_rule_path": c_path,
            "relations": {
                "belongs_to": [
                    {
                        "target_type": "concept",
                        "target_name": ind["concept"],
                        "target_path": concept_path(str(ind["concept"])),
                    }
                ],
                "same_theme": [
                    {"target_rule_id": peer["rule_id"], "target_name": peer["rule_name"]}
                    for peer in peers
                ],
                "base_national_rule": [
                    {
                        "target_rule_id": ind["rule_id"],
                        "target_path": n_path,
                    }
                ],
                "depends_on_fields": [],
                "uses_tables": [],
                "has_hospital_override": [],
                "source_from": [
                    {
                        "source_path": RAW_REL_PATH,
                        "source_section": f"指标{ind['ordinal']}、{ind['title']}",
                    }
                ],
                "change_from": [
                    {
                        "change_id": "CR_20260705_001",
                        "path": "review/pending/CR_20260705_001_字段映射与SQL实现待审核.md",
                        "status": "pending",
                    }
                ],
            },
        }
        related_rule_ids = [peer["rule_id"] for peer in peers]
        for level, path, section, content in [
            ("national", n_path, "指标定义", str(ind["definition"])),
            ("national", n_path, "计算公式", str(ind["formula"])),
            ("company", c_path, "公司实现口径", "公司标准 v1.0 暂按国标原文定义和公式继承，字段、表、SQL 待确认。"),
        ]:
            search_index.append(
                {
                    "chunk_id": f"{ind['rule_id']}_{level}_{safe_name(section)}",
                    "rule_id": ind["rule_id"],
                    "title": f"{ind['title']}_{section}",
                    "path": path,
                    "type": "standard_rule",
                    "level": level,
                    "section": section,
                    "keywords": [ind["rule_id"], ind["title"], ind["concept"], section],
                    "related_rule_ids": related_rule_ids,
                    "related_fields": [],
                    "related_tables": [],
                    "content": content,
                }
            )

    return rule_index, hospital_override_index, field_index, relation_index, search_index


def create_directories() -> None:
    dirs = [
        "raw/national",
        "raw/company",
        "raw/hospitals/hospital_001",
        "wiki/standards/national",
        "wiki/standards/company",
        "wiki/hospitals/hospital_001/overrides",
        "wiki/concepts",
        "wiki/metadata/tables",
        "wiki/metadata/field_roles",
        "review/incoming",
        "review/pending",
        "review/approved",
        "review/rejected",
        "indexes",
    ]
    for rel in dirs:
        (KB_ROOT / rel).mkdir(parents=True, exist_ok=True)


def build() -> None:
    if not SOURCE_PATH.exists():
        raise FileNotFoundError(SOURCE_PATH)
    if not SPEC_PATH.exists():
        raise FileNotFoundError(SPEC_PATH)
    create_directories()
    source = read_text(SOURCE_PATH)
    indicators = parse_indicators(source)
    if len(indicators) != 35:
        raise RuntimeError(f"Expected 35 indicators, parsed {len(indicators)}")

    shutil.copyfile(SOURCE_PATH, KB_ROOT / RAW_REL_PATH)

    write_text(KB_ROOT / "README.md", root_readme(indicators))
    write_text(KB_ROOT / "schema.md", schema_doc())
    write_text(KB_ROOT / "index.md", index_doc(indicators))
    write_text(KB_ROOT / "agent_contract.md", agent_contract())
    write_text(KB_ROOT / "log.md", log_doc(indicators))

    by_concept = defaultdict(list)
    for ind in indicators:
        by_concept[str(ind["concept"])].append(ind)
        write_text(KB_ROOT / national_path(ind), national_page(ind, indicators))
        write_text(KB_ROOT / company_path(ind), company_page(ind, indicators))

    for concept, rules in by_concept.items():
        write_text(KB_ROOT / concept_path(concept), concept_page(concept, rules))

    write_text(KB_ROOT / "wiki/metadata/field_roles/核心制度指标字段角色映射_待确认.md", field_role_page(indicators))
    write_text(KB_ROOT / "wiki/metadata/tables/核心制度指标表结构_待确认.md", table_metadata_page(indicators))
    write_text(KB_ROOT / "review/pending/CR_20260705_001_字段映射与SQL实现待审核.md", pending_change_request(indicators))

    rule_index, hospital_override_index, field_index, relation_index, search_index = build_indexes(indicators)
    write_json(KB_ROOT / "indexes/rule_index.json", rule_index)
    write_json(KB_ROOT / "indexes/hospital_override_index.json", hospital_override_index)
    write_json(KB_ROOT / "indexes/field_index.json", field_index)
    write_json(KB_ROOT / "indexes/relation_index.json", relation_index)
    write_json(KB_ROOT / "indexes/search_index.json", search_index)

    print(f"Generated {len(indicators)} indicators at {KB_ROOT}")


def verify() -> None:
    source = read_text(SOURCE_PATH)
    indicators = parse_indicators(source)
    errors: list[str] = []
    for rel in REQUIRED_ROOT_FILES:
        if not (KB_ROOT / rel).exists():
            errors.append(f"Missing root file: {rel}")

    national_files = list((KB_ROOT / "wiki/standards/national").glob("MQSI2025_*.md"))
    company_files = list((KB_ROOT / "wiki/standards/company").glob("MQSI2025_*.md"))
    if len(national_files) != 35:
        errors.append(f"Expected 35 national pages, found {len(national_files)}")
    if len(company_files) != 35:
        errors.append(f"Expected 35 company pages, found {len(company_files)}")

    for rel in [
        "indexes/rule_index.json",
        "indexes/hospital_override_index.json",
        "indexes/field_index.json",
        "indexes/relation_index.json",
        "indexes/search_index.json",
    ]:
        path = KB_ROOT / rel
        if not path.exists():
            errors.append(f"Missing JSON index: {rel}")
            continue
        try:
            json.loads(read_text(path))
        except json.JSONDecodeError as exc:
            errors.append(f"Invalid JSON {rel}: {exc}")

    for ind in indicators:
        n_path = KB_ROOT / national_path(ind)
        c_path = KB_ROOT / company_path(ind)
        for path in [n_path, c_path]:
            if not path.exists():
                errors.append(f"Missing page: {path}")
                continue
            text = read_text(path)
            if "related:" not in text:
                errors.append(f"Missing related frontmatter: {path}")
            if "[[" not in text:
                errors.append(f"Missing WikiLink: {path}")

    rule_index = json.loads(read_text(KB_ROOT / "indexes/rule_index.json"))
    relation_index = json.loads(read_text(KB_ROOT / "indexes/relation_index.json"))
    search_index = json.loads(read_text(KB_ROOT / "indexes/search_index.json"))
    if len(rule_index.get("rules", [])) != 35:
        errors.append(f"rule_index should contain 35 rules, found {len(rule_index.get('rules', []))}")
    if len(relation_index) != 35:
        errors.append(f"relation_index should contain 35 keys, found {len(relation_index)}")
    if len(search_index) < 105:
        errors.append(f"search_index should contain at least 105 chunks, found {len(search_index)}")

    if errors:
        raise RuntimeError("\n".join(errors))
    print("Verification passed: 35 national pages, 35 company pages, 5 valid JSON indexes, required related fields and WikiLinks present.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true", help="verify generated wiki")
    args = parser.parse_args()
    if args.verify:
        verify()
    else:
        build()


if __name__ == "__main__":
    main()

