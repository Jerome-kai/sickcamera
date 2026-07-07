from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from threading import Lock


class PersistentJobStore:
    """Tiny disk-backed queue for jobs that must survive service restarts."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = Lock()
        self.path.mkdir(parents=True, exist_ok=True)

    def _entry_path(self, job_id: str) -> Path:
        return self.path / f"{job_id}.json"

    @staticmethod
    def _sort_timestamp(value: object) -> datetime:
        raw = str(value or "").strip()
        if not raw:
            return datetime.min
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            return datetime.min

    @staticmethod
    def _write_json(path: Path, payload: dict[str, object]) -> None:
        temp_path = path.with_suffix(f"{path.suffix}.tmp")
        temp_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        temp_path.replace(path)

    def save_entry(self, job_id: str, payload: dict[str, object]) -> dict[str, object]:
        cleaned = dict(payload)
        cleaned["id"] = job_id
        with self._lock:
            self._write_json(self._entry_path(job_id), cleaned)
        return cleaned

    def load_entry(self, job_id: str) -> dict[str, object] | None:
        path = self._entry_path(job_id)
        if not path.is_file():
            return None
        with self._lock:
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                return None
        return loaded if isinstance(loaded, dict) else None

    def delete_entry(self, job_id: str) -> None:
        path = self._entry_path(job_id)
        with self._lock:
            try:
                path.unlink()
            except FileNotFoundError:
                return

    def count(self) -> int:
        with self._lock:
            return sum(1 for _ in self.path.glob("*.json"))

    def next_due_entry(self, now: datetime | None = None) -> tuple[str, dict[str, object]] | None:
        current = now or datetime.now()
        candidates: list[tuple[datetime, datetime, str, dict[str, object]]] = []
        with self._lock:
            for path in self.path.glob("*.json"):
                try:
                    loaded = json.loads(path.read_text(encoding="utf-8"))
                except Exception:
                    continue
                if not isinstance(loaded, dict):
                    continue
                job_id = str(loaded.get("id") or path.stem).strip() or path.stem
                due_at = self._sort_timestamp(loaded.get("next_attempt_at"))
                created_at = self._sort_timestamp(loaded.get("created_at"))
                if due_at <= current:
                    candidates.append((due_at, created_at, job_id, loaded))
        if not candidates:
            return None
        _, _, job_id, payload = min(candidates, key=lambda item: (item[0], item[1], item[2]))
        payload["id"] = job_id
        return job_id, payload
