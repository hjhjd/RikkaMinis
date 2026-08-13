#!/usr/bin/env python3
"""ExecPlane v0.2 forward WebSocket server."""
import argparse, asyncio, hmac, json, os
from websockets.asyncio.server import serve
from runtime import Runtime, RpcFault, load_instruction_set, load_dispatch_handler
async def send_json(ws,payload,lock):
 async with lock:await ws.send(json.dumps(payload,separators=(',',':')))
async def process_request(ws,raw,runtime,send_lock):
 rid=0
 async def emit(event):await send_json(ws,event,send_lock)
 try:
  if not isinstance(raw,str):raise RpcFault('EXEC_INVALID_REQUEST','Unexpected binary frame')
  req=json.loads(raw);rid=req['id'];result=await runtime.dispatch(req.get('method',''),req.get('params',{}),rid,emit,str(id(ws)));reply={'id':rid,'ok':True,'result':result}
 except asyncio.CancelledError:raise
 except RpcFault as e:reply={'id':rid,'ok':False,'error':{'code':e.code,'message':e.message}}
 except Exception as e:reply={'id':rid,'ok':False,'error':{'code':'EXEC_INTERNAL','message':str(e)}}
 await send_json(ws,reply,send_lock)
async def handler(ws,token,runtime,connections):
 supplied=ws.request.headers.get('X-Minis-Token','');auth=ws.request.headers.get('Authorization','');bearer=auth[7:] if auth.startswith('Bearer ') else ''
 if not(hmac.compare_digest(supplied,token) or hmac.compare_digest(bearer,token)):await ws.close(1008,'Unauthorized');return
 if connections.locked():await ws.close(1013,'Too many connections');return
 await connections.acquire();tasks=set();send_lock=asyncio.Lock()
 try:
  async for raw in ws:
   task=asyncio.create_task(process_request(ws,raw,runtime,send_lock));tasks.add(task);task.add_done_callback(tasks.discard)
 finally:
  for task in tasks:task.cancel()
  await asyncio.gather(*tasks,return_exceptions=True);connections.release()
async def main():
 ap=argparse.ArgumentParser();ap.add_argument('--host',default='127.0.0.1');ap.add_argument('--port',type=int,default=8766);ap.add_argument('--token',default=os.getenv('EXECPLANE_TOKEN'));ap.add_argument('--name',default='execplane-server');ap.add_argument('--server-id',default=os.getenv('EXECPLANE_SERVER_ID'));ap.add_argument('--allow-root',action='append',required=True);ap.add_argument('--max-connections',type=int,default=8);ap.add_argument('--max-exec',type=int,default=256);ap.add_argument('--temp-limit-mb',type=int,default=1024);ap.add_argument('--max-transfers',type=int,default=8);ap.add_argument('--instructions');ap.add_argument('--dispatch-plugin');a=ap.parse_args()
 if not a.token or len(a.token)<32:ap.error('provide a token of at least 32 characters')
 runtime=Runtime(a.allow_root,max_exec=a.max_exec,transfer_options={'temp_limit':a.temp_limit_mb*1024*1024,'max_active':a.max_transfers},name=a.name,instruction_set=load_instruction_set(a.instructions),dispatch_handler=load_dispatch_handler(a.dispatch_plugin),server_id=a.server_id);runtime.transfers.start();connections=asyncio.Semaphore(a.max_connections)
 try:
  async with serve(lambda ws:handler(ws,a.token,runtime,connections),a.host,a.port,ping_interval=20,ping_timeout=30,max_size=2*1024*1024):print(f'ExecPlane listening on ws://{a.host}:{a.port}',flush=True);await asyncio.Future()
 finally:await runtime.transfers.close()
if __name__=='__main__':asyncio.run(main())
