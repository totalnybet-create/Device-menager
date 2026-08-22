import tempfile
import unittest
from pathlib import Path

from device_manager.database import Base, create_session_factory, make_engine
from device_manager.repository import DeviceRepository
from device_manager.schemas import DeviceCreate, DeviceUpdate
from device_manager.service import DeviceNotFoundError, DeviceService


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db_path = Path(self.tmp.name) / "service.db"
        self.engine = make_engine(f"sqlite:///{db_path}")
        Base.metadata.create_all(self.engine)
        self.session_factory = create_session_factory(self.engine)
        self.session = self.session_factory()
        self.service = DeviceService(DeviceRepository(self.session))

    def tearDown(self):
        self.session.close()
        self.engine.dispose()
        self.tmp.cleanup()

    def test_service_crud_and_missing(self):
        created = self.service.create_device(
            DeviceCreate(name="Tablet", device_type="Mobile", status="Online")
        )

        self.assertEqual(self.service.get_device(created.id).name, "Tablet")
        self.assertEqual(len(self.service.list_devices()), 1)

        updated = self.service.update_device(
            created.id,
            DeviceUpdate(status="Offline"),
        )
        self.assertEqual(updated.status, "Offline")

        self.service.delete_device(created.id)
        with self.assertRaises(DeviceNotFoundError):
            self.service.get_device(created.id)


if __name__ == "__main__":
    unittest.main()
