from __future__ import annotations

import argparse
import itertools
import json
import re
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from harness import checks as check_module
from harness.client import ApiError, DocAgentClient

ROOT = Path(__file__).resolve().parents[1]
TASK_DIR = ROOT / "tasks"
DOC_DIR = ROOT / "fixtures" / "docs"
RUNS_DIR = ROOT / "runs"

PLACEHOLDER = re.compile(r"\{\{(doc|title):(\d+)\}\}")


def load_tasks(sets, only_ids, only_tags):
    tasks = []
    for set_name in sets:
        for path in sorted((TASK_DIR / set_name).glob("*.json")):
            task = json.loads(path.read_text(encoding="utf-8"))
            task["set"] = set_name
            tasks.append(task)
    if only_ids:
        tasks = [task for task in tasks if task["id"] in only_ids]
    if only_tags:
        tasks = [task for task in tasks
                 if set(task.get("tags", [])) & set(only_tags)]
    return tasks


def fill(template, seeds):
    def replace(match):
        seed = seeds[int(match.group(2))]
        return str(seed["id"]) if match.group(1) == "doc" else seed["title"]
    return PLACEHOLDER.sub(replace, template)


def task_parts(task):
    if task.get("parts"):
        return task["parts"]
    return [task] + ([task["followup"]] if task.get("followup") else [])


def job_result(job):
    result = job.get("result")
    if not isinstance(result, dict):
        raise ApiError(f"job {job.get('jobId')} 没有返回任务快照: {result!r}")
    return result


def run_part(client, task, part, seeds, state, model):
    goal = fill(part["goal"], seeds)
    scope = part.get("scope") or {"workspace": "all"}
    document_id = None
    if scope.get("workspace") == "current":
        document_id = seeds[scope.get("doc", 0)]["id"]
    timeout = task.get("timeoutSeconds", 300)
    session_id = state.get("sessionId") if part.get("sameSession") else None
    job = client.wait_for_job(
        client.submit_task(goal, uuid.uuid4().hex,
                           document_id=document_id,
                           workspace=scope.get("workspace", "all"),
                           model=model,
                           session_id=session_id),
        timeout)
    snapshot = job_result(job)
    task_id = snapshot.get("taskId")
    if task_id:
        state["taskIds"].append(task_id)
    interactions = 0
    while snapshot.get("status") == "action_required" and task_id and interactions < 8:
        interactions += 1
        live = client.get_task(task_id)
        pending = live.get("pending") or []
        state["pauses"].extend(pending)
        state["pauseEvents"] += 1
        approvals = [p for p in pending if p.get("kind") == "APPROVAL"]
        asks = [p for p in pending if p.get("kind") == "ASK_USER"]
        reply = fill(part.get("userInput", task.get("userInput", "按你的判断处理即可。")), seeds)
        answers = {p["toolCallId"]: reply for p in asks}
        if approvals and part.get("approval", task.get("approval")) == "reject":
            client.reject_approvals(task_id)
            snapshot = client.get_task(task_id)
            break
        if approvals and part.get("approval", task.get("approval")) == "confirm":
            job = client.wait_for_job(
                client.confirm_approvals(
                    task_id, [p["toolCallId"] for p in approvals], answers),
                timeout)
        elif asks:
            job = client.wait_for_job(
                client.continue_task(task_id, answers), timeout)
        else:
            break
        snapshot = job_result(job)
    if task_id:
        snapshot = client.get_task(task_id)
    if snapshot.get("sessionId"):
        state["sessionId"] = snapshot["sessionId"]
    return snapshot


def gather_observed(client, task, seeds, state, snapshots):
    for seed in seeds:
        try:
            doc = client.get_document(seed["id"])
            if (doc.get("status") or "").upper() in ("DELETED", "REMOVED"):
                seed["after"] = None
            else:
                seed["after"] = doc.get("content") or ""
        except ApiError:
            seed["after"] = None
    seed_ids = {seed["id"] for seed in seeds}
    created = []
    for doc in client.list_user_documents():
        doc_id = doc.get("id")
        if doc_id in state["baseline"] or doc_id in seed_ids:
            continue
        try:
            created.append(client.get_document(doc_id))
        except ApiError:
            pass
    state["createdIds"] = [doc.get("id") for doc in created]
    steps = []
    for snapshot in snapshots:
        steps.extend(snapshot.get("steps") or [])
    final = snapshots[-1]
    memory = None
    if task.get("cleanupMemory") or any(
            spec.get("type") == "memory_contains"
            for spec in task.get("checks", [])):
        memory = client.list_memory()
    return {
        "task": final,
        "answer": final.get("answer") or "",
        "seeds": seeds,
        "created": created,
        "steps": steps,
        "pauses": state["pauses"],
        "pauseEvents": state["pauseEvents"],
        "iterations": sum(s.get("iterations") or 0 for s in snapshots),
        "memory": memory,
    }


def dump_traces(client, run_dir, task, snapshots, observed, trial=1):
    trace_dir = run_dir / (task["id"] if trial == 1 else f"{task['id']}-t{trial}")
    trace_dir.mkdir(parents=True, exist_ok=True)
    for index, snapshot in enumerate(snapshots):
        name = "task.json" if index == len(snapshots) - 1 else f"task_part{index}.json"
        (trace_dir / name).write_text(
            json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        session_id = snapshot.get("sessionId")
        if session_id:
            try:
                history = client.session_history(session_id)
                (trace_dir / f"history_{index}.json").write_text(
                    json.dumps(history, ensure_ascii=False, indent=2),
                    encoding="utf-8")
            except ApiError:
                pass
    docs_state = {
        "seeds": [{"id": s["id"], "title": s["title"],
                   "deleted": s["after"] is None,
                   "after": s["after"]} for s in observed["seeds"]],
        "created": observed["created"],
    }
    (trace_dir / "docs.json").write_text(
        json.dumps(docs_state, ensure_ascii=False, indent=2), encoding="utf-8")


def cleanup(client, task, seeds, state):
    for seed in seeds:
        client.delete_document(seed["id"])
    for doc_id in state.get("createdIds", []):
        client.delete_document(doc_id)
    for task_id in state["taskIds"]:
        client.delete_task(task_id)
    if task.get("cleanupMemory"):
        for entry in client.list_memory():
            name = entry.get("name") if isinstance(entry, dict) else None
            if name:
                client.delete_memory(name)


def run_one(client, task, model, run_dir, trial):
    started = time.monotonic()
    record = {"id": task["id"], "set": task["set"],
              "tags": task.get("tags", []), "trial": trial}
    seeds = []
    state = {"taskIds": [], "pauses": [], "pauseEvents": 0,
             "baseline": set(), "createdIds": []}
    try:
        state["baseline"] = {doc.get("id") for doc in client.list_user_documents()}
        for spec in task.get("seeds", []):
            content = (DOC_DIR / spec["file"]).read_text(encoding="utf-8")
            title = f"{spec['title']}-{uuid.uuid4().hex[:6]}"
            doc = client.create_document(title, content)
            seeds.append({"id": doc["id"], "title": title,
                          "before": content, "after": None})
        parts = task_parts(task)
        snapshots = [run_part(client, task, part, seeds, state, model)
                     for part in parts]
        observed = gather_observed(client, task, seeds, state, snapshots)
        results = check_module.evaluate(task.get("checks", []), observed)
        dump_traces(client, run_dir, task, snapshots, observed, trial)
        final = observed["task"]
        record.update({
            "passed": all(item["passed"] for item in results),
            "score": (sum(1 for item in results if item["passed"]) / len(results))
                     if results else 0.0,
            "checks": results,
            "status": final.get("status"),
            "iterations": observed["iterations"],
            "toolCalls": sum(s.get("toolCallCount") or 0 for s in snapshots),
            "promptTokens": sum(s.get("promptTokens") or 0 for s in snapshots),
            "cachedPromptTokens": sum(s.get("cachedPromptTokens") or 0
                                      for s in snapshots),
            "completionTokens": sum(s.get("completionTokens") or 0
                                    for s in snapshots),
        })
    except Exception as error:
        record.update({"passed": False, "score": 0.0,
                       "error": f"{type(error).__name__}: {error}"})
    finally:
        try:
            cleanup(client, task, seeds, state)
        except Exception:
            pass
    record["wallSeconds"] = round(time.monotonic() - started, 1)
    return record


def summarize(records):
    def rate(items):
        return (sum(1 for item in items if item.get("passed")) / len(items)
                if items else 0.0)

    summary = {"total": len(records), "passRate": round(rate(records), 4)}
    for set_name in sorted({record["set"] for record in records}):
        subset = [record for record in records if record["set"] == set_name]
        summary[f"passRate:{set_name}"] = round(rate(subset), 4)
    tags = sorted({tag for record in records for tag in record.get("tags", [])})
    for tag in tags:
        subset = [record for record in records if tag in record.get("tags", [])]
        summary[f"passRate:tag:{tag}"] = round(rate(subset), 4)
    scored = [record.get("score", 0.0) for record in records]
    summary["checklistAvg"] = round(sum(scored) / len(scored), 4) if scored else 0.0
    for key in ("promptTokens", "cachedPromptTokens", "completionTokens"):
        summary[key] = sum(record.get(key) or 0 for record in records)
    safety = [record for record in records if "safety" in record.get("tags", [])]
    summary["safetyGate"] = "PASS" if all(r.get("passed") for r in safety) else "FAIL"
    return summary


def print_report(records, summary):
    for record in records:
        mark = "PASS" if record.get("passed") else "FAIL"
        line = (f"[{mark}] {record['id']:<12} score={record.get('score', 0):.2f} "
                f"iters={record.get('iterations', '-'):<3} "
                f"tokens={record.get('promptTokens', 0)}+{record.get('completionTokens', 0)} "
                f"{record.get('wallSeconds', 0)}s")
        print(line)
        if record.get("error"):
            print(f"       错误: {record['error']}")
        for item in record.get("checks", []):
            if not item["passed"]:
                print(f"       未过: {item['check'].get('type')} {item['detail']}")
    print("")
    for key, value in summary.items():
        print(f"{key}: {value}")


def validate_tasks(tasks):
    problems = []
    for task in tasks:
        prefix = f"{task.get('set')}/{task.get('id')}"
        for key in ("id", "checks"):
            if key not in task:
                problems.append(f"{prefix}: 缺少字段 {key}")
        parts = task_parts(task)
        if not all(part.get("goal") for part in parts):
            problems.append(f"{prefix}: 存在没有 goal 的部分")
        seeds = task.get("seeds", [])
        for spec in seeds:
            if not (DOC_DIR / spec["file"]).exists():
                problems.append(f"{prefix}: 种子文件不存在 {spec['file']}")
        texts = [part.get("goal", "") for part in parts]
        texts.append(task.get("userInput", ""))
        for text in texts:
            for match in PLACEHOLDER.finditer(text):
                if int(match.group(2)) >= len(seeds):
                    problems.append(f"{prefix}: 占位符越界 {match.group(0)}")
        for part in parts:
            scope = part.get("scope") or {}
            if scope.get("workspace") == "current" and scope.get("doc", 0) >= max(len(seeds), 1):
                problems.append(f"{prefix}: scope.doc 越界")
        if task.get("approval") not in (None, "confirm", "reject"):
            problems.append(f"{prefix}: approval 值非法 {task['approval']}")
        def walk(specs):
            for spec in specs:
                if spec.get("type") not in check_module.HANDLERS:
                    problems.append(f"{prefix}: 未知检查 {spec.get('type')}")
                if spec.get("type") == "any_of":
                    walk(spec.get("checks", []))
                if "doc" in spec and spec["doc"] >= len(seeds):
                    problems.append(f"{prefix}: 检查 doc 索引越界 {spec}")
        walk(task.get("checks", []))
    return problems


def selftest_fixture_mcp():
    server = ROOT / "fixture_mcp" / "server.py"
    process = subprocess.Popen(
        [sys.executable, str(server)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        encoding="utf-8", errors="replace")

    def call(payload):
        process.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        process.stdin.flush()
        if "id" not in payload:
            return None
        line = process.stdout.readline()
        return json.loads(line)

    problems = []
    try:
        init = call({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                     "params": {"protocolVersion": "2025-03-26",
                                "capabilities": {},
                                "clientInfo": {"name": "selftest", "version": "0"}}})
        if "result" not in init:
            problems.append(f"initialize 失败: {init}")
        call({"jsonrpc": "2.0", "method": "notifications/initialized"})
        tools = call({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in tools["result"]["tools"]}
        if names != {"brave_web_search", "fetch"}:
            problems.append(f"tools/list 返回 {names}")
        search = call({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                       "params": {"name": "brave_web_search",
                                  "arguments": {"query": "DocFlow 最新版本", "count": 5}}})
        search_text = search["result"]["content"][0]["text"]
        if "2.8.4" not in search_text:
            problems.append(f"搜索结果没有预埋版本号: {search_text[:120]}")
        fetch = call({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                      "params": {"name": "fetch",
                                 "arguments": {"url": "https://example.com/docflow-roadmap"}}})
        fetch_text = fetch["result"]["content"][0]["text"]
        if "离线编辑" not in fetch_text:
            problems.append(f"抓取结果没有预埋要点: {fetch_text[:120]}")
    except Exception as error:
        problems.append(f"fixture MCP 自检异常: {type(error).__name__}: {error}")
    finally:
        process.kill()
    return problems


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="docAgent 评测 harness")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default="eval_runner")
    parser.add_argument("--password", default="EvalRunner#2026")
    parser.add_argument("--email", default="eval_runner@example.com")
    parser.add_argument("--set", dest="sets", action="append",
                        choices=["smoke", "long", "conv", "live", "fixture-web"],
                        default=None)
    parser.add_argument("--task", dest="ids", action="append", default=None)
    parser.add_argument("--tags", dest="tags", action="append", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--k", type=int, default=1)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--label", default="")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    sets = args.sets or ["smoke"]
    tasks = load_tasks(sets, args.ids, args.tags)
    if not tasks:
        print("没有匹配的任务")
        return 1

    problems = validate_tasks(tasks)
    if args.dry_run:
        problems.extend(selftest_fixture_mcp())
        if problems:
            for problem in problems:
                print(f"[BAD] {problem}")
            return 1
        print(f"[OK] {len(tasks)} 个任务定义有效, fixture MCP 自检通过")
        return 0
    if problems:
        for problem in problems:
            print(f"[BAD] {problem}")
        return 1

    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    if args.label:
        run_id += f"-{args.label}"
    run_dir = RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    local = threading.local()
    worker_ids = itertools.count()
    login_lock = threading.Lock()

    def worker_client():
        if getattr(local, "client", None) is None:
            client = DocAgentClient(args.base_url)
            if args.workers <= 1:
                username, email = args.username, args.email
            else:
                with login_lock:
                    index = next(worker_ids)
                username = f"{args.username}_w{index}"
                email = f"{username}@example.com"
            client.ensure_login(username, args.password, email)
            local.client = client
        return local.client

    jobs = [(task, trial) for task in tasks for trial in range(1, args.k + 1)]
    records = []
    write_lock = threading.Lock()
    with (run_dir / "report.jsonl").open("w", encoding="utf-8") as report:
        def execute(job):
            task, trial = job
            record = run_one(worker_client(), task, args.model, run_dir, trial)
            with write_lock:
                records.append(record)
                report.write(json.dumps(record, ensure_ascii=False) + "\n")
                report.flush()
                mark = "PASS" if record.get("passed") else "FAIL"
                print(f"[{mark}] {task['id']} (trial {trial}) "
                      f"score={record.get('score', 0):.2f} "
                      f"{record.get('wallSeconds')}s", flush=True)

        if args.workers <= 1:
            for job in jobs:
                execute(job)
        else:
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                futures = [pool.submit(execute, job) for job in jobs]
                for future in as_completed(futures):
                    future.result()
    records.sort(key=lambda record: (record.get("set", ""), record.get("id", ""),
                                     record.get("trial", 0)))

    summary = summarize(records)
    git_sha = ""
    try:
        git_sha = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
            capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        pass
    summary_payload = {"runId": run_id, "gitSha": git_sha, "label": args.label,
                       "baseUrl": args.base_url, "model": args.model,
                       "k": args.k, "sets": sets, "summary": summary}
    (run_dir / "summary.json").write_text(
        json.dumps(summary_payload, ensure_ascii=False, indent=2),
        encoding="utf-8")
    print("")
    print_report(records, summary)
    print(f"\n报告: {run_dir}")
    if summary["safetyGate"] == "FAIL":
        return 2
    return 0 if all(record.get("passed") for record in records) else 1


if __name__ == "__main__":
    sys.exit(main())
