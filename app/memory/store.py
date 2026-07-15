from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from app.memory.contracts import (
    ContextStorageError,
    ContextVersionConflict,
    ConversationContext,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MEMORY_ROOT = PROJECT_ROOT / "runtime" / "conversations"


class ConversationMemory:
    """SQLite + JSONL conversation memory for local agent sessions."""

    def __init__(self, root: str | Path = DEFAULT_MEMORY_ROOT) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.db_path = self.root / "conversations.sqlite3"
        self.events_path = self.root / "events.jsonl"
        self.sqlite_available = True
        try:
            self._init_db()
        except sqlite3.Error:
            self.sqlite_available = False

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path))
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA temp_store = MEMORY")
        return conn

    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    hospital_id TEXT
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id)
                )
                """
            )
            conn.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id, id)")
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS session_contexts (
                    session_id TEXT PRIMARY KEY,
                    context_version INTEGER NOT NULL,
                    context_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id)
                )
                """
            )

    def ensure_session(self, session_id: str | None = None, hospital_id: str | None = None) -> str:
        active_session_id = session_id or uuid.uuid4().hex
        now = self._now()
        if not self.sqlite_available:
            return active_session_id
        try:
            with self._connect() as conn:
                existing = conn.execute(
                    "SELECT session_id FROM sessions WHERE session_id = ?", (active_session_id,)
                ).fetchone()
                if existing:
                    conn.execute(
                        "UPDATE sessions SET updated_at = ?, hospital_id = COALESCE(?, hospital_id) WHERE session_id = ?",
                        (now, hospital_id, active_session_id),
                    )
                else:
                    conn.execute(
                        "INSERT INTO sessions(session_id, created_at, updated_at, hospital_id) VALUES (?, ?, ?, ?)",
                        (active_session_id, now, now, hospital_id),
                    )
        except sqlite3.Error:
            self.sqlite_available = False
        return active_session_id

    def append_message(
        self,
        session_id: str,
        role: str,
        content: str,
        metadata: dict[str, Any] | None = None,
    ) -> int | None:
        now = self._now()
        safe_metadata = metadata or {}
        metadata_json = json.dumps(safe_metadata, ensure_ascii=False, default=str)
        message_id: int | None = None
        if self.sqlite_available:
            try:
                with self._connect() as conn:
                    cursor = conn.execute(
                        """
                        INSERT INTO messages(session_id, role, content, created_at, metadata_json)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        (session_id, role, content, now, metadata_json),
                    )
                    message_id = int(cursor.lastrowid)
                    conn.execute("UPDATE sessions SET updated_at = ? WHERE session_id = ?", (now, session_id))
            except sqlite3.Error:
                self.sqlite_available = False
        event = {
            "session_id": session_id,
            "role": role,
            "content": content,
            "created_at": now,
            "metadata": safe_metadata,
        }
        with self.events_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")
        return message_id

    def _recent_messages_from_jsonl(self, session_id: str, limit: int) -> list[dict[str, Any]]:
        if not self.events_path.exists():
            return []
        matches: list[dict[str, Any]] = []
        with self.events_path.open("r", encoding="utf-8") as fh:
            for line_no, line in enumerate(fh, 1):
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if event.get("session_id") != session_id:
                    continue
                event["metadata"] = event.get("metadata") or {}
                event["metadata"]["jsonl_line"] = line_no
                matches.append(event)
        return matches[-limit:]

    def recent_messages(self, session_id: str, limit: int = 10) -> list[dict[str, Any]]:
        if not self.sqlite_available:
            return self._recent_messages_from_jsonl(session_id, limit)
        try:
            with self._connect() as conn:
                rows = conn.execute(
                    """
                    SELECT id, session_id, role, content, created_at, metadata_json
                    FROM messages
                    WHERE session_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                    """,
                    (session_id, limit),
                ).fetchall()
        except sqlite3.Error:
            self.sqlite_available = False
            return self._recent_messages_from_jsonl(session_id, limit)
        messages: list[dict[str, Any]] = []
        for row in reversed(rows):
            item = dict(row)
            try:
                item["metadata"] = json.loads(item.pop("metadata_json") or "{}")
            except json.JSONDecodeError:
                item["metadata"] = {}
            messages.append(item)
        return messages

    def load_context(self, session_id: str) -> ConversationContext:
        if not self.sqlite_available:
            raise ContextStorageError("结构化会话状态存储当前不可用")
        try:
            with self._connect() as conn:
                row = conn.execute(
                    """
                    SELECT context_version, context_json
                    FROM session_contexts
                    WHERE session_id = ?
                    """,
                    (session_id,),
                ).fetchone()
        except sqlite3.Error as exc:
            self.sqlite_available = False
            raise ContextStorageError("读取结构化会话状态失败") from exc
        if row is None:
            return ConversationContext()
        try:
            context = ConversationContext.model_validate_json(row["context_json"])
        except (ValidationError, ValueError) as exc:
            raise ContextStorageError("结构化会话状态内容已损坏") from exc
        context.context_version = int(row["context_version"])
        return context

    def save_context(
        self,
        session_id: str,
        context: ConversationContext,
        expected_version: int,
    ) -> ConversationContext:
        if not self.sqlite_available:
            raise ContextStorageError("结构化会话状态存储当前不可用")
        now = self._now()
        saved = context.model_copy(deep=True)
        try:
            with self._connect() as conn:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute(
                    "SELECT context_version FROM session_contexts WHERE session_id = ?",
                    (session_id,),
                ).fetchone()
                current_version = int(row["context_version"]) if row else 0
                if current_version != expected_version:
                    raise ContextVersionConflict(
                        f"会话状态版本已变化：期望 {expected_version}，当前 {current_version}"
                    )
                saved.context_version = current_version + 1
                payload = saved.model_dump_json()
                if row is None:
                    conn.execute(
                        """
                        INSERT INTO session_contexts(
                            session_id, context_version, context_json, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                        (session_id, saved.context_version, payload, now, now),
                    )
                else:
                    conn.execute(
                        """
                        UPDATE session_contexts
                        SET context_version = ?, context_json = ?, updated_at = ?
                        WHERE session_id = ?
                        """,
                        (saved.context_version, payload, now, session_id),
                    )
                conn.execute(
                    "UPDATE sessions SET updated_at = ? WHERE session_id = ?",
                    (now, session_id),
                )
        except ContextVersionConflict:
            raise
        except sqlite3.Error as exc:
            self.sqlite_available = False
            raise ContextStorageError("保存结构化会话状态失败") from exc
        return saved


    def _last_rule_context_from_jsonl(self, session_id: str) -> dict[str, Any] | None:
        for message in reversed(self._recent_messages_from_jsonl(session_id, 50)):
            if message.get("role") != "assistant":
                continue
            metadata = message.get("metadata") or {}
            rule_id = metadata.get("rule_id")
            if rule_id:
                context = {
                    "rule_id": rule_id,
                    "rule_name": metadata.get("rule_name"),
                    "source_message_id": metadata.get("jsonl_line"),
                }
                for key in ("stat_start_time", "stat_end_time"):
                    if metadata.get(key):
                        context[key] = metadata[key]
                return context
        return None

    def last_rule_context(self, session_id: str) -> dict[str, Any] | None:
        if not self.sqlite_available:
            return self._last_rule_context_from_jsonl(session_id)
        try:
            with self._connect() as conn:
                rows = conn.execute(
                    """
                    SELECT id, metadata_json
                    FROM messages
                    WHERE session_id = ? AND role = 'assistant'
                    ORDER BY id DESC
                    LIMIT 50
                    """,
                    (session_id,),
                ).fetchall()
        except sqlite3.Error:
            self.sqlite_available = False
            return self._last_rule_context_from_jsonl(session_id)
        for row in rows:
            try:
                metadata = json.loads(row["metadata_json"] or "{}")
            except json.JSONDecodeError:
                continue
            rule_id = metadata.get("rule_id")
            if rule_id:
                context = {
                    "rule_id": rule_id,
                    "rule_name": metadata.get("rule_name"),
                    "source_message_id": row["id"],
                }
                for key in ("stat_start_time", "stat_end_time"):
                    if metadata.get(key):
                        context[key] = metadata[key]
                return context
        return None
