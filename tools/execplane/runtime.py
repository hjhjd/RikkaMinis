#!/usr/bin/env python3
"""Shared ExecPlane executor runtime: command, file RPC and safe roots."""
import asyncio, base64, hashlib, os, pathlib, shutil, stat, time
from transfer_runtime import TransferManager

CAPS={"exec","status","fs.stat","fs.list","fs.read","fs.write","fs.mkdir","fs.remove","fs.move","transfer.push","transfer.pull","env.inject"}
MAX_RPC=1024*1024

class RpcFault(Exception):
 def __init__(self,code,message): self.code,self.message=code,message; super().__init__(message)

class Runtime:
 def __init__(self,roots):
  self.roots=[pathlib.Path(r).expanduser().resolve() for r in roots]
  if not self.roots: raise ValueError("at least one --allow-root is required")
  self.transfers=TransferManager(self)

 def path(self,value,create=False):
  if not isinstance(value,str) or not value or "\0" in value: raise RpcFault("FS_INVALID_PATH","Invalid path")
  raw=pathlib.Path(value).expanduser()
  probe=raw if raw.exists() else raw.parent
  try: resolved=probe.resolve(strict=True)
  except (OSError,RuntimeError): raise RpcFault("FS_NOT_FOUND",f"Path not found: {value}")
  if not any(resolved==r or r in resolved.parents for r in self.roots): raise RpcFault("FS_PERMISSION_DENIED","Path is outside allowed roots")
  return raw

 async def exec(self,p):
  cmd=p.get("cmd"); timeout=min(max(int(p.get("timeoutMs",600000)),1000),3600000)/1000
  env=p.get("env",{}); safe={}
  if not isinstance(env,dict) or len(env)>128: raise RpcFault("EXEC_INVALID_PARAMS","Invalid environment")
  for k,v in env.items():
   if not isinstance(k,str) or not k.replace("_","a").isalnum() or k[0].isdigit() or not isinstance(v,str) or "\0" in v: raise RpcFault("EXEC_INVALID_PARAMS","Invalid environment")
   safe[k]=v
  child_env=os.environ.copy(); child_env.update(safe)
  if isinstance(cmd,list) and cmd: proc=await asyncio.create_subprocess_exec(*cmd,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,env=child_env)
  elif isinstance(cmd,str) and cmd.strip(): proc=await asyncio.create_subprocess_shell(cmd,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,env=child_env)
  else: raise RpcFault("EXEC_INVALID_PARAMS","Invalid command")
  started=time.monotonic()
  try: out,err=await asyncio.wait_for(proc.communicate(),timeout)
  except asyncio.TimeoutError: proc.kill(); await proc.wait(); raise RpcFault("EXEC_TIMEOUT","Command timed out")
  return {"stdout":out.decode(errors="replace"),"stderr":err.decode(errors="replace"),"exitCode":proc.returncode,"durationMs":int((time.monotonic()-started)*1000)}

 def revision(self,p):
  s=p.stat(); return hashlib.sha256(f"{s.st_dev}:{s.st_ino}:{s.st_size}:{s.st_mtime_ns}".encode()).hexdigest()

 async def dispatch(self,method,p):
  if method=="capabilities": return {"caps":sorted(CAPS)}
  if method=="exec": return await self.exec(p)
  if method in ("ping","status"): return {}
  if method=="transfer.open": return self.transfers.open(p)
  if method=="transfer.chunk": return self.transfers.chunk(p)
  if method=="transfer.commit": return self.transfers.commit(p)
  if method=="transfer.resume":
   t=self.transfers.items.get(p.get("transferId"))
   if not t: raise RpcFault("TRANSFER_INVALID_STATE","Unknown transfer")
   return self.transfers.info(t)
  if method=="transfer.abort": return self.transfers.abort(p.get("transferId"))
  path=self.path(p.get("path",""),method in ("fs.write","fs.mkdir")) if method.startswith("fs.") else None
  if method=="fs.stat":
   if not path.exists(): raise RpcFault("FS_NOT_FOUND","Path not found")
   s=path.stat(); kind="directory" if path.is_dir() else "file"
   return {"path":str(path),"type":kind,"size":s.st_size,"mtimeMs":s.st_mtime_ns//1000000,"revision":self.revision(path),"readable":os.access(path,os.R_OK),"writable":os.access(path,os.W_OK)}
  if method=="fs.list":
   if not path.is_dir(): raise RpcFault("FS_INVALID_PATH","Path is not a directory")
   limit=min(max(int(p.get("limit",200)),1),1000); start=max(int(p.get("cursor",0)),0); rows=sorted(path.iterdir(),key=lambda x:x.name)
   items=[{"name":x.name,"type":"directory" if x.is_dir() else "file","size":x.stat().st_size,"mtimeMs":x.stat().st_mtime_ns//1000000} for x in rows[start:start+limit]]
   return {"items":items,"nextCursor":start+len(items) if start+len(items)<len(rows) else None}
  if method=="fs.read":
   if not path.is_file(): raise RpcFault("FS_INVALID_PATH","Path is not a file")
   offset=max(int(p.get("offset",0)),0); count=min(max(int(p.get("maxBytes",MAX_RPC)),1),MAX_RPC)
   with path.open("rb") as f: f.seek(offset); data=f.read(count); more=bool(f.read(1))
   return {"data":base64.b64encode(data).decode(),"offset":offset,"nextOffset":offset+len(data) if more else None,"revision":self.revision(path)}
  if method=="fs.write":
   data=base64.b64decode(p.get("data",""),validate=True)
   if len(data)>MAX_RPC: raise RpcFault("TRANSFER_TOO_LARGE","Use transfer for writes over 1 MiB")
   expected=p.get("expectedRevision")
   if expected and path.exists() and self.revision(path)!=expected: raise RpcFault("FS_CONFLICT","File changed since it was read")
   path.parent.mkdir(parents=bool(p.get("createParents",False)),exist_ok=True)
   mode=p.get("createMode","replace")
   if mode=="create" and path.exists(): raise RpcFault("FS_CONFLICT","Path already exists")
   if mode=="append":
    with path.open("ab") as f: f.write(data)
   else:
    tmp=path.with_name(path.name+f".minis-{os.getpid()}.tmp"); tmp.write_bytes(data); os.replace(tmp,path)
   return {"size":path.stat().st_size,"revision":self.revision(path)}
  if method=="fs.mkdir": path.mkdir(parents=bool(p.get("parents",False)),exist_ok=bool(p.get("existOk",False))); return {}
  if method=="fs.remove":
   expected=p.get("expectedRevision")
   if expected and self.revision(path)!=expected: raise RpcFault("FS_CONFLICT","Path changed")
   if path.is_dir():
    if p.get("recursive",False): shutil.rmtree(path)
    else: path.rmdir()
   else: path.unlink()
   return {}
  if method=="fs.move":
   dest=self.path(p.get("destination",""),True)
   if dest.exists() and not p.get("overwrite",False): raise RpcFault("FS_CONFLICT","Destination exists")
   if dest.exists(): shutil.rmtree(dest) if dest.is_dir() else dest.unlink()
   dest.parent.mkdir(parents=True,exist_ok=True); os.replace(path,dest); return {"path":str(dest)}
  raise RpcFault("EXEC_METHOD_NOT_FOUND","Unknown method")
