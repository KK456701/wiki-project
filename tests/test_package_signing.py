from __future__ import annotations

from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from sqlalchemy import create_engine, inspect

from app.kb.exchange_schema import EXCHANGE_TABLES, ensure_kb_exchange_schema
from app.kb.signing import PackageSignatureError, PackageSigner, verify_checksums


def _write_key_pair(root: Path, key_id: str) -> tuple[Path, Path]:
    private_key = Ed25519PrivateKey.generate()
    private_path = root / "private.pem"
    trusted_dir = root / "trusted"
    trusted_dir.mkdir()
    private_path.write_bytes(
        private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    (trusted_dir / f"{key_id}.pem").write_bytes(
        private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return private_path, trusted_dir


def test_ed25519_signature_detects_changed_checksums(tmp_path: Path) -> None:
    private_path, trusted_dir = _write_key_pair(tmp_path, "hospital_001")
    signer = PackageSigner.from_private_pem(private_path, "hospital_001")
    checksums = b'{"manifest.yaml":"abc"}'

    signature = signer.sign_checksums(checksums)

    verified = verify_checksums(checksums, signature, trusted_dir)
    assert verified["key_id"] == "hospital_001"
    assert verified["algorithm"] == "Ed25519"
    with pytest.raises(PackageSignatureError, match="PACKAGE_SIGNATURE_INVALID"):
        verify_checksums(b'{"manifest.yaml":"changed"}', signature, trusted_dir)


def test_verifier_rejects_unknown_key_id(tmp_path: Path) -> None:
    private_path, trusted_dir = _write_key_pair(tmp_path, "hospital_001")
    signer = PackageSigner.from_private_pem(private_path, "unknown_hospital")

    with pytest.raises(PackageSignatureError, match="PACKAGE_SIGNING_KEY_NOT_TRUSTED"):
        verify_checksums(b"{}", signer.sign_checksums(b"{}"), trusted_dir)


def test_exchange_schema_is_idempotent() -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")

    first = ensure_kb_exchange_schema(engine)
    second = ensure_kb_exchange_schema(engine)

    assert set(first["created_tables"]) == set(EXCHANGE_TABLES)
    assert second["created_tables"] == []
    assert set(EXCHANGE_TABLES) <= set(inspect(engine).get_table_names())
