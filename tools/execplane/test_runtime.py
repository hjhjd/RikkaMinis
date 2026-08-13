import asyncio, base64, hashlib, importlib.util, json, os, pathlib, tempfile, time, unittest, zipfile
from unittest import mock
from runtime import Runtime, RpcFault, CAPS
_spec=importlib.util.spec_from_file_location('ws_agent',pathlib.Path(__file__).with_name('ws-agent.py')); _ws_agent=importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_ws_agent)
agent_process_request=_ws_agent.process_request

class RuntimeTest(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.tmp=tempfile.TemporaryDirectory(); self.root=pathlib.Path(self.tmp.name); self.r=Runtime([self.root])
 async def asyncTearDown(self): self.tmp.cleanup()
 async def test_capabilities_complete(self):
  result=await self.r.dispatch("capabilities",{"protocol":"0.2"})
  self.assertEqual(set(result["caps"]),CAPS); self.assertEqual(result['protocol'],'0.2'); self.assertGreaterEqual(result['limits']['maxConcurrentCommands'],1)
 async def test_server_id_is_stable_for_name_and_can_be_overridden(self):
  first=Runtime([self.root],name='box')
  second=Runtime([self.root],name='box')
  self.assertEqual(first.server_id,second.server_id)
  self.assertEqual(Runtime([self.root],name='box',server_id='configured').server_id,'configured')
 async def test_opaque_dispatch_and_instruction_set(self):
  async def handler(payload,emit):
   await emit('stream:'+payload);return {'output':'final:'+payload}
  instructions={'title':'Box AI','revision':'r1','content':'use echo'}
  runtime=Runtime([self.root],instruction_set=instructions,dispatch_handler=handler)
  caps=await runtime.dispatch('capabilities',{'protocol':'0.2'});self.assertIn('dispatch',caps['caps']);self.assertEqual(caps['instructionSet'],instructions)
  events=[]
  async def collect(event):events.append(event)
  result=await runtime.dispatch('dispatch',{'payload':'echo hello'},42,collect)
  self.assertEqual(result['output'],'final:echo hello');self.assertEqual(events[0]['event'],'dispatch.output');self.assertEqual(events[0]['data']['data'],'stream:echo hello')
 async def test_dispatch_not_advertised_without_plugin(self):
  caps=await self.r.dispatch('capabilities',{'protocol':'0.2'});self.assertNotIn('dispatch',caps['caps'])
  with self.assertRaises(RpcFault) as cm:await self.r.dispatch('dispatch',{'payload':'anything'})
  self.assertEqual(cm.exception.code,'CAPABILITY_UNSUPPORTED')
 async def test_write_read_revision_conflict(self):
  p=str(self.root/"a.txt"); data=base64.b64encode(b"hello").decode()
  w=await self.r.dispatch("fs.write",{"path":p,"data":data,"createParents":True})
  got=await self.r.dispatch("fs.read",{"path":p,"maxBytes":10})
  self.assertEqual(base64.b64decode(got["data"]),b"hello")
  (self.root/"a.txt").write_text("changed")
  with self.assertRaises(RpcFault) as cm: await self.r.dispatch("fs.write",{"path":p,"data":data,"expectedRevision":w["revision"]})
  self.assertEqual(cm.exception.code,"FS_CONFLICT")
 async def test_escape_rejected(self):
  outside=self.root.parent/"outside-minis-test"; outside.write_text("x")
  try:
   with self.assertRaises(RpcFault) as cm: await self.r.dispatch("fs.read",{"path":str(outside)})
   self.assertEqual(cm.exception.code,"FS_PERMISSION_DENIED")
  finally: outside.unlink(missing_ok=True)
 async def test_transfer_file_and_directory(self):
  import hashlib, zipfile
  data=b'x'*300000; target=self.root/'pushed.bin'
  opened=await self.r.dispatch('transfer.open',{'transferId':'f1','direction':'push','path':str(target),'type':'file','size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'overwrite':'fail'})
  for seq,start in enumerate(range(0,len(data),256*1024)):
   block=data[start:start+256*1024]
   await self.r.dispatch('transfer.chunk',{'transferId':'f1','sequence':seq,'data':base64.b64encode(block).decode(),'chunkSha256':hashlib.sha256(block).hexdigest()})
  await self.r.dispatch('transfer.commit',{'transferId':'f1'}); self.assertEqual(target.read_bytes(),data)
  folder=self.root/'folder'; folder.mkdir(); (folder/'a.txt').write_text('a'); (folder/'empty').mkdir()
  pull=await self.r.dispatch('transfer.open',{'transferId':'d1','direction':'pull','path':str(folder),'type':'directory','overwrite':'fail'})
  chunk=await self.r.dispatch('transfer.chunk',{'transferId':'d1','sequence':0})
  self.assertTrue(zipfile.is_zipfile(pathlib.Path(self.r.transfers.items['d1'].temp)))
  with zipfile.ZipFile(self.r.transfers.items['d1'].temp) as z: self.assertIn('empty/',z.namelist())
  self.assertEqual(hashlib.sha256(base64.b64decode(chunk['data'])).hexdigest(),chunk['chunkSha256'])
  await self.r.dispatch('transfer.abort',{'transferId':'d1'})
 async def test_env_is_per_process(self):
  one=await self.r.dispatch("exec",{"cmd":["/bin/sh","-lc","printf %s \"$MINIS_TEST_SECRET\""],"shell":True,"env":{"MINIS_TEST_SECRET":"visible"}})
  two=await self.r.dispatch("exec",{"cmd":["/bin/sh","-lc","printf %s \"${MINIS_TEST_SECRET-unset}\""],"shell":True})
  self.assertEqual(one["stdout"],"visible"); self.assertEqual(two["stdout"],"unset")
 async def test_output_limit_kills_command(self):
  limited=Runtime([self.root],stdout_limit=1024,stderr_limit=1024,total_output_limit=1500)
  with self.assertRaises(RpcFault) as cm: await limited.dispatch('exec',{'cmd':['python3','-c','import sys; sys.stdout.write("x"*4096)']})
  self.assertEqual(cm.exception.code,'EXEC_OUTPUT_LIMIT')
 async def test_timeout_kills_process_group(self):
  marker=self.root/'orphan-marker'
  command=f"(sleep 2; printf orphan > {marker}) & wait"
  with self.assertRaises(RpcFault) as cm: await self.r.dispatch('exec',{'cmd':['/bin/sh','-lc',command],'shell':True,'timeoutMs':1000})
  self.assertEqual(cm.exception.code,'EXEC_TIMEOUT'); await asyncio.sleep(2.2); self.assertFalse(marker.exists())
 async def test_exec_concurrency_limit_rejects_excess(self):
  limited=Runtime([self.root],max_exec=1)
  running=asyncio.create_task(limited.dispatch('exec',{'cmd':['sleep','0.3']})); await asyncio.sleep(0.05)
  with self.assertRaises(RpcFault) as cm: await limited.dispatch('exec',{'cmd':['true']})
  self.assertEqual(cm.exception.code,'EXEC_RESOURCE_LIMIT'); await running
 async def test_transfer_temp_quota_and_active_limit(self):
  limited=Runtime([self.root],transfer_options={'temp_limit':10,'max_active':1})
  digest=hashlib.sha256(b'x'*8).hexdigest()
  limited.transfers.open({'transferId':'one','direction':'push','path':str(self.root/'one'),'type':'file','size':8,'sha256':digest,'overwrite':'fail'})
  with self.assertRaises(RpcFault) as active: limited.transfers.open({'transferId':'two','direction':'push','path':str(self.root/'two'),'type':'file','size':1,'sha256':hashlib.sha256(b'x').hexdigest(),'overwrite':'fail'})
  self.assertEqual(active.exception.code,'TRANSFER_INVALID_STATE'); limited.transfers.abort('one')
  with self.assertRaises(RpcFault) as quota: limited.transfers.open({'transferId':'large','direction':'push','path':str(self.root/'large'),'type':'file','size':11,'sha256':hashlib.sha256(b'x'*11).hexdigest(),'overwrite':'fail'})
  self.assertEqual(quota.exception.code,'TRANSFER_TOO_LARGE')
 async def test_periodic_transfer_cleanup(self):
  limited=Runtime([self.root],transfer_options={'temp_limit':100,'max_active':2,'ttl':0.05,'clean_interval':0.02}); limited.transfers.start()
  data=b'x'; target=self.root/'stale'; limited.transfers.open({'transferId':'stale','direction':'push','path':str(target),'type':'file','size':1,'sha256':hashlib.sha256(data).hexdigest(),'overwrite':'fail'})
  t=limited.transfers.items['stale']; t.temp.write_bytes(data); await asyncio.sleep(0.12)
  self.assertNotIn('stale',limited.transfers.items); self.assertFalse(t.temp.exists()); await limited.transfers.close()
 async def test_directory_commit_is_idempotent_and_replaces_transactionally(self):
  target=self.root/'target'; target.mkdir(); (target/'old.txt').write_text('old')
  archive=self.root/'payload.zip'
  with zipfile.ZipFile(archive,'w') as z: z.writestr('new.txt','new')
  data=archive.read_bytes(); digest=hashlib.sha256(data).hexdigest(); tid='tx-dir'
  self.r.transfers.open({'transferId':tid,'direction':'push','path':str(target),'type':'directory','size':len(data),'sha256':digest,'overwrite':'replace_directory'})
  t=self.r.transfers.items[tid]; t.temp.write_bytes(data); t.next_seq=(len(data)+256*1024-1)//(256*1024)
  first=self.r.transfers.commit({'transferId':tid}); second=self.r.transfers.commit({'transferId':tid}); resumed=self.r.transfers.resume(tid)
  self.assertEqual(first,second); self.assertEqual(resumed['state'],'COMMITTED'); self.assertEqual((target/'new.txt').read_text(),'new'); self.assertFalse((target/'old.txt').exists())
 async def test_transaction_failure_restores_original_target(self):
  target=self.root/'target-file'; target.write_text('original'); data=b'replacement'; tid='rollback'
  self.r.transfers.open({'transferId':tid,'direction':'push','path':str(target),'type':'file','size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'overwrite':'replace_file'})
  t=self.r.transfers.items[tid]; t.temp.write_bytes(data)
  real_replace=os.replace
  def failing_replace(source,destination):
   if pathlib.Path(source)==t.temp: raise OSError('injected move failure')
   return real_replace(source,destination)
  with mock.patch('transfer_runtime.os.replace',side_effect=failing_replace):
   with self.assertRaises(OSError): self.r.transfers.commit({'transferId':tid})
  self.assertEqual(target.read_text(),'original'); self.assertTrue(t.temp.exists())
 async def test_abort_is_stable_and_commit_after_abort_fails(self):
  target=self.root/'abort-target'; data=b'x'; tid='abort-once'
  self.r.transfers.open({'transferId':tid,'direction':'push','path':str(target),'type':'file','size':1,'sha256':hashlib.sha256(data).hexdigest(),'overwrite':'fail'})
  self.r.transfers.abort(tid)
  with self.assertRaises(RpcFault) as second: self.r.transfers.abort(tid)
  self.assertEqual(second.exception.code,'TRANSFER_INVALID_STATE')
  with self.assertRaises(RpcFault) as commit: self.r.transfers.commit({'transferId':tid})
  self.assertEqual(commit.exception.code,'TRANSFER_INVALID_STATE')
 async def test_symlink_transfer_and_list_are_safe(self):
  outside=self.root.parent/'outside-link-target'; outside.write_text('secret'); link=self.root/'link'; link.symlink_to(outside)
  try:
   listing=await self.r.dispatch('fs.list',{'path':str(self.root)})
   item=next(x for x in listing['items'] if x['name']=='link'); self.assertEqual(item['type'],'symlink')
   with self.assertRaises(RpcFault) as cm: self.r.transfers.open({'transferId':'link','direction':'pull','path':str(link),'type':'file','overwrite':'fail'})
   self.assertEqual(cm.exception.code,'FS_PERMISSION_DENIED')
   linked_dir=self.root/'linked-dir'; linked_dir.symlink_to(self.root.parent,target_is_directory=True)
   with self.assertRaises(RpcFault) as middle: self.r.path(str(linked_dir/'escape.txt'),create=True)
   self.assertEqual(middle.exception.code,'FS_PERMISSION_DENIED'); linked_dir.unlink()
  finally: link.unlink(missing_ok=True); outside.unlink(missing_ok=True)
 async def test_create_parents_supports_multiple_new_levels(self):
  target=self.root/'one'/'two'/'three.txt'; data=base64.b64encode(b'ok').decode()
  await self.r.dispatch('fs.write',{'path':str(target),'data':data,'createParents':True})
  self.assertEqual(target.read_text(),'ok')
 async def test_streaming_output_and_cancel(self):
  events=[]
  async def emit(event): events.append(event)
  result=await self.r.dispatch('exec',{'cmd':['/bin/sh','-lc','printf one; printf two >&2'],'shell':True},77,emit)
  self.assertEqual({e['data']['stream'] for e in events},{'stdout','stderr'}); self.assertEqual(result['stdout'],'one'); self.assertEqual(result['stderr'],'two')
  running=asyncio.create_task(self.r.dispatch('exec',{'cmd':['sleep','30']},88,emit,'peer-a')); await asyncio.sleep(.05)
  foreign=await self.r.dispatch('cancel',{'requestId':88},98,emit,'peer-b'); self.assertFalse(foreign['cancelled'])
  cancelled=await self.r.dispatch('cancel',{'requestId':88},99,emit,'peer-a'); self.assertTrue(cancelled['cancelled'])
  with self.assertRaises(RpcFault) as cm: await running
  self.assertEqual(cm.exception.code,'EXEC_CANCELLED')
 async def test_single_connection_requests_run_concurrently(self):
  class FakeWs:
   def __init__(self): self.messages=[]
   async def send(self,value): self.messages.append(json.loads(value))
  ws=FakeWs(); lock=asyncio.Lock(); started=time.monotonic()
  first=asyncio.create_task(agent_process_request(ws,json.dumps({'id':1,'method':'exec','params':{'cmd':['/bin/sh','-lc','sleep 0.3; printf one'],'shell':True}}),self.r,lock))
  second=asyncio.create_task(agent_process_request(ws,json.dumps({'id':2,'method':'exec','params':{'cmd':['/bin/sh','-lc','sleep 0.3; printf two'],'shell':True}}),self.r,lock))
  await asyncio.gather(first,second); elapsed=time.monotonic()-started
  responses=[m for m in ws.messages if 'id' in m]
  self.assertLess(elapsed,0.55); self.assertEqual({m['id'] for m in responses},{1,2}); self.assertTrue(all(m['ok'] for m in responses)); self.assertTrue(any(m.get('event')=='exec.output' for m in ws.messages))

if __name__=="__main__": unittest.main()
