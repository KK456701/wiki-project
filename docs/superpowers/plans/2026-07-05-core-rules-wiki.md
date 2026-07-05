# Core Rules Wiki Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `core-rules-wiki/` from the supplied Markdown Wiki specification and the 2025 core system indicator source document.

**Architecture:** A single deterministic generator reads the source Markdown, parses each `## 指标...` section, writes normalized Markdown wiki pages, and rebuilds JSON indexes from the same parsed data. The generator never invents source-missing fields, tables, or SQL; it writes explicit pending-review records instead.

**Tech Stack:** Python 3 standard library, Markdown files, JSON indexes.

## Global Constraints

- Output root: `F:\A-wiki-project\core-rules-wiki`.
- Raw source must be copied into `raw/national/` without rewriting source content.
- Each of 35 source indicators must have one national rule page and one company standard page.
- Each formal wiki Markdown page must include frontmatter `related` data and body `[[WikiLink]]` references.
- Missing table, field, and SQL details must be recorded as `原文未明确` or `待医院字段映射确认`, not invented.
- Indexes required: `rule_index.json`, `hospital_override_index.json`, `field_index.json`, `relation_index.json`, `search_index.json`.

---

### Task 1: Generator

**Files:**
- Create: `F:\A-wiki-project\scripts\build_core_rules_wiki.py`
- Create by running: `F:\A-wiki-project\core-rules-wiki\**`

**Interfaces:**
- Consumes: `C:\Users\lenovo\Downloads\Markdown_Wiki_规则知识库建库规范_v1.1_含文档相关性.md`
- Consumes: `F:\VTE-memory\medical_quality_core_system_indicators_2025_dify_cleaned.md`
- Produces: Complete Markdown + JSON wiki at `F:\A-wiki-project\core-rules-wiki`

- [ ] **Step 1: Implement parser and generator**

Create a Python script that parses indicator sections by `^## 指标`, extracts definition/formula/description/meaning blocks, infers broad concept categories from source text keywords, and writes wiki pages and indexes.

- [ ] **Step 2: Run generator**

Run: `python scripts/build_core_rules_wiki.py`

Expected: script exits `0` and reports `Generated 35 indicators`.

- [ ] **Step 3: Verify file counts and JSON validity**

Run: `python scripts/build_core_rules_wiki.py --verify`

Expected: script exits `0`, confirms 35 national pages, 35 company pages, 5 valid JSON indexes, and no missing required root files.

