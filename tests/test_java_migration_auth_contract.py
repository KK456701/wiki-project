from __future__ import annotations

import json
from pathlib import Path

from app.hospital_auth.service import hash_password, hash_token


ROOT = Path(__file__).resolve().parents[1]


def test_java_python_auth_crypto_contract() -> None:
    vector = json.loads(
        (ROOT / "contracts/migration/v1/auth-crypto-vector.json").read_text(
            encoding="utf-8"
        )
    )

    assert hash_password(
        vector["password"],
        bytes.fromhex(vector["salt_hex"]),
        vector["iterations"],
    ) == vector["password_hash_base64"]
    assert hash_token(vector["token"]) == vector["token_sha256"]
