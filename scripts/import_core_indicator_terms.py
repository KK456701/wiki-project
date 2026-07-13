"""预览或导入 35 个核心制度指标医学术语。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.terminology.importer import (
    import_term_corpus,
    load_term_corpus,
    validate_term_corpus,
)


CORPUS_PATH = ROOT / "core-rules-wiki" / "terminology" / "core_indicator_terms.yaml"
KB_ROOT = ROOT / "core-rules-wiki"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导入 35 个核心制度指标医学术语")
    parser.add_argument("--apply", action="store_true", help="写入 MySQL 并发布术语版本")
    parser.add_argument("--actor-id", default="terminology_import")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    corpus = load_term_corpus(CORPUS_PATH)
    coverage = validate_term_corpus(corpus)
    preview = coverage.model_dump()
    if not coverage.ok:
        print(json.dumps(preview, ensure_ascii=False, indent=2))
        return 2
    if not args.apply:
        print("术语语料校验通过，当前为预览模式，尚未写入数据库。")
        print(json.dumps(preview, ensure_ascii=False, indent=2))
        return 0

    from app.db.engine import create_runtime_engine
    from app.terminology.release import TerminologyReleaseService
    from app.terminology.repository import TerminologyRepository
    from app.terminology.schema import ensure_terminology_schema
    from app.terminology.wiki_sync import write_terminology_wiki

    engine = create_runtime_engine()
    migration = ensure_terminology_schema(engine)
    imported = import_term_corpus(engine, corpus, args.actor_id)
    release = TerminologyReleaseService(TerminologyRepository(engine)).publish(args.actor_id)
    wiki = write_terminology_wiki(corpus, KB_ROOT)
    print("医学术语已写入 MySQL，发布版本并同步 Wiki。")
    print(
        json.dumps(
            {"migration": migration, "import": imported, "release": release, "wiki": wiki},
            ensure_ascii=False,
            indent=2,
            default=str,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
