from __future__ import annotations

import argparse
import os
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def _write_pair(private_path: Path, trusted_path: Path) -> None:
    private_key = Ed25519PrivateKey.generate()
    private_path.parent.mkdir(parents=True, exist_ok=True)
    trusted_path.parent.mkdir(parents=True, exist_ok=True)
    private_path.write_bytes(
        private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    try:
        os.chmod(private_path, 0o600)
    except OSError:
        pass
    trusted_path.write_bytes(
        private_key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="生成离线知识包 Ed25519 演示密钥")
    parser.add_argument("--output", default="runtime/package-keys")
    parser.add_argument("--hospital-id", default="hospital_001")
    parser.add_argument("--company-id", default="company_main")
    args = parser.parse_args()
    root = Path(args.output)
    _write_pair(
        root / "hospital-private.pem",
        root / "trusted-hospitals" / f"{args.hospital_id}.pem",
    )
    _write_pair(
        root / "company-private.pem",
        root / "trusted-companies" / f"{args.company_id}.pem",
    )
    print(f"密钥已生成到 {root.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
