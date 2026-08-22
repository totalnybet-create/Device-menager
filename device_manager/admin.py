from __future__ import annotations

import argparse
import json

from sqlalchemy.exc import IntegrityError

from .auth import AuthRepository, AuthService, VALID_ROLES
from .database import SessionLocal


def create_user(name: str, role: str) -> dict[str, str | int]:
    with SessionLocal() as session:
        service = AuthService(AuthRepository(session))
        try:
            principal, issued = service.create_user(name=name, role=role)
        except IntegrityError as exc:
            session.rollback()
            raise RuntimeError(f"user '{name}' already exists") from exc

    return {
        "id": principal.id,
        "name": principal.name,
        "role": principal.role,
        "token": issued.token,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Device Manager administrative CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create-user", help="create API user and issue bearer token")
    create.add_argument("--name", required=True)
    create.add_argument("--role", required=True, choices=sorted(VALID_ROLES))

    args = parser.parse_args()
    if args.command == "create-user":
        result = create_user(args.name, args.role)
        print(json.dumps(result, ensure_ascii=False))
        print("Token is shown once. Store it securely; only its SHA-256 hash is in the database.")


if __name__ == "__main__":
    main()
