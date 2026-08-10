#!/usr/bin/env python3
"""ExecPlane reverse agent."""
import argparse, asyncio, json, os, platform, random, time
from websockets.asyncio.client import connect
from runtime import Runtime, RpcFault, CAPS

async def run(uri,token,name,runtime):
 delay=1
 while True:
  try:
   async with connect(uri,additional_headers={"X-Minis-Token":token},ping_interval=5,ping_timeout=15,max_size=2*1024*1024) as ws:
    params={"protocol":"0.1","name":name,"caps":sorted(CAPS),"resources":{"cpuCores":os.cpu_count(),"os":platform.system().lower(),"arch":platform.machine()},"trust":"RESTRICTED","tags":["reverse"]}
    await ws.send(json.dumps({"id":1,"method":"register","params":params,"ts":int(time.time()*1000)})); reply=json.loads(await ws.recv())
    if not reply.get("ok"): raise RuntimeError(str(reply))
    print("registered successfully",flush=True); delay=1
    async for raw in ws:
     rid=0
     try:
      if not isinstance(raw,str): raise RpcFault("EXEC_INVALID_REQUEST","Unexpected binary frame")
      req=json.loads(raw); rid=req.get("id",0); result=await runtime.dispatch(req.get("method",""),req.get("params",{})); response={"id":rid,"ok":True,"result":result}
     except RpcFault as e: response={"id":rid,"ok":False,"error":{"code":e.code,"message":e.message}}
     except Exception as e: response={"id":rid,"ok":False,"error":{"code":"EXEC_INTERNAL","message":str(e)}}
     await ws.send(json.dumps(response,separators=(",",":")))
  except asyncio.CancelledError: raise
  except Exception as e:
   wait=delay+random.random()*min(delay*.25,2); print(f"disconnected: {e}; retry in {wait:.1f}s",flush=True); await asyncio.sleep(wait); delay=min(delay*2,30)

async def main():
 ap=argparse.ArgumentParser(); ap.add_argument("--uri",default="ws://127.0.0.1:8765"); ap.add_argument("--token",default=os.getenv("EXECPLANE_TOKEN")); ap.add_argument("--name",default="proot-test"); ap.add_argument("--allow-root",action="append",required=True)
 a=ap.parse_args()
 if not a.token: ap.error("provide --token or EXECPLANE_TOKEN")
 await run(a.uri,a.token,a.name,Runtime(a.allow_root))
if __name__=="__main__": asyncio.run(main())
