#!/usr/bin/env python3
"""ExecPlane v0.2 reverse agent with streaming and cancellation."""
import argparse, asyncio, json, os, platform, random, time
from websockets.asyncio.client import connect
from runtime import Runtime, RpcFault, CAPS, PROTOCOL_VERSION, load_instruction_set, load_dispatch_handler

async def send_json(ws,payload,lock):
 async with lock:await ws.send(json.dumps(payload,separators=(',',':')))
async def process_request(ws,raw,runtime,send_lock):
 rid=0
 async def emit(event):await send_json(ws,event,send_lock)
 try:
  if not isinstance(raw,str):raise RpcFault('EXEC_INVALID_REQUEST','Unexpected binary frame')
  req=json.loads(raw);rid=req.get('id',0);result=await runtime.dispatch(req.get('method',''),req.get('params',{}),rid,emit,'reverse-app');response={'id':rid,'ok':True,'result':result}
 except asyncio.CancelledError:raise
 except RpcFault as e:response={'id':rid,'ok':False,'error':{'code':e.code,'message':e.message}}
 except Exception as e:response={'id':rid,'ok':False,'error':{'code':'EXEC_INTERNAL','message':str(e)}}
 await send_json(ws,response,send_lock)
async def run(uri,token,name,runtime):
 delay=1;runtime.name=name;runtime.transfers.start()
 try:
  while True:
   tasks=set()
   try:
    async with connect(uri,additional_headers={'X-Minis-Token':token},ping_interval=5,ping_timeout=15,max_size=2*1024*1024) as ws:
     limits=runtime.capabilities()['limits'];params={'protocol':PROTOCOL_VERSION,'name':name,'serverId':runtime.server_id,'caps':sorted(runtime.capabilities()['caps']),'resources':{'cpuCores':os.cpu_count(),'os':platform.system().lower(),'arch':platform.machine()},'limits':limits,'trust':'RESTRICTED','tags':['reverse']};instruction_set=runtime.capabilities().get('instructionSet');params.update({'instructionSet':instruction_set} if instruction_set else {})
     await ws.send(json.dumps({'id':1,'method':'register','params':params,'ts':int(time.time()*1000)}));reply=json.loads(await ws.recv())
     if not reply.get('ok'):raise RuntimeError(str(reply))
     print('registered successfully',flush=True);delay=1;send_lock=asyncio.Lock()
     async for raw in ws:
      task=asyncio.create_task(process_request(ws,raw,runtime,send_lock));tasks.add(task);task.add_done_callback(tasks.discard)
   except asyncio.CancelledError:raise
   except Exception as e:
    wait=delay+random.random()*min(delay*.25,2);print(f'disconnected: {e}; retry in {wait:.1f}s',flush=True);await asyncio.sleep(wait);delay=min(delay*2,30)
   finally:
    for task in tasks:task.cancel()
    await asyncio.gather(*tasks,return_exceptions=True)
 finally:await runtime.transfers.close()
async def main():
 ap=argparse.ArgumentParser();ap.add_argument('--uri',default='ws://127.0.0.1:8765');ap.add_argument('--token',default=os.getenv('EXECPLANE_TOKEN'));ap.add_argument('--name',default='proot-test');ap.add_argument('--server-id',default=os.getenv('EXECPLANE_SERVER_ID'));ap.add_argument('--allow-root',action='append',required=True);ap.add_argument('--max-exec',type=int,default=256);ap.add_argument('--temp-limit-mb',type=int,default=1024);ap.add_argument('--max-transfers',type=int,default=8);ap.add_argument('--instructions');ap.add_argument('--dispatch-plugin');a=ap.parse_args()
 if not a.token or len(a.token)<32:ap.error('provide a token of at least 32 characters')
 await run(a.uri,a.token,a.name,Runtime(a.allow_root,max_exec=a.max_exec,transfer_options={'temp_limit':a.temp_limit_mb*1024*1024,'max_active':a.max_transfers},name=a.name,instruction_set=load_instruction_set(a.instructions),dispatch_handler=load_dispatch_handler(a.dispatch_plugin),server_id=a.server_id))
if __name__=='__main__':asyncio.run(main())
