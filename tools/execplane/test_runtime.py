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
 async def test_env_is_per_process(self):
  one=await self.r.dispatch("exec",{"cmd":"printf %s \"$MINIS_TEST_SECRET\"","env":{"MINIS_TEST_SECRET":"visible"}})
  two=await self.r.dispatch("exec",{"cmd":"printf %s \"${MINIS_TEST_SECRET-unset}\""})
  self.assertEqual(one["stdout"],"visible"); self.assertEqual(two["stdout"],"unset")

if __name__=="__main__": unittest.main()
