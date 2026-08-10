import asyncio, base64, pathlib, tempfile, unittest
from runtime import Runtime, RpcFault, CAPS

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
  folder=self.root/'folder'; folder.mkdir(); (folder/'a.txt').write_text('a')
  pull=await self.r.dispatch('transfer.open',{'transferId':'d1','direction':'pull','path':str(folder),'type':'directory','overwrite':'fail'})
  chunk=await self.r.dispatch('transfer.chunk',{'transferId':'d1','sequence':0})
  self.assertTrue(zipfile.is_zipfile(pathlib.Path(self.r.transfers.items['d1'].temp)))
  self.assertEqual(hashlib.sha256(base64.b64decode(chunk['data'])).hexdigest(),chunk['chunkSha256'])
  await self.r.dispatch('transfer.abort',{'transferId':'d1'})
 async def test_env_is_per_process(self):
  one=await self.r.dispatch("exec",{"cmd":"printf %s \"$MINIS_TEST_SECRET\"","env":{"MINIS_TEST_SECRET":"visible"}})
  two=await self.r.dispatch("exec",{"cmd":"printf %s \"${MINIS_TEST_SECRET-unset}\""})
  self.assertEqual(one["stdout"],"visible"); self.assertEqual(two["stdout"],"unset")

if __name__=="__main__": unittest.main()
