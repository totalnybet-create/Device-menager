import tempfile
import unittest
from pathlib import Path

from pydantic import ValidationError

from device_manager.database import Base, create_session_factory, make_engine
from device_manager.repository import DeviceRepository
from device_manager.schemas import DeviceCreate, DeviceUpdate


class PersistenceTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        db_path = Path(self.tempdir.name) / "devices.db"
        self.engine = make_engine(f"sqlite:///{db_path}")
        Base.metadata.create_all(self.engine)
        self.session_factory = create_session_factory(self.engine)

    def tearDown(self):
        self.engine.dispose()
        self.tempdir.cleanup()

    def test_create_persists_across_sessions(self):
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            created = repository.create(
                DeviceCreate(
                    name="Serwer Główny",
                    device_type="Server",
                    status="Aktywny",
                )
            )
            created_id = created.id

        with self.session_factory() as session:
            repository = DeviceRepository(session)
            loaded = repository.get(created_id)
            self.assertIsNotNone(loaded)
            self.assertEqual(loaded.name, "Serwer Główny")
            self.assertEqual(loaded.status, "Aktywny")

    def test_full_crud(self):
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            created = repository.create(
                DeviceCreate(name="Router Biurowy", device_type="Network")
            )

            updated = repository.update(
                created.id,
                DeviceUpdate(status="Offline"),
            )
            self.assertIsNotNone(updated)
            self.assertEqual(updated.status, "Offline")

            self.assertEqual(len(repository.list()), 1)
            self.assertTrue(repository.delete(created.id))
            self.assertEqual(repository.list(), [])
            self.assertFalse(repository.delete(created.id))

    def test_validation_rejects_blank_values(self):
        with self.assertRaises(ValidationError):
            DeviceCreate(name="   ", device_type="Server")

    def test_missing_update_does_not_create_device(self):
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            result = repository.update(999, DeviceUpdate(status="Offline"))
            self.assertIsNone(result)
            self.assertEqual(repository.list(), [])


if __name__ == "__main__":
    unittest.main()
