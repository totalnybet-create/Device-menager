"""create devices table

Revision ID: 0001
Revises:
Create Date: 2026-08-22
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "devices",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("device_type", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("length(trim(name)) > 0", name="ck_devices_name_not_blank"),
        sa.CheckConstraint("length(trim(device_type)) > 0", name="ck_devices_type_not_blank"),
        sa.CheckConstraint("length(trim(status)) > 0", name="ck_devices_status_not_blank"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_devices_name"), "devices", ["name"], unique=False)
    op.create_index(op.f("ix_devices_device_type"), "devices", ["device_type"], unique=False)
    op.create_index(op.f("ix_devices_status"), "devices", ["status"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_devices_status"), table_name="devices")
    op.drop_index(op.f("ix_devices_device_type"), table_name="devices")
    op.drop_index(op.f("ix_devices_name"), table_name="devices")
    op.drop_table("devices")
