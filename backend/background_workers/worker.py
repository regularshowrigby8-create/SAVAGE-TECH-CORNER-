"""Browser Backend: Background Workers Module."""

from typing import Any, Callable, Dict, List, Optional


class BackgroundWorkerQueue:
    """Task queue for asynchronous index updates, telemetry processing, and downloads."""

    def __init__(self) -> None:
        self.queue: List[Dict[str, Any]] = []
        self.completed_tasks: List[str] = []
        self._counter: int = 0

    def enqueue(self, task_name: str, payload: Dict[str, Any]) -> str:
        self._counter += 1
        task_id = f"task-{self._counter:05d}"
        self.queue.append({"id": task_id, "name": task_name, "payload": payload, "status": "QUEUED"})
        return task_id

    def process_next(self) -> Optional[Dict[str, Any]]:
        if not self.queue:
            return None
        task = self.queue.pop(0)
        task["status"] = "COMPLETED"
        self.completed_tasks.append(task["id"])
        return task
