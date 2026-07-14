from __future__ import annotations

import base64
import re
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)


_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9_.-]{1,96}$")


class PackageSignatureError(RuntimeError):
    pass


class PackageSigner:
    def __init__(self, private_key: Ed25519PrivateKey, key_id: str) -> None:
        if not _KEY_ID_PATTERN.fullmatch(key_id):
            raise PackageSignatureError("PACKAGE_SIGNING_KEY_ID_INVALID")
        self.private_key = private_key
        self.key_id = key_id

    @classmethod
    def from_private_pem(cls, path: str | Path, key_id: str) -> "PackageSigner":
        try:
            loaded = serialization.load_pem_private_key(
                Path(path).read_bytes(), password=None
            )
        except (OSError, ValueError, TypeError) as exc:
            raise PackageSignatureError("PACKAGE_SIGNING_KEY_LOAD_FAILED") from exc
        if not isinstance(loaded, Ed25519PrivateKey):
            raise PackageSignatureError("PACKAGE_SIGNING_KEY_TYPE_INVALID")
        return cls(loaded, key_id)

    def sign_checksums(self, payload: bytes) -> dict[str, str]:
        signature = self.private_key.sign(payload)
        return {
            "algorithm": "Ed25519",
            "key_id": self.key_id,
            "signed_file": "checksums.json",
            "signature": base64.b64encode(signature).decode("ascii"),
        }


def verify_checksums(
    payload: bytes,
    signature_payload: dict[str, Any],
    trusted_keys_dir: str | Path,
) -> dict[str, str]:
    if signature_payload.get("algorithm") != "Ed25519":
        raise PackageSignatureError("PACKAGE_SIGNATURE_ALGORITHM_UNSUPPORTED")
    if signature_payload.get("signed_file") != "checksums.json":
        raise PackageSignatureError("PACKAGE_SIGNATURE_TARGET_INVALID")
    key_id = str(signature_payload.get("key_id") or "")
    if not _KEY_ID_PATTERN.fullmatch(key_id):
        raise PackageSignatureError("PACKAGE_SIGNING_KEY_ID_INVALID")
    public_path = Path(trusted_keys_dir) / f"{key_id}.pem"
    if not public_path.is_file():
        raise PackageSignatureError("PACKAGE_SIGNING_KEY_NOT_TRUSTED")
    try:
        loaded = serialization.load_pem_public_key(public_path.read_bytes())
    except (OSError, ValueError, TypeError) as exc:
        raise PackageSignatureError("PACKAGE_PUBLIC_KEY_LOAD_FAILED") from exc
    if not isinstance(loaded, Ed25519PublicKey):
        raise PackageSignatureError("PACKAGE_PUBLIC_KEY_TYPE_INVALID")
    try:
        signature = base64.b64decode(
            str(signature_payload.get("signature") or ""), validate=True
        )
        loaded.verify(signature, payload)
    except (InvalidSignature, ValueError) as exc:
        raise PackageSignatureError("PACKAGE_SIGNATURE_INVALID") from exc
    return {"algorithm": "Ed25519", "key_id": key_id, "status": "verified"}
