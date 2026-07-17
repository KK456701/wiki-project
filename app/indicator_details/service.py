from __future__ import annotations

import base64
import binascii
import hashlib
import logging
import os
import re
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

from pydantic import ValidationError

from app.hospital_auth.models import (
    DETAIL_EXPORT_PERMISSION,
    DETAIL_VIEW_PERMISSION,
    HospitalPrincipal,
)
from app.hospital_auth.repository import HospitalAuthRepository

from app.agent_tools.upload_tools import build_aggregate_comparison, parse_excel_preview

from .exporter import create_indicator_workbook, create_upload_comparison_workbook
from .models import CleanupResult, DetailPage, DetailSnapshotSummary, ExportSummary
from .repository import IndicatorDetailRepository
from .snapshot import DetailSnapshotStore


EXPORT_TTL = timedelta(hours=24)
logger = logging.getLogger(__name__)


def _utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class IndicatorDetailError(Exception):
    def __init__(self, message: str, *, code: str, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code


class IndicatorDetailService:
    def __init__(
        self,
        repository: IndicatorDetailRepository,
        snapshot_store: DetailSnapshotStore,
        audit_repository: HospitalAuthRepository,
        *,
        export_root: Path = Path("runtime/exports"),
        upload_root: Path | None = None,
        now_provider: Callable[[], datetime] = _utcnow,
        export_ttl: timedelta = EXPORT_TTL,
    ) -> None:
        self.repository = repository
        self.snapshot_store = snapshot_store
        self.audit_repository = audit_repository
        self.export_root = Path(export_root)
        self.upload_root = upload_root or (
            Path(__file__).resolve().parents[2] / "runtime" / "uploads"
        )
        self.now_provider = now_provider
        self.export_ttl = export_ttl

    def _audit(
        self,
        principal: HospitalPrincipal,
        action: str,
        result: str,
        *,
        rule_id: str | None = None,
        run_id: str | None = None,
        export_id: str | None = None,
        row_count: int | None = None,
        request_id: str | None = None,
        reason: str | None = None,
    ) -> None:
        self.audit_repository.insert_audit(
            action=action,
            result=result,
            user_id=principal.user_id,
            hospital_id=principal.hospital_id,
            rule_id=rule_id,
            run_id=run_id,
            export_id=export_id,
            row_count=row_count,
            request_id=request_id,
            reason=reason,
            now=self.now_provider(),
        )

    def _require_permission(
        self, principal: HospitalPrincipal, permission: str
    ) -> None:
        if principal.must_change_password:
            self._audit(
                principal,
                "ACCESS_DENIED",
                "denied",
                reason="AUTH_PASSWORD_CHANGE_REQUIRED",
            )
            raise IndicatorDetailError(
                "请先修改初始密码再查看指标明细",
                code="AUTH_PASSWORD_CHANGE_REQUIRED",
                status_code=403,
            )
        if permission not in principal.permissions:
            self._audit(
                principal,
                "ACCESS_DENIED",
                "denied",
                reason="AUTH_PERMISSION_DENIED",
            )
            raise IndicatorDetailError(
                "当前账号没有指标明细访问权限，请联系管理员",
                code="AUTH_PERMISSION_DENIED",
                status_code=403,
            )

    def _run_in_scope(
        self, principal: HospitalPrincipal, run_id: str
    ) -> dict[str, Any]:
        run = self.repository.get_run(run_id)
        if run is None or not principal.can_access_hospital(str(run.get("hospital_id"))):
            self._audit(
                principal,
                "ACCESS_DENIED",
                "denied",
                run_id=run_id,
                reason="DETAIL_SCOPE_DENIED",
            )
            raise IndicatorDetailError(
                "试运行记录不存在", code="DETAIL_RUN_NOT_FOUND", status_code=404
            )
        return run

    @staticmethod
    def _summary(export: dict[str, Any]) -> ExportSummary:
        return ExportSummary(
            export_id=str(export["export_id"]),
            run_id=str(export["run_id"]),
            hospital_id=str(export["hospital_id"]),
            rule_id=str(export["rule_id"]),
            file_name=str(export["file_name"]),
            row_count=int(export.get("row_count") or 0),
            status=str(export["status"]),
            created_at=export["created_at"],
            expires_at=export["expires_at"],
            download_count=int(export.get("download_count") or 0),
        )

    @staticmethod
    def _safe_segment(value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", value or ""):
            raise IndicatorDetailError(
                "文件标识无效", code="DETAIL_PATH_INVALID", status_code=400
            )
        return value

    def _resolve_relative_path(self, relative_path: str) -> Path:
        root = self.export_root.resolve()
        candidate = (root / relative_path).resolve()
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise IndicatorDetailError(
                "文件路径无效", code="DETAIL_PATH_INVALID", status_code=400
            ) from exc
        return candidate

    def ensure_snapshot(
        self, principal: HospitalPrincipal, run_id: str
    ) -> DetailSnapshotSummary:
        self._require_permission(principal, DETAIL_VIEW_PERMISSION)
        self.cleanup_expired()
        run = self._run_in_scope(principal, run_id)
        try:
            summary = self.snapshot_store.create(
                run_id, principal.hospital_id, principal.user_id
            )
        except ValidationError as exc:
            self._audit(
                principal,
                "DETAIL_PREVIEW",
                "failed",
                rule_id=str(run.get("rule_id") or ""),
                run_id=run_id,
                reason="DETAIL_CONTEXT_INVALID",
            )
            raise IndicatorDetailError(
                "本次试运行的明细上下文不完整，请重新试运行后再查看明细。",
                code="DETAIL_CONTEXT_INVALID",
                status_code=409,
            ) from exc
        except (LookupError, ValueError) as exc:
            reason = (
                "DETAIL_COUNT_MISMATCH"
                if "数据已经变化" in str(exc)
                else "DETAIL_SNAPSHOT_FAILED"
            )
            self._audit(
                principal,
                "DETAIL_COUNT_MISMATCH" if reason == "DETAIL_COUNT_MISMATCH" else "DETAIL_PREVIEW",
                "failed",
                rule_id=str(run.get("rule_id") or ""),
                run_id=run_id,
                reason=reason,
            )
            raise IndicatorDetailError(str(exc), code=reason, status_code=409) from exc
        self._audit(
            principal,
            "DETAIL_PREVIEW",
            "success",
            rule_id=summary.rule_id,
            run_id=run_id,
            row_count=summary.denominator_count,
        )
        return summary

    def get_page(
        self,
        principal: HospitalPrincipal,
        run_id: str,
        group: str,
        page: int,
        page_size: int,
    ) -> DetailPage:
        self._require_permission(principal, DETAIL_VIEW_PERMISSION)
        run = self._run_in_scope(principal, run_id)
        try:
            result = self.snapshot_store.read_page(
                run_id, principal.hospital_id, group, page, page_size
            )
        except LookupError as exc:
            raise IndicatorDetailError(
                "明细快照不存在", code="DETAIL_NOT_FOUND", status_code=404
            ) from exc
        except ValueError as exc:
            status_code = 410 if "过期" in str(exc) else 409
            code = "DETAIL_FILE_EXPIRED" if status_code == 410 else "DETAIL_FILE_INVALID"
            self._audit(
                principal,
                "DETAIL_FILE_EXPIRED" if status_code == 410 else "DETAIL_PREVIEW",
                "failed",
                rule_id=str(run.get("rule_id") or ""),
                run_id=run_id,
                reason=code,
            )
            raise IndicatorDetailError(str(exc), code=code, status_code=status_code) from exc
        self._audit(
            principal,
            "DETAIL_PREVIEW",
            "success",
            rule_id=str(run.get("rule_id") or ""),
            run_id=run_id,
            row_count=result.total,
        )
        return result

    def create_export(
        self,
        principal: HospitalPrincipal,
        run_id: str,
        confirmed: bool,
    ) -> ExportSummary:
        self._require_permission(principal, DETAIL_EXPORT_PERMISSION)
        if not confirmed:
            self._audit(
                principal,
                "ACCESS_DENIED",
                "denied",
                run_id=run_id,
                reason="DETAIL_EXPORT_CONFIRM_REQUIRED",
            )
            raise IndicatorDetailError(
                "导出前必须确认患者明细使用范围",
                code="DETAIL_EXPORT_CONFIRM_REQUIRED",
                status_code=400,
            )
        self.cleanup_expired()
        self._run_in_scope(principal, run_id)
        snapshot = self.ensure_snapshot(principal, run_id)
        try:
            _, rows = self.snapshot_store.read_all_rows(run_id, principal.hospital_id)
        except (LookupError, ValueError) as exc:
            raise IndicatorDetailError(
                str(exc), code="DETAIL_SNAPSHOT_INVALID", status_code=409
            ) from exc

        export_id = f"EXP_{uuid.uuid4().hex[:16]}"
        safe_hospital = self._safe_segment(principal.hospital_id)
        safe_run = self._safe_segment(run_id)
        start = re.sub(r"[^0-9]", "", snapshot.stat_start)[:8]
        end = re.sub(r"[^0-9]", "", snapshot.stat_end)[:8]
        file_name = f"{snapshot.rule_id}_{start}_{end}_{export_id}.xlsx"
        relative_path = f"{safe_hospital}/{safe_run}/{file_name}"
        now = self.now_provider()
        self.repository.create_export(
            export_id=export_id,
            snapshot_id=snapshot.snapshot_id,
            run_id=run_id,
            hospital_id=principal.hospital_id,
            rule_id=snapshot.rule_id,
            relative_path=relative_path,
            file_name=file_name,
            row_count=snapshot.denominator_count,
            created_by=principal.user_id,
            created_at=now,
            expires_at=now + self.export_ttl,
        )
        final_path = self._resolve_relative_path(relative_path)
        temp_path = final_path.with_name(final_path.name + ".tmp")
        try:
            create_indicator_workbook(
                temp_path, snapshot, rows, actor_id=principal.account_id
            )
            os.replace(temp_path, final_path)
            self.repository.mark_export_ready(export_id, _sha256(final_path))
        except Exception as exc:
            if temp_path.exists():
                temp_path.unlink()
            self.repository.mark_export_failed(export_id, str(exc))
            self._audit(
                principal,
                "DETAIL_EXPORT_CREATE",
                "failed",
                rule_id=snapshot.rule_id,
                run_id=run_id,
                export_id=export_id,
                reason="DETAIL_EXPORT_FAILED",
            )
            raise IndicatorDetailError(
                "导出文件生成失败，请稍后重试",
                code="DETAIL_EXPORT_FAILED",
                status_code=500,
            ) from exc
        ready = self.repository.get_export(export_id)
        if ready is None:
            raise RuntimeError("导出记录不存在")
        self._audit(
            principal,
            "DETAIL_EXPORT_CREATE",
            "success",
            rule_id=snapshot.rule_id,
            run_id=run_id,
            export_id=export_id,
            row_count=snapshot.denominator_count,
        )
        return self._summary(ready)

    @staticmethod
    def _decode_file_token(file_token: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", file_token or ""):
            raise IndicatorDetailError(
                "上传文件标识无效", code="UPLOAD_FILE_TOKEN_INVALID", status_code=400
            )
        try:
            padding = "=" * (-len(file_token) % 4)
            file_key = base64.b64decode(
                file_token + padding,
                altchars=b"-_",
                validate=True,
            ).decode("utf-8")
        except (binascii.Error, UnicodeDecodeError) as exc:
            raise IndicatorDetailError(
                "上传文件标识无效", code="UPLOAD_FILE_TOKEN_INVALID", status_code=400
            ) from exc
        if (
            not file_key
            or len(file_key) > 128
            or Path(file_key).name != file_key
            or "/" in file_key
            or "\\" in file_key
        ):
            raise IndicatorDetailError(
                "上传文件标识无效", code="UPLOAD_FILE_TOKEN_INVALID", status_code=400
            )
        return file_key

    def create_upload_comparison_export(
        self,
        principal: HospitalPrincipal,
        run_id: str,
        file_token: str,
        confirmed: bool,
    ) -> ExportSummary:
        """导出上传文件汇总值与指定系统试运行结果的差异。"""
        self._require_permission(principal, DETAIL_EXPORT_PERMISSION)
        if not confirmed:
            raise IndicatorDetailError(
                "导出前必须确认对比范围",
                code="UPLOAD_COMPARISON_EXPORT_CONFIRM_REQUIRED",
                status_code=400,
            )
        self.cleanup_expired()
        run = self._run_in_scope(principal, run_id)
        if str(run.get("run_status") or "") != "success":
            raise IndicatorDetailError(
                "本次试运行未成功，不能生成差异表",
                code="UPLOAD_COMPARISON_RUN_INVALID",
                status_code=409,
            )
        file_key = self._decode_file_token(file_token)
        if not file_key.startswith(f"{principal.hospital_id}_"):
            raise IndicatorDetailError(
                "上传文件不存在", code="UPLOAD_NOT_FOUND", status_code=404
            )
        upload_root = Path(self.upload_root).resolve()
        file_path = (upload_root / file_key).resolve()
        try:
            file_path.relative_to(upload_root)
        except ValueError as exc:
            raise IndicatorDetailError(
                "上传文件标识无效", code="UPLOAD_FILE_TOKEN_INVALID", status_code=400
            ) from exc
        if not file_path.is_file():
            raise IndicatorDetailError(
                "上传文件不存在或已清理", code="UPLOAD_NOT_FOUND", status_code=404
            )
        preview = parse_excel_preview(file_path)
        if "error" in preview:
            raise IndicatorDetailError(
                str(preview["error"]), code="EXCEL_PARSE_ERROR", status_code=409
            )
        start = str(run.get("stat_start_time") or "")
        end = str(run.get("stat_end_time") or "")
        system_result = {
            "system_stat_period": f"{start} 至 {end}",
            "system_numerator": run.get("numerator_count"),
            "system_denominator": run.get("denominator_count"),
            "system_rate": run.get("result_value"),
        }
        comparison = build_aggregate_comparison(preview, system_result)
        if not comparison["metrics"]:
            raise IndicatorDetailError(
                "上传文件中未识别到可与系统核对的分子、分母或指标率",
                code="UPLOAD_COMPARISON_VALUES_MISSING",
                status_code=409,
            )
        run_context = run.get("run_context_json") or {}
        effective_rule = (
            run_context.get("effective_rule") or {}
            if isinstance(run_context, dict)
            else {}
        )
        rule_id = str(run.get("rule_id") or effective_rule.get("rule_id") or "")
        comparison.update({
            "rule_id": rule_id,
            "rule_name": str(
                effective_rule.get("rule_name")
                or (run_context.get("rule_name") if isinstance(run_context, dict) else "")
                or rule_id
            ),
            "hospital_id": principal.hospital_id,
            "file_name": preview["file_name"],
        })

        export_id = f"EXP_{uuid.uuid4().hex[:16]}"
        safe_hospital = self._safe_segment(principal.hospital_id)
        safe_run = self._safe_segment(run_id)
        start_text = re.sub(r"[^0-9]", "", start)[:8]
        end_text = re.sub(r"[^0-9]", "", end)[:8]
        file_name = f"{rule_id}_{start_text}_{end_text}_汇总差异_{export_id}.xlsx"
        relative_path = f"{safe_hospital}/{safe_run}/{file_name}"
        now = self.now_provider()
        self.repository.create_export(
            export_id=export_id,
            snapshot_id=f"UPLCMP_{uuid.uuid4().hex[:16]}",
            run_id=run_id,
            hospital_id=principal.hospital_id,
            rule_id=rule_id,
            relative_path=relative_path,
            file_name=file_name,
            row_count=len(comparison["metrics"]),
            created_by=principal.user_id,
            created_at=now,
            expires_at=now + self.export_ttl,
        )
        final_path = self._resolve_relative_path(relative_path)
        temp_path = final_path.with_name(final_path.name + ".tmp")
        try:
            create_upload_comparison_workbook(
                temp_path,
                comparison,
                actor_id=principal.account_id,
                created_at=now,
            )
            os.replace(temp_path, final_path)
            self.repository.mark_export_ready(export_id, _sha256(final_path))
        except Exception as exc:
            if temp_path.exists():
                temp_path.unlink()
            self.repository.mark_export_failed(export_id, str(exc))
            self._audit(
                principal,
                "UPLOAD_COMPARISON_EXPORT_CREATE",
                "failed",
                rule_id=rule_id,
                run_id=run_id,
                export_id=export_id,
                reason="UPLOAD_COMPARISON_EXPORT_FAILED",
            )
            raise IndicatorDetailError(
                "差异表生成失败，请稍后重试",
                code="UPLOAD_COMPARISON_EXPORT_FAILED",
                status_code=500,
            ) from exc
        ready = self.repository.get_export(export_id)
        if ready is None:
            raise RuntimeError("导出记录不存在")
        self._audit(
            principal,
            "UPLOAD_COMPARISON_EXPORT_CREATE",
            "success",
            rule_id=rule_id,
            run_id=run_id,
            export_id=export_id,
            row_count=len(comparison["metrics"]),
        )
        return self._summary(ready)

    def list_exports(self, principal: HospitalPrincipal) -> list[ExportSummary]:
        self._require_permission(principal, DETAIL_EXPORT_PERMISSION)
        self.cleanup_expired()
        return [
            self._summary(item)
            for item in self.repository.list_exports(principal.hospital_id)
        ]

    def resolve_download(
        self,
        principal: HospitalPrincipal,
        export_id: str,
        request_id: str | None = None,
    ) -> tuple[Path, str]:
        self._require_permission(principal, DETAIL_EXPORT_PERMISSION)
        self.cleanup_expired()
        export = self.repository.get_export(export_id)
        if export is None or not principal.can_access_hospital(
            str(export.get("hospital_id"))
        ):
            raise IndicatorDetailError(
                "导出文件不存在", code="DETAIL_EXPORT_NOT_FOUND", status_code=404
            )
        if export.get("status") == "expired" or export["expires_at"] <= self.now_provider():
            raise IndicatorDetailError(
                "导出文件已过期，请重新生成",
                code="DETAIL_FILE_EXPIRED",
                status_code=410,
            )
        if export.get("status") != "ready":
            raise IndicatorDetailError(
                "导出文件尚未生成", code="DETAIL_EXPORT_NOT_READY", status_code=409
            )
        path = self._resolve_relative_path(str(export["relative_path"]))
        if not path.is_file() or _sha256(path) != str(export.get("file_sha256") or ""):
            raise IndicatorDetailError(
                "导出文件校验失败，请重新生成",
                code="DETAIL_FILE_INVALID",
                status_code=409,
            )
        now = self.now_provider()
        self.repository.record_download(export_id, now)
        self._audit(
            principal,
            "DETAIL_EXPORT_DOWNLOAD",
            "success",
            rule_id=str(export["rule_id"]),
            run_id=str(export["run_id"]),
            export_id=export_id,
            row_count=int(export.get("row_count") or 0),
            request_id=request_id,
        )
        return path, str(export["file_name"])

    def cleanup_expired(self, now: datetime | None = None) -> CleanupResult:
        cutoff = now or self.now_provider()
        result = CleanupResult()
        for kind, records in (
            ("snapshot", self.repository.list_expired_snapshots(cutoff)),
            ("export", self.repository.list_expired_exports(cutoff)),
        ):
            for record in records:
                try:
                    path = self._resolve_relative_path(str(record["relative_path"]))
                    if path.exists():
                        path.unlink()
                    if kind == "snapshot":
                        self.repository.mark_snapshot_expired(str(record["snapshot_id"]))
                        result.expired_snapshots += 1
                    else:
                        self.repository.mark_export_expired(str(record["export_id"]))
                        result.expired_exports += 1
                except Exception:
                    result.failed_paths += 1
                    logger.exception("indicator detail cleanup failed")
                    self._audit_cleanup(record, kind, cutoff, success=False)
                    continue
                self._audit_cleanup(record, kind, cutoff, success=True)
        return result

    def _audit_cleanup(
        self,
        record: dict[str, Any],
        kind: str,
        now: datetime,
        *,
        success: bool,
    ) -> None:
        try:
            self.audit_repository.insert_audit(
                action="DETAIL_FILE_EXPIRED",
                result="success" if success else "failed",
                user_id=None,
                hospital_id=str(record.get("hospital_id") or ""),
                rule_id=str(record.get("rule_id") or ""),
                run_id=str(record.get("run_id") or ""),
                export_id=(
                    str(record.get("export_id")) if kind == "export" else None
                ),
                row_count=(
                    int(
                        record.get("row_count")
                        or record.get("denominator_count")
                        or 0
                    )
                    if success
                    else None
                ),
                reason=(
                    f"DETAIL_{kind.upper()}_EXPIRED"
                    if success
                    else "DETAIL_CLEANUP_FAILED"
                ),
                now=now,
            )
        except Exception:
            logger.exception("indicator detail cleanup audit failed")
