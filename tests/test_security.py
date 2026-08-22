import unittest

from device_manager.security import AttemptLimiter


class AttemptLimiterTests(unittest.TestCase):
    def test_blocks_after_threshold_and_expires_after_window(self):
        now = [100.0]
        limiter = AttemptLimiter(
            max_failures=3,
            window_seconds=10,
            clock=lambda: now[0],
        )

        self.assertFalse(limiter.blocked("client"))
        limiter.register_failure("client")
        limiter.register_failure("client")
        self.assertFalse(limiter.blocked("client"))
        limiter.register_failure("client")
        self.assertTrue(limiter.blocked("client"))

        now[0] = 111.0
        self.assertFalse(limiter.blocked("client"))

    def test_success_clear_resets_failures(self):
        limiter = AttemptLimiter(max_failures=1)
        limiter.register_failure("client")
        self.assertTrue(limiter.blocked("client"))
        limiter.clear("client")
        self.assertFalse(limiter.blocked("client"))


if __name__ == "__main__":
    unittest.main()
