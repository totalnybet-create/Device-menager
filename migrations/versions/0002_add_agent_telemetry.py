"""add agent telemetry fields

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-22
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table("devices") as batch_op:
        batch_op.add_column(sa.Column("agent_id", sa.String(length=36), nullable=True))
        batch_op.add_column(sa.Column("hostname", sa.String(length=255), nullable=True))
        batch_op.add_column(sa.Column("platform", sa.String(length=64), nullable=True))
        batch_op.add_column(sa.Column("agent_version", sa.String(length=32), nullable=True))
        batch_op.add_column(sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True))
        batch_op.create_index("ix_devices_agent_id", ["agent_id"], unique=True)
        batch_op.create_index("ix_devices_last_seen_at", ["last_seen_at"], unique=False)


def downgrade() -> None:
    with op.batch_alter_table("devices") as batch_op:
        batch_op.drop_index("ix_devices_last_seen_at")
        batch_op.drop_index("ix_devices_agent_id")
        batch_op.drop_column("last_seen_at")
        batch_op.drop_column("agent_version")
        batch_op.drop_column("platform")
        batch_op.drop_column("hostname")
        batch_op.drop_column("agent_id")
