#!/usr/bin/env python3
"""ExecPlane v0.1 forward WebSocket Server. App connects to this process."""
import argparse, asyncio, json, os, time
from websockets.asyncio.server import serve

async def execute(req):
    p = req.get("params", {})
    cmd = p.get("cmd")
    timeout = min(max(int(p.get("timeoutMs", 600000)), 1000), 3600000) / 1000
    if isinstance(cmd, list):
        proc = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    elif isinstance(cmd, str) and cmd.strip():
        proc = await asyncio.create_subprocess_shell(cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    else:
        raise ValueError("invalid cmd")
    started = time.monotonic()
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout)
    except asyncio.TimeoutError:
        proc.kill(); await proc.wait(); raise RuntimeError("command timed out")
    return {"stdout": out.decode(errors="replace"), "stderr": err.decode(errors="replace"),
            "exitCode": proc.returncode, "durationMs": int((time.monotonic()-started)*1000)}

async def handler(ws, token):
    supplied = ws.request.headers.get("X-Minis-Token", "")
    auth = ws.request.headers.get("Authorization", "")
    if supplied != token and auth != "Bearer " + token:
        await ws.close(1008, "Unauthorized"); return
    async for raw in ws:
        try:
            req = json.loads(raw); rid = req["id"]
            if req.get("method") == "exec":
                print(f"exec request id={rid}", flush=True)
                result = await execute(req)
                reply = {"id": rid, "ok": True, "result": result}
            elif req.get("method") in ("ping", "status"):
                reply = {"id": rid, "ok": True, "result": {}}
            else:
                reply = {"id": rid, "ok": False, "error": {"code":"EXEC_METHOD_NOT_FOUND","message":"Unknown method"}}
        except Exception as exc:
            reply = {"id": locals().get("rid", 0), "ok": False,
                     "error": {"code":"EXEC_FAILED","message":str(exc)}}
        await ws.send(json.dumps(reply, separators=(",", ":")))

async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("--token", default=os.getenv("EXECPLANE_TOKEN"))
    args = ap.parse_args()
    if not args.token: ap.error("provide --token or EXECPLANE_TOKEN")
    async with serve(lambda ws: handler(ws, args.token), args.host, args.port, ping_interval=20, ping_timeout=30):
        print(f"ExecPlane WebSocket Server listening on ws://{args.host}:{args.port}", flush=True)
        await asyncio.Future()

if __name__ == "__main__": asyncio.run(main())
