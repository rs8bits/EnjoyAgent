#!/usr/bin/env python3
"""Acceptance test against an isolated local API; creates two disposable users and tasks.

Run only against a disposable database/schema. Does not print passwords or access tokens.
"""
import hashlib
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("SANDBOX_TEST_BASE_URL", "http://127.0.0.1:18080")


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, body=None, headers=None, raw=False, method=None):
        supplied = {"Origin": "http://localhost:5173", **(headers or {})}
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            supplied["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE + path, data=body, headers=supplied, method=method)
        with self.opener.open(request, timeout=180) as response:
            data = response.read()
            return data if raw else json.loads(data)["data"]

    def register(self):
        return self.request("/api/auth/register", {
            "email": f"sandbox-{uuid.uuid4().hex}@example.test",
            "password": uuid.uuid4().hex,
            "displayName": "Sandbox acceptance",
        })

    def task(self, operation="PYTHON", code="pass", files=None, timeout=10, key=None):
        return self.request("/api/sandbox/tasks", {"operation": operation, "code": code, "fileIds": files or [], "timeoutSeconds": timeout}, {"Idempotency-Key": key or str(uuid.uuid4())})

    def await_task(self, task_id, state=None):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            detail = self.request(f"/api/sandbox/tasks/{task_id}")
            status = detail["task"]["status"]
            if status == state or status in {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"}:
                return detail
            time.sleep(0.3)
        raise AssertionError(f"Task did not finish: {task_id}")


def main():
    client = Client()
    client.register()
    status = client.request("/api/sandbox/status")
    assert status["ready"], status
    boundary = "enjoy-" + uuid.uuid4().hex
    source = b"item,amount\na,10\nb,20\n"
    upload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sales.csv"\r\nContent-Type: text/csv\r\n\r\n'.encode()
              + source + f"\r\n--{boundary}--\r\n".encode())
    file = client.request("/api/files", upload, {"Content-Type": "multipart/form-data; boundary=" + boundary})
    assert file["sha256"] == hashlib.sha256(source).hexdigest()
    assert client.request(f'/api/files/{file["id"]}/download', raw=True) == source
    code = """import csv,json,sys
from pathlib import Path
source=next(Path('/input').iterdir())
with source.open() as f: total=sum(int(row['amount']) for row in csv.DictReader(f))
Path('/output/report.json').write_text(json.dumps({'total':total}),encoding='utf-8')
print('analysis complete')
print('sample diagnostic',file=sys.stderr)
"""
    key = str(uuid.uuid4())
    task = client.task(code=code, files=[file["id"]], key=key)
    assert client.task(code=code, files=[file["id"]], key=key)["id"] == task["id"]
    result = client.await_task(task["id"])
    assert result["task"]["status"] == "SUCCEEDED", result
    assert result["execution"]["exit_code"] == 0
    assert "analysis complete" in result["execution"]["stdout"]
    assert "sample diagnostic" in result["execution"]["stderr"]
    artifact = result["artifacts"][0]
    downloaded = client.request(f'/api/files/{artifact["id"]}/download', raw=True)
    assert json.loads(downloaded)["total"] == 30
    assert hashlib.sha256(downloaded).hexdigest() == artifact["sha256"]
    assert client.request(f'/api/files/{artifact["id"]}/preview')["kind"] == "text"
    other = Client()
    other.register()
    for path in (f'/api/files/{file["id"]}/download', f'/api/sandbox/tasks/{task["id"]}'):
        try:
            other.request(path)
            raise AssertionError("Foreign resource was accessible")
        except urllib.error.HTTPError as error:
            assert error.code == 404, error.code
    demo = client.await_task(client.task(operation="DEMO", code="")["id"])
    assert demo["task"]["status"] == "SUCCEEDED" and len(demo["artifacts"]) == 2
    failed = client.await_task(client.task(code="import sys\nprint('failed',file=sys.stderr)\nsys.exit(7)")["id"])
    assert failed["task"]["status"] == "FAILED" and failed["execution"]["exit_code"] == 7
    timed = client.await_task(client.task(code="import time\ntime.sleep(30)", timeout=1)["id"])
    assert timed["task"]["status"] == "TIMED_OUT", timed
    running = client.task(code="import time\ntime.sleep(30)", timeout=30)
    client.await_task(running["id"], "RUNNING")
    client.request(f'/api/sandbox/tasks/{running["id"]}/cancel', b"")
    cancelled = client.await_task(running["id"])
    assert cancelled["task"]["status"] == "CANCELLED", cancelled
    print("PASS: upload/hash/download, Python/stdout/stderr/artifact/preview, idempotency, tenant isolation, demo, nonzero exit, timeout, cancellation")


if __name__ == "__main__":
    main()
