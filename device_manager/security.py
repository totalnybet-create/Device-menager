from __future__ import annotations

import time
from collections import defaultdict, deque
from collections.abc import Callable
from threading import Lock


class AttemptLimiter:
    """Small per-process limiter for repeated authentication failures.

    Production deployments with multiple workers should additionally enforce
    distributed limits at a reverse proxy/gateway or shared store.
    """

    def __init__(
        self,
        *,
        max_failures: int = 5,
        window_seconds: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.max_failures = max_failures
        self.window_seconds = window_seconds
        self.clock = clock
        self._failures: dict[str, deque[float]] = defaultdict(deque)
        self._lock = Lock()

    def _prune(self, key: str, now: float) -> deque[float]:
        attempts = self._failures[key]
        threshold = now - self.window_seconds
        while attempts and attempts[0] <= threshold:
            attempts.popleft()
        return attempts

    def blocked(self, key: str) -> bool:
        now = self.clock()
        with self._lock:
            return len(self._prune(key, now)) >= self.max_failures

    def register_failure(self, key: str) -> None:
        now = self.clock()
        with self._lock:
            attempts = self._prune(key, now)
            attempts.append(now)

    def clear(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
