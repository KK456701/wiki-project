"""术语发布快照和版本回退。"""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import text

from app.terminology.repository import TerminologyRepository


class TerminologyReleaseService:
    def __init__(self, repository: TerminologyRepository) -> None:
        self.repository = repository

    def publish(self, actor_id: str, change_summary: str = "医学术语发布") -> dict[str, Any]:
        if self.repository.list_aliases("pending"):
            raise ValueError("TERM_PENDING_REVIEW_EXISTS")
        snapshot = self.repository.snapshot()
        stable = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        checksum = hashlib.sha256(stable.encode("utf-8")).hexdigest()
        with self.repository.engine.begin() as conn:
            existing = conn.execute(
                text("SELECT * FROM med_term_release WHERE checksum=:checksum"),
                {"checksum": checksum},
            ).mappings().first()
            conn.execute(text("UPDATE med_term_release SET status='history' WHERE status='active'"))
            if existing is not None:
                conn.execute(
                    text("UPDATE med_term_release SET status='active' WHERE release_id=:release_id"),
                    {"release_id": existing["release_id"]},
                )
                return {
                    "release_id": existing["release_id"],
                    "active_release_id": existing["release_id"],
                    "version": int(existing["version"]),
                    "status": "active",
                    "checksum": existing["checksum"],
                    "reused": True,
                }
            version = int(
                conn.execute(text("SELECT COALESCE(MAX(version), 0) FROM med_term_release")).scalar_one()
            ) + 1
            release_id = f"TERM_{datetime.now():%Y%m%d}_{version:03d}_{uuid.uuid4().hex[:6]}"
            now = datetime.now()
            conn.execute(
                text(
                    """
                    INSERT INTO med_term_release
                      (release_id, version, status, checksum, snapshot_json,
                       change_summary, published_by, published_at)
                    VALUES (:release_id, :version, 'active', :checksum, :snapshot_json,
                            :change_summary, :published_by, :published_at)
                    """
                ),
                {
                    "release_id": release_id,
                    "version": version,
                    "checksum": checksum,
                    "snapshot_json": json.dumps(snapshot, ensure_ascii=False),
                    "change_summary": change_summary,
                    "published_by": actor_id,
                    "published_at": now,
                },
            )
        return {
            "release_id": release_id,
            "active_release_id": release_id,
            "version": version,
            "status": "active",
            "checksum": checksum,
        }

    def restore(self, release_id: str, actor_id: str) -> dict[str, Any]:
        release = self.repository.get_release(release_id)
        if release is None:
            raise LookupError("TERM_RELEASE_NOT_FOUND")
        self.repository.replace_projection(release["snapshot_json"])
        with self.repository.engine.begin() as conn:
            conn.execute(text("UPDATE med_term_release SET status='history' WHERE status='active'"))
            conn.execute(
                text("UPDATE med_term_release SET status='active' WHERE release_id=:release_id"),
                {"release_id": release_id},
            )
            self.repository._audit(
                conn, "restore", "term_release", release_id, actor_id,
                {"restored_version": release["version"]},
            )
        return {"active_release_id": release_id, "status": "active"}
