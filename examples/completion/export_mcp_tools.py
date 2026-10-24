"""Export an external tool's MCP catalog without executing any tools."""

import argparse
import asyncio
import json
import sys
from pathlib import Path


async def export_catalog(command, timeout):
    process = await asyncio.create_subprocess_exec(
        *command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        limit=16 * 1024 * 1024,
    )
    request_id = 0

    async def send(message):
        process.stdin.write((json.dumps(message) + "\n").encode())
        await process.stdin.drain()

    async def request(method, params):
        nonlocal request_id
        request_id += 1
        current_id = request_id
        await send({"jsonrpc": "2.0", "id": current_id, "method": method, "params": params})
        async with asyncio.timeout(timeout):
            while True:
                line = await process.stdout.readline()
                if not line:
                    raise RuntimeError("MCP server closed stdout before replying")
                response = json.loads(line)
                if response.get("id") != current_id or "method" in response:
                    if "method" in response and "id" in response:
                        await send({
                            "jsonrpc": "2.0",
                            "id": response["id"],
                            "error": {"code": -32601, "message": "Client method unavailable"},
                        })
                    continue
                if "error" in response:
                    raise RuntimeError(f"MCP {method} failed: {response['error']}")
                return response["result"]

    try:
        await request("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "code-editor-catalog-export", "version": "1"},
        })
        await send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        tools = []
        cursor = None
        seen_cursors = set()
        while True:
            result = await request("tools/list", {} if cursor is None else {"cursor": cursor})
            tools.extend(result["tools"])
            cursor = result.get("nextCursor")
            if cursor is None:
                return {"tools": tools}
            if cursor in seen_cursors:
                raise RuntimeError("MCP server repeated a tools/list cursor")
            seen_cursors.add(cursor)
    finally:
        if process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=3)
            except TimeoutError:
                process.kill()
                await process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Write a catalog file instead of stdout")
    parser.add_argument("--timeout", type=float, default=10, help="Seconds allowed per MCP request")
    parser.add_argument("command", nargs=argparse.REMAINDER, help="MCP stdio server command and arguments")
    arguments = parser.parse_args()
    command = arguments.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command or arguments.timeout <= 0:
        parser.error("A server command and a positive timeout are required")
    try:
        catalog = asyncio.run(export_catalog(command, arguments.timeout))
        encoded = json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"
        if arguments.output:
            arguments.output.write_text(encoded, encoding="utf-8")
        else:
            sys.stdout.write(encoded)
    except (OSError, RuntimeError, TimeoutError, ValueError, KeyError) as error:
        parser.exit(1, f"Catalog export failed: {error}\n")


if __name__ == "__main__":
    main()
