from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SEARCH_TOOL = {
    "name": "brave_web_search",
    "description": "Web search (fixture).",
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string"},
            "count": {"type": "number"},
        },
        "required": ["query"],
    },
}

FETCH_TOOL = {
    "name": "fetch",
    "description": "Fetch a URL as text (fixture).",
    "inputSchema": {
        "type": "object",
        "properties": {
            "url": {"type": "string"},
            "max_length": {"type": "number"},
            "start_index": {"type": "number"},
        },
        "required": ["url"],
    },
}


def load_fixtures(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def search_result(fixtures, arguments):
    query = arguments.get("query") or ""
    for entry in fixtures.get("search", []):
        if all(keyword in query for keyword in entry["match"]):
            return entry["result"]
    return fixtures.get("searchDefault", "没有找到相关结果。")


def fetch_result(fixtures, arguments):
    url = arguments.get("url") or ""
    text = fixtures.get("fetch", {}).get(url)
    if text is None:
        text = fixtures.get("fetchDefault", f"无法访问 {url}")
    start = int(arguments.get("start_index") or 0)
    limit = int(arguments.get("max_length") or 8000)
    return text[start:start + limit]


def handle(fixtures, message):
    method = message.get("method")
    if method == "initialize":
        version = (message.get("params") or {}).get("protocolVersion", "2024-11-05")
        return {
            "protocolVersion": version,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "docagent-eval-fixture", "version": "1.0.0"},
        }
    if method == "tools/list":
        return {"tools": [SEARCH_TOOL, FETCH_TOOL]}
    if method == "tools/call":
        params = message.get("params") or {}
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if name == "brave_web_search":
            text = search_result(fixtures, arguments)
        elif name == "fetch":
            text = fetch_result(fixtures, arguments)
        else:
            return {"content": [{"type": "text", "text": f"unknown tool {name}"}],
                    "isError": True}
        return {"content": [{"type": "text", "text": text}], "isError": False}
    if method == "ping":
        return {}
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures",
                        default=str(Path(__file__).with_name("fixtures.json")))
    args = parser.parse_args()
    fixtures = load_fixtures(args.fixtures)

    stdin = sys.stdin
    stdout = sys.stdout
    if hasattr(stdin, "reconfigure"):
        stdin.reconfigure(encoding="utf-8", errors="replace")
        stdout.reconfigure(encoding="utf-8")

    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "id" not in message:
            continue
        result = handle(fixtures, message)
        if result is None:
            response = {"jsonrpc": "2.0", "id": message["id"],
                        "error": {"code": -32601,
                                  "message": f"method not found: {message.get('method')}"}}
        else:
            response = {"jsonrpc": "2.0", "id": message["id"], "result": result}
        stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        stdout.flush()


if __name__ == "__main__":
    main()
