"""Browser Backend: Background Workers Module."""

from typing import Any, Callable, Dict, List


class BackgroundWorkerQueue:
    """Task queue for asynchronous index updates, telemetry processing, and downloads."""

    def __init__(self) -> None:
        self.queue: List[Dict[str, Any]] = []
        self.completed_tasks: List[str] = []

    def enqueue(self, task_name: str, payload: Dict[str, Any]) -> str:
        task_id = f"task-{len(self.queue) + 1:05d}"
        self.queue.append({"id": task_id, "name": task_name, "payload": payload, "status": "QUEUED"})
        return task_id

    def process_next(self) -> Optional[Dict[str, Any]]:
        if not self.queue:
            return None
        task = self.queue.pop(0)
        task["status"] = "COMPLETED"
        self.completed_tasks.append(task["id"])
        return task
