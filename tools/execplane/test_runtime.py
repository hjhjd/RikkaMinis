import asyncio, base64, hashlib, importlib.util, json, os, pathlib, tempfile, time, unittest
from runtime import Runtime, RpcFault, CAPS
_spec=importlib.util.spec_from_file_location('ws_agent',pathlib.Path(__file__).with_name('ws-agent.py')); _ws_agent=importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_ws_agent)
agent_process_request=_ws_agent.process_request

class RuntimeTest(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.tmp=tempfile.TemporaryDirectory(); self.root=pathlib.Path(self.tmp.name); self.r=Runtime([self.root])
 async def asyncTearDown(self): self.tmp.cleanup()
 async def test_capabilities_complete(self):
  self.assertEqual(set((await self.r.dispatch("capabilities",{}))["caps"]),CAPS)
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
  one=await self.r.dispatch("exec",{"cmd":"printf %s \"$MINIS_TEST_SECRET\"","env":{"MINIS_TEST_SECRET":"visible"}})
  two=await self.r.dispatch("exec",{"cmd":"printf %s \"${MINIS_TEST_SECRET-unset}\""})
  self.assertEqual(one["stdout"],"visible"); self.assertEqual(two["stdout"],"unset")
 async def test_output_limit_kills_command(self):
  limited=Runtime([self.root],stdout_limit=1024,stderr_limit=1024,total_output_limit=1500)
  with self.assertRaises(RpcFault) as cm: await limited.dispatch('exec',{'cmd':['python3','-c','import sys; sys.stdout.write("x"*4096)']})
  self.assertEqual(cm.exception.code,'EXEC_OUTPUT_LIMIT')
 async def test_timeout_kills_process_group(self):
  marker=self.root/'orphan-marker'
  command=f"(sleep 2; printf orphan > {marker}) & wait"
  with self.assertRaises(RpcFault) as cm: await self.r.dispatch('exec',{'cmd':command,'timeoutMs':1000})
  self.assertEqual(cm.exception.code,'EXEC_TIMEOUT'); await asyncio.sleep(2.2); self.assertFalse(marker.exists())
 async def test_exec_concurrency_limit_rejects_excess(self):
  limited=Runtime([self.root],max_exec=1)
  running=asyncio.create_task(limited.dispatch('exec',{'cmd':'sleep 0.3'})); await asyncio.sleep(0.05)
  with self.assertRaises(RpcFault) as cm: await limited.dispatch('exec',{'cmd':'true'})
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
 async def test_single_connection_requests_run_concurrently(self):
  class FakeWs:
   def __init__(self): self.messages=[]
   async def send(self,value): self.messages.append(json.loads(value))
  ws=FakeWs(); lock=asyncio.Lock(); started=time.monotonic()
  first=asyncio.create_task(agent_process_request(ws,json.dumps({'id':1,'method':'exec','params':{'cmd':'sleep 0.3; printf one'}}),self.r,lock))
  second=asyncio.create_task(agent_process_request(ws,json.dumps({'id':2,'method':'exec','params':{'cmd':'sleep 0.3; printf two'}}),self.r,lock))
  await asyncio.gather(first,second); elapsed=time.monotonic()-started
  self.assertLess(elapsed,0.55); self.assertEqual({m['id'] for m in ws.messages},{1,2}); self.assertTrue(all(m['ok'] for m in ws.messages))

if __name__=="__main__": unittest.main()
