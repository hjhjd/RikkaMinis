#!/usr/bin/env python3
"""ExecPlane forward WebSocket server."""
import argparse, asyncio, json, os
from websockets.asyncio.server import serve
from runtime import Runtime, RpcFault

async def handler(ws,token,runtime):
 supplied=ws.request.headers.get("X-Minis-Token",""); auth=ws.request.headers.get("Authorization","")
 if supplied!=token and auth!="Bearer "+token: await ws.close(1008,"Unauthorized"); return
 async for raw in ws:
  rid=0
  try:
   if not isinstance(raw,str): raise RpcFault("EXEC_INVALID_REQUEST","Unexpected binary frame")
   req=json.loads(raw); rid=req["id"]; result=await runtime.dispatch(req.get("method",""),req.get("params",{})); reply={"id":rid,"ok":True,"result":result}
  except RpcFault as e: reply={"id":rid,"ok":False,"error":{"code":e.code,"message":e.message}}
  except Exception as e: reply={"id":rid,"ok":False,"error":{"code":"EXEC_INTERNAL","message":str(e)}}
  await ws.send(json.dumps(reply,separators=(",",":")))

async def main():
 ap=argparse.ArgumentParser(); ap.add_argument("--host",default="127.0.0.1"); ap.add_argument("--port",type=int,default=8766); ap.add_argument("--token",default=os.getenv("EXECPLANE_TOKEN")); ap.add_argument("--allow-root",action="append",required=True)
 a=ap.parse_args()
 if not a.token: ap.error("provide --token or EXECPLANE_TOKEN")
 runtime=Runtime(a.allow_root)
 async with serve(lambda ws:handler(ws,a.token,runtime),a.host,a.port,ping_interval=20,ping_timeout=30,max_size=2*1024*1024):
  print(f"ExecPlane listening on ws://{a.host}:{a.port}",flush=True); await asyncio.Future()
if __name__=="__main__": asyncio.run(main())
