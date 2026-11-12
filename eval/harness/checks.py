from __future__ import annotations

import json
import re


def evaluate(specs, observed):
    results = []
    for spec in specs:
        handler = HANDLERS.get(spec.get("type"))
        if handler is None:
            results.append(_result(spec, False, f"未知检查类型: {spec.get('type')}"))
            continue
        try:
            passed, detail = handler(spec, observed)
        except Exception as error:
            passed, detail = False, f"{type(error).__name__}: {error}"
        results.append(_result(spec, passed, detail))
    return results


def _result(spec, passed, detail):
    return {"check": spec, "passed": bool(passed), "detail": str(detail)[:300]}


def _answer(observed):
    return observed.get("answer") or ""


def _seed(spec, observed):
    return observed["seeds"][spec["doc"]]


def _seed_after(spec, observed):
    seed = _seed(spec, observed)
    if seed["after"] is None:
        raise AssertionError(f"文档《{seed['title']}》已不存在")
    return seed["after"]


def _created(observed):
    return observed.get("created") or []


def check_task_status(spec, observed):
    status = (observed.get("task") or {}).get("status")
    allowed = spec.get("anyOf") or [spec.get("expect")]
    return status in allowed, f"status={status}, allowed={allowed}"


def check_answer_contains(spec, observed):
    text = _answer(observed)
    if "any" in spec:
        hit = next((item for item in spec["any"] if item in text), None)
        return hit is not None, f"any={spec['any']}, hit={hit}"
    return spec["text"] in text, f"回答未包含 {spec['text']!r}"


def check_answer_not_contains(spec, observed):
    return spec["text"] not in _answer(observed), f"回答不应包含 {spec['text']!r}"


def check_answer_regex(spec, observed):
    matched = re.search(spec["pattern"], _answer(observed), re.S) is not None
    return matched, f"pattern={spec['pattern']}"


def check_answer_not_regex(spec, observed):
    matched = re.search(spec["pattern"], _answer(observed), re.S) is not None
    return not matched, f"回答不应匹配 {spec['pattern']}"


def check_doc_contains(spec, observed):
    return spec["text"] in _seed_after(spec, observed), f"文档未包含 {spec['text']!r}"


def check_doc_not_contains(spec, observed):
    return spec["text"] not in _seed_after(spec, observed), f"文档不应包含 {spec['text']!r}"


def check_doc_regex(spec, observed):
    matched = re.search(spec["pattern"], _seed_after(spec, observed), re.S) is not None
    return matched, f"pattern={spec['pattern']}"


def check_doc_count(spec, observed):
    count = _seed_after(spec, observed).count(spec["text"])
    low = spec.get("min", 0)
    high = spec.get("max")
    ok = count >= low and (high is None or count <= high)
    return ok, f"{spec['text']!r} 出现 {count} 次, 允许 [{low}, {high}]"


def check_doc_unchanged(spec, observed):
    seed = _seed(spec, observed)
    if seed["after"] is None:
        return False, f"文档《{seed['title']}》被删除了"
    unchanged = seed["after"].rstrip("\n") == seed["before"].rstrip("\n")
    return unchanged, "文档内容发生了变化"


def check_doc_deleted(spec, observed):
    seed = _seed(spec, observed)
    return seed["after"] is None, f"文档《{seed['title']}》仍然存在"


def check_created_exists(spec, observed):
    docs = _created(observed)
    needle = spec.get("titleContains")
    if needle:
        docs = [doc for doc in docs if needle in (doc.get("title") or "")]
    return len(docs) > 0, f"新建文档数={len(docs)}, titleContains={needle}"


def check_created_count(spec, observed):
    count = len(_created(observed))
    return count == spec["expect"], f"新建文档数={count}, 期望 {spec['expect']}"


def check_created_contains(spec, observed):
    hit = any(spec["text"] in (doc.get("content") or "") for doc in _created(observed))
    return hit, f"新建文档均未包含 {spec['text']!r}"


def check_created_not_contains(spec, observed):
    hit = any(spec["text"] in (doc.get("content") or "") for doc in _created(observed))
    return not hit, f"新建文档不应包含 {spec['text']!r}"


def check_created_regex(spec, observed):
    hit = any(re.search(spec["pattern"], doc.get("content") or "", re.S)
              for doc in _created(observed))
    return hit, f"新建文档均未匹配 {spec['pattern']}"


def check_created_text_count(spec, observed):
    counts = [(doc.get("content") or "").count(spec["text"])
              for doc in _created(observed)]
    count = max(counts) if counts else 0
    low = spec.get("min", 1)
    high = spec.get("max")
    ok = count >= low and (high is None or count <= high)
    return ok, f"{spec['text']!r} 在新建文档中出现 {count} 次, 允许 [{low}, {high}]"


def check_created_length_range(spec, observed):
    lengths = [len(doc.get("content") or "") for doc in _created(observed)]
    if not lengths:
        return False, "没有新建文档"
    length = max(lengths)
    low = spec.get("min", 0)
    high = spec.get("max")
    ok = length >= low and (high is None or length <= high)
    return ok, f"最长新建文档 {length} 字符, 允许 [{low}, {high}]"


def check_tool_called(spec, observed):
    count = sum(1 for step in observed.get("steps", [])
                if step.get("toolName") == spec["name"])
    low = spec.get("min", 1)
    high = spec.get("max")
    ok = count >= low and (high is None or count <= high)
    return ok, f"{spec['name']} 调用 {count} 次, 允许 [{low}, {high}]"


def check_tool_not_called(spec, observed):
    count = sum(1 for step in observed.get("steps", [])
                if step.get("toolName") == spec["name"])
    return count == 0, f"{spec['name']} 被调用了 {count} 次"


def check_tool_success_count(spec, observed):
    count = sum(1 for step in observed.get("steps", [])
                if step.get("toolName") == spec["name"]
                and step.get("status") == "success")
    return count == spec["expect"], f"{spec['name']} 成功 {count} 次, 期望 {spec['expect']}"


def check_paused_for(spec, observed):
    kind = spec.get("kind", "APPROVAL")
    hit = any(entry.get("kind") == kind
              and entry.get("toolName") == spec["toolName"]
              for entry in observed.get("pauses", []))
    return hit, f"未观察到 {spec['toolName']} 的 {kind} 暂停"


def check_pause_count(spec, observed):
    count = observed.get("pauseEvents", 0)
    low = spec.get("min", 1)
    high = spec.get("max")
    ok = count >= low and (high is None or count <= high)
    return ok, f"暂停 {count} 次, 允许 [{low}, {high}]"


def check_iterations_max(spec, observed):
    iterations = observed.get("iterations") or 0
    return iterations <= spec["max"], f"iterations={iterations}, 上限 {spec['max']}"


def check_memory_contains(spec, observed):
    entries = observed.get("memory") or []
    hit = any(spec["text"] in json.dumps(entry, ensure_ascii=False)
              for entry in entries)
    return hit, f"记忆里没有 {spec['text']!r}"


def check_memory_not_contains(spec, observed):
    entries = observed.get("memory") or []
    hit = any(spec["text"] in json.dumps(entry, ensure_ascii=False)
              for entry in entries)
    return not hit, f"记忆里不应存在 {spec['text']!r}"


def check_any_of(spec, observed):
    sub = evaluate(spec["checks"], observed)
    passed = any(item["passed"] for item in sub)
    return passed, "; ".join(item["detail"] for item in sub)


HANDLERS = {
    "task_status": check_task_status,
    "answer_contains": check_answer_contains,
    "answer_not_contains": check_answer_not_contains,
    "answer_regex": check_answer_regex,
    "answer_not_regex": check_answer_not_regex,
    "doc_contains": check_doc_contains,
    "doc_not_contains": check_doc_not_contains,
    "doc_regex": check_doc_regex,
    "doc_count": check_doc_count,
    "doc_unchanged": check_doc_unchanged,
    "doc_deleted": check_doc_deleted,
    "created_exists": check_created_exists,
    "created_count": check_created_count,
    "created_contains": check_created_contains,
    "created_not_contains": check_created_not_contains,
    "created_regex": check_created_regex,
    "created_text_count": check_created_text_count,
    "created_length_range": check_created_length_range,
    "tool_called": check_tool_called,
    "tool_not_called": check_tool_not_called,
    "tool_success_count": check_tool_success_count,
    "paused_for": check_paused_for,
    "pause_count": check_pause_count,
    "iterations_max": check_iterations_max,
    "memory_contains": check_memory_contains,
    "memory_not_contains": check_memory_not_contains,
    "any_of": check_any_of,
}
