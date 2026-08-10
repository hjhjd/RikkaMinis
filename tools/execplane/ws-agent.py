#!/usr/bin/env python3
"""ExecPlane v0.1 reverse agent. Connects to App and handles exec."""
import argparse, asyncio, json, os, platform, time
from websockets.asyncio.client import connect

async def execute(req):
    p=req.get("params",{}); cmd=p.get("cmd"); timeout=min(max(int(p.get("timeoutMs",600000)),1000),3600000)/1000
    if isinstance(cmd,list): proc=await asyncio.create_subprocess_exec(*cmd,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
    elif isinstance(cmd,str) and cmd.strip(): proc=await asyncio.create_subprocess_shell(cmd,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
    else: raise ValueError("invalid cmd")
    started=time.monotonic()
    try: out,err=await asyncio.wait_for(proc.communicate(),timeout)
    except asyncio.TimeoutError: proc.kill(); await proc.wait(); raise RuntimeError("command timed out")
    return {"stdout":out.decode(errors="replace"),"stderr":err.decode(errors="replace"),"exitCode":proc.returncode,"durationMs":int((time.monotonic()-started)*1000)}

async def run(uri,token,name):
    delay=1; register_id=1
    while True:
        try:
            async with connect(uri,additional_headers={"X-Minis-Token":token},ping_interval=5,ping_timeout=15) as ws:
                params={"protocol":"0.1","name":name,"caps":["exec","status"],"resources":{"cpuCores":os.cpu_count(),"os":platform.system().lower(),"arch":platform.machine()},"trust":"RESTRICTED","tags":["reverse"]}
                await ws.send(json.dumps({"id":register_id,"method":"register","params":params,"ts":int(time.time()*1000)}))
                reply=json.loads(await ws.recv())
                if not reply.get("ok"): raise RuntimeError(str(reply))
                print("registered successfully",flush=True); delay=1
                async for raw in ws:
                    req=json.loads(raw); rid=req.get("id",0)
                    try:
                        if req.get("method")=="exec": response={"id":rid,"ok":True,"result":await execute(req)}
                        else: response={"id":rid,"ok":False,"error":{"code":"EXEC_METHOD_NOT_FOUND","message":"Unknown method"}}
                    except Exception as exc: response={"id":rid,"ok":False,"error":{"code":"EXEC_FAILED","message":str(exc)}}
                    await ws.send(json.dumps(response,separators=(",",":")))
        except asyncio.CancelledError: raise
        except Exception as exc:
            print(f"disconnected: {exc}; retry in {delay}s",flush=True); await asyncio.sleep(delay); delay=min(delay*2,30)

async def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--uri",default="ws://127.0.0.1:8765"); ap.add_argument("--token",default=os.getenv("EXECPLANE_TOKEN")); ap.add_argument("--name",default="proot-test")
    args=ap.parse_args()
    if not args.token: ap.error("provide --token or EXECPLANE_TOKEN")
    await run(args.uri,args.token,args.name)
if __name__=="__main__": asyncio.run(main())
