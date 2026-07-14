from __future__ import annotations

import hashlib
import io
import json
import zipfile
from pathlib import Path

import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from sqlalchemy import create_engine, text

from app.kb.hospital_import import HospitalReleaseError, HospitalReleaseRepository
from app.kb.signing import PackageSigner


def _runtime_engine():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(
            text(
                """CREATE TABLE med_index_standard (
                index_code TEXT PRIMARY KEY, version INTEGER NOT NULL)"""
            )
        )
        conn.execute(text("INSERT INTO med_index_standard VALUES ('R001', 1)"))
    return engine


def _signer_pair(root: Path) -> tuple[PackageSigner, Path]:
    private = Ed25519PrivateKey.generate()
    private_path = root / "company-private.pem"
    trusted = root / "trusted-companies"
    trusted.mkdir()
    private_path.write_bytes(
        private.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    (trusted / "company_main.pem").write_bytes(
        private.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return PackageSigner.from_private_pem(private_path, "company_main"), trusted


def _release_package(
    *,
    format_version: str,
    signer: PackageSigner | None = None,
    definition: str = "公司标准定义",
) -> bytes:
    manifest = {
        "package_id": "REL_001",
        "release_id": "REL_001",
        "version": 2,
        "format_version": format_version,
        "published_at": "2026-07-14T12:00:00",
        "rule_count": 1,
        "contains_patient_data": False,
    }
    if format_version == "company-release-v3":
        manifest.update(
            {
                "compatible_system_versions": ["0.1.0"],
                "signature_algorithm": "Ed25519",
                "signer_key_id": "company_main",
            }
        )
    files = {
        "manifest.yaml": yaml.safe_dump(manifest, allow_unicode=True).encode(),
        "rules/R001.yaml": yaml.safe_dump(
            {"rule_id": "R001", "definition": definition}, allow_unicode=True
        ).encode(),
    }
    checksums = {
        name: hashlib.sha256(content).hexdigest() for name, content in files.items()
    }
    checksum_bytes = json.dumps(checksums, sort_keys=True).encode()
    files["checksums.json"] = checksum_bytes
    if signer is not None:
        files["signature.json"] = json.dumps(
            signer.sign_checksums(checksum_bytes), sort_keys=True
        ).encode()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, content in files.items():
            zf.writestr(name, content)
    return buffer.getvalue()


def test_verified_release_is_stored_without_applying_rules(tmp_path: Path) -> None:
    signer, trusted = _signer_pair(tmp_path)
    engine = _runtime_engine()
    repository = HospitalReleaseRepository(engine, trusted, system_version="0.1.0")

    imported = repository.import_package(
        _release_package(format_version="company-release-v3", signer=signer),
        "hospital_admin",
    )

    with engine.connect() as conn:
        standard_version = conn.execute(
            text("SELECT version FROM med_index_standard WHERE index_code='R001'")
        ).scalar_one()
    assert imported["signature_status"] == "verified"
    assert imported["compatibility_status"] == "compatible"
    assert imported["status"] == "ready_for_adaptation"
    assert imported["items"][0]["item_type"] == "rule"
    assert standard_version == 1


def test_unsigned_legacy_release_stays_quarantined(tmp_path: Path) -> None:
    _, trusted = _signer_pair(tmp_path)
    repository = HospitalReleaseRepository(
        _runtime_engine(), trusted, system_version="0.1.0"
    )

    imported = repository.import_package(
        _release_package(format_version="company-release-v2"), "hospital_admin"
    )

    assert imported["signature_status"] == "legacy_unsigned"
    assert imported["compatibility_status"] == "review_required"
    assert imported["status"] == "quarantined"


def test_repeat_import_is_idempotent_and_changed_package_id_conflicts(
    tmp_path: Path,
) -> None:
    signer, trusted = _signer_pair(tmp_path)
    repository = HospitalReleaseRepository(
        _runtime_engine(), trusted, system_version="0.1.0"
    )
    package = _release_package(format_version="company-release-v3", signer=signer)

    first = repository.import_package(package, "hospital_admin")
    second = repository.import_package(package, "hospital_admin")

    assert second["import_id"] == first["import_id"]
    assert second["duplicate"] is True
    changed = _release_package(
        format_version="company-release-v3",
        signer=signer,
        definition="同编号但内容变化",
    )
    try:
        repository.import_package(changed, "hospital_admin")
    except HospitalReleaseError as exc:
        assert "PACKAGE_ID_CONFLICT" in str(exc)
    else:
        raise AssertionError("同一包编号的不同内容必须被拒绝")
