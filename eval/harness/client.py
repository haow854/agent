from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.request


class ApiError(RuntimeError):
    pass


class DocAgentClient:

    def __init__(self, base_url: str, timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token = None
        self.user_id = None

    def _request(self, method, path, body=None, auth=True):
        headers = {"Content-Type": "application/json"}
        if auth and self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:500]
            raise ApiError(f"{method} {path} -> HTTP {error.code}: {detail}") from None
        except urllib.error.URLError as error:
            raise ApiError(f"{method} {path} -> {error.reason}") from None
        if isinstance(payload, dict) and "code" in payload:
            if payload["code"] not in (200, 0, None):
                raise ApiError(
                    f"{method} {path} -> code {payload['code']}: {payload.get('message')}")
            return payload.get("data")
        return payload

    def ensure_login(self, username, password, email):
        try:
            self.login(username, password)
        except ApiError:
            digest = hashlib.md5(username.encode("utf-8")).hexdigest()
            phone = "13" + str(int(digest[:12], 16) % 10 ** 9).zfill(9)
            self._request("POST", "/api/users/register", {
                "username": username, "password": password,
                "email": email, "phone": phone}, auth=False)
            self.login(username, password)

    def login(self, username, password):
        data = self._request("POST", "/api/users/login",
                             {"username": username, "password": password}, auth=False)
        self.token = data["accessToken"]
        self.user_id = (data.get("user") or {}).get("id")
        return data

    def create_document(self, title, content):
        return self._request("POST", "/api/documents",
                             {"title": title, "content": content})

    def get_document(self, document_id):
        return self._request("GET", f"/api/documents/{document_id}")

    def delete_document(self, document_id):
        try:
            self._request("DELETE", f"/api/documents/{document_id}")
        except ApiError:
            pass

    def list_user_documents(self):
        data = self._request("GET", f"/api/documents/user/{self.user_id}")
        if isinstance(data, dict):
            for key in ("records", "list", "items", "content", "documents"):
                if isinstance(data.get(key), list):
                    return data[key]
            return []
        return data or []

    def submit_task(self, goal, idempotency_key, document_id=None,
                    workspace="all", model=None, session_id=None):
        body = {"task": goal, "idempotencyKey": idempotency_key,
                "workspace": workspace, "ragEnabled": False}
        if document_id:
            body["documentId"] = document_id
        if model:
            body["model"] = model
        if session_id:
            body["sessionId"] = session_id
        return self._request("POST", "/api/ai/async/agent/execute", body)["jobId"]

    def confirm_approvals(self, task_id, tool_call_ids, user_inputs=None):
        body = {"taskId": task_id, "approvedToolCallIds": tool_call_ids}
        if user_inputs:
            body["userInputs"] = user_inputs
        return self._request(
            "POST", "/api/ai/async/agent/approvals/confirm", body)["jobId"]

    def reject_approvals(self, task_id):
        return self._request(
            "POST", f"/api/ai/agent/tasks/{task_id}/approvals/cancel")

    def continue_task(self, task_id, user_inputs):
        return self._request(
            "POST", f"/api/ai/async/agent/tasks/{task_id}/continue",
            {"userInputs": user_inputs})["jobId"]

    def get_job(self, job_id):
        return self._request("GET", f"/api/ai/async/jobs/{job_id}")

    def get_task(self, task_id):
        return self._request("GET", f"/api/ai/agent/tasks/{task_id}")

    def delete_task(self, task_id):
        try:
            self._request("DELETE", f"/api/ai/agent/tasks/{task_id}")
        except ApiError:
            pass

    def session_history(self, session_id):
        return self._request("GET", f"/api/ai/agent/session/{session_id}/history")

    def list_memory(self):
        return self._request("GET", "/api/ai/agent/memory") or []

    def delete_memory(self, name):
        try:
            self._request("DELETE", f"/api/ai/agent/memory/{name}")
        except ApiError:
            pass

    def wait_for_job(self, job_id, timeout_seconds, poll_seconds=2.0):
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            job = self.get_job(job_id)
            if job.get("status") == "success":
                return job
            if job.get("status") == "failed":
                raise ApiError(f"job {job_id} failed: {job.get('error')}")
            time.sleep(poll_seconds)
        raise ApiError(f"job {job_id} still {job.get('status')} after {timeout_seconds}s")
