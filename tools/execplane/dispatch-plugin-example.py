"""VCPMinis minimal opaque DSL plugin: help, status and exec.

Android forwards payload byte-for-byte and does not import or understand this
module. All command grammar, process execution and cancellation live here.
"""

import asyncio
import codecs
import os
import platform
import signal
import time
from collections import deque

from runtime import RpcFault

DSL_REVISION = "vcpminis-dsl-1"
MAX_FINAL_CHARS = 256 * 1024
TERMINATE_GRACE_SECONDS = 1.0
HELP = """VCPMinis DSL commands:
help
status
exec <shell script>
exec
<multiline shell script>

The first token is the DSL verb. For exec, everything after the first space or
line break is passed unchanged to /bin/sh -lc. Android never parses this text.
"""


def _parse(payload):
    for index, char in enumerate(payload):
        if char in " \t\r\n":
            return payload[:index], payload[index + 1 :]
    return payload, ""


async def _terminate_group(process):
    if process.returncode is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        await asyncio.wait_for(process.wait(), TERMINATE_GRACE_SECONDS)
    except asyncio.TimeoutError:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        await process.wait()


def _append_tail(tail, text, state):
    tail.append(text)
    state["chars"] += len(text)
    while state["chars"] > MAX_FINAL_CHARS and tail:
        state["truncated"] = True
        overflow = state["chars"] - MAX_FINAL_CHARS
        first = tail[0]
        if len(first) <= overflow:
            state["chars"] -= len(tail.popleft())
        else:
            tail[0] = first[overflow:]
            state["chars"] -= overflow


async def _read_stream(stream, name, emit, tail, state):
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    while True:
        block = await stream.read(16 * 1024)
        if not block:
            text = decoder.decode(b"", final=True)
            if text:
                _append_tail(tail, text, state)
                await emit(text, name)
            return
        text = decoder.decode(block)
        if text:
            _append_tail(tail, text, state)
            await emit(text, name)


async def _exec(script, emit):
    if not script or not script.strip():
        raise RpcFault("EXEC_INVALID_PARAMS", "exec requires a shell script body")
    process = await asyncio.create_subprocess_exec(
        "/bin/sh",
        "-lc",
        script,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        start_new_session=True,
    )
    started = time.monotonic()
    tail = deque()
    state = {"chars": 0, "truncated": False}
    stdout_task = asyncio.create_task(_read_stream(process.stdout, "stdout", emit, tail, state))
    stderr_task = asyncio.create_task(_read_stream(process.stderr, "stderr", emit, tail, state))
    wait_task = asyncio.create_task(process.wait())
    try:
        done, _ = await asyncio.wait(
            {stdout_task, stderr_task, wait_task},
            return_when=asyncio.FIRST_EXCEPTION,
        )
        for task in done:
            error = task.exception()
            if error is not None:
                raise error
        await wait_task
        await asyncio.gather(stdout_task, stderr_task)
    except BaseException:
        await _terminate_group(process)
        raise
    finally:
        for task in (stdout_task, stderr_task, wait_task):
            if not task.done():
                task.cancel()
        await asyncio.gather(stdout_task, stderr_task, wait_task, return_exceptions=True)
    footer = f"\n[exit={process.returncode} durationMs={int((time.monotonic() - started) * 1000)}]"
    _append_tail(tail, footer, state)
    return {"output": "".join(tail), "truncated": state["truncated"]}


async def dispatch(payload, emit):
    verb, body = _parse(payload)
    if verb == "help" and not body.strip():
        return {"output": HELP, "truncated": False}
    if verb == "status" and not body.strip():
        output = "\n".join(
            (
                f"dslRevision={DSL_REVISION}",
                f"cwd={os.getcwd()}",
                f"host={platform.node()}",
                f"pid={os.getpid()}",
                "commands=help,status,exec",
            )
        )
        return {"output": output, "truncated": False}
    if verb == "exec":
        return await _exec(body, emit)
    if verb in {"help", "status"}:
        raise RpcFault("EXEC_INVALID_PARAMS", f"{verb} does not accept a body")
    raise RpcFault("EXEC_METHOD_NOT_FOUND", f"Unknown DSL command: {verb or '<empty>'}")
