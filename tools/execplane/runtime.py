#!/usr/bin/env python3
"""Shared ExecPlane executor runtime: bounded commands, file RPC and safe roots."""
import asyncio, base64, hashlib, os, pathlib, shutil, signal, time
from transfer_runtime import TransferManager

CAPS={"exec","status","fs.stat","fs.list","fs.read","fs.write","fs.mkdir","fs.remove","fs.move","transfer.push","transfer.pull","env.inject"}
MAX_RPC=1024*1024
DEFAULT_STDOUT_LIMIT=16*1024*1024
DEFAULT_STDERR_LIMIT=8*1024*1024
DEFAULT_TOTAL_OUTPUT_LIMIT=20*1024*1024
DEFAULT_MAX_EXEC=256
TERMINATE_GRACE_SECONDS=1.0

class RpcFault(Exception):
 def __init__(self,code,message): self.code,self.message=code,message; super().__init__(message)

class OutputLimitExceeded(Exception): pass

class Runtime:
 def __init__(self,roots,stdout_limit=DEFAULT_STDOUT_LIMIT,stderr_limit=DEFAULT_STDERR_LIMIT,total_output_limit=DEFAULT_TOTAL_OUTPUT_LIMIT,max_exec=DEFAULT_MAX_EXEC,transfer_options=None):
  self.roots=[pathlib.Path(r).expanduser().resolve(strict=True) for r in roots]
  if not self.roots: raise ValueError("at least one --allow-root is required")
  if min(stdout_limit,stderr_limit,total_output_limit,max_exec)<1: raise ValueError("runtime limits must be positive")
  self.stdout_limit=stdout_limit; self.stderr_limit=stderr_limit; self.total_output_limit=total_output_limit
  self.exec_slots=asyncio.Semaphore(max_exec)
  self.transfers=TransferManager(self,**(transfer_options or {}))

 def path(self,value,create=False):
  if not isinstance(value,str) or not value or "\0" in value: raise RpcFault("FS_INVALID_PATH","Invalid path")
  raw=pathlib.Path(value).expanduser(); probe=raw if raw.exists() else raw.parent
  try: resolved=probe.resolve(strict=True)
  except (OSError,RuntimeError): raise RpcFault("FS_NOT_FOUND",f"Path not found: {value}")
  if not any(resolved==r or r in resolved.parents for r in self.roots): raise RpcFault("FS_PERMISSION_DENIED","Path is outside allowed roots")
  return raw

 async def _read_stream(self,stream,limit,total):
  chunks=[]; size=0
  while True:
   block=await stream.read(64*1024)
   if not block: return b''.join(chunks)
   size+=len(block)
   async with total['lock']:
    total['size']+=len(block); combined=total['size']
   if size>limit or combined>self.total_output_limit: raise OutputLimitExceeded
   chunks.append(block)

 async def _terminate_group(self,proc):
  if proc.returncode is not None: return
  try: os.killpg(proc.pid,signal.SIGTERM)
  except ProcessLookupError: return
  try: await asyncio.wait_for(proc.wait(),TERMINATE_GRACE_SECONDS)
  except asyncio.TimeoutError:
   try: os.killpg(proc.pid,signal.SIGKILL)
   except ProcessLookupError: pass
   await proc.wait()

 async def exec(self,p):
  if self.exec_slots.locked(): raise RpcFault("EXEC_RESOURCE_LIMIT","Too many commands are running")
  async with self.exec_slots: return await self._exec_bounded(p)

 async def _exec_bounded(self,p):
  cmd=p.get("cmd"); timeout=min(max(int(p.get("timeoutMs",600000)),1000),3600000)/1000
  env=p.get("env",{}); safe={}
  if not isinstance(env,dict) or len(env)>128: raise RpcFault("EXEC_INVALID_PARAMS","Invalid environment")
  for k,v in env.items():
   if not isinstance(k,str) or not k.replace("_","a").isalnum() or k[0].isdigit() or not isinstance(v,str) or "\0" in v: raise RpcFault("EXEC_INVALID_PARAMS","Invalid environment")
   safe[k]=v
  child_env=os.environ.copy(); child_env.update(safe)
  common=dict(stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,env=child_env,start_new_session=True)
  if isinstance(cmd,list) and cmd: proc=await asyncio.create_subprocess_exec(*cmd,**common)
  elif isinstance(cmd,str) and cmd.strip(): proc=await asyncio.create_subprocess_shell(cmd,**common)
  else: raise RpcFault("EXEC_INVALID_PARAMS","Invalid command")
  started=time.monotonic(); total={'size':0,'lock':asyncio.Lock()}
  out_task=asyncio.create_task(self._read_stream(proc.stdout,self.stdout_limit,total)); err_task=asyncio.create_task(self._read_stream(proc.stderr,self.stderr_limit,total)); wait_task=asyncio.create_task(proc.wait())
  try:
   done,_=await asyncio.wait({out_task,err_task,wait_task},timeout=timeout,return_when=asyncio.FIRST_EXCEPTION)
   if not done: raise asyncio.TimeoutError
   for task in (out_task,err_task):
    if task.done() and task.exception(): raise task.exception()
   if not wait_task.done(): await asyncio.wait_for(wait_task,max(0.001,timeout-(time.monotonic()-started)))
   out,err=await asyncio.gather(out_task,err_task)
  except OutputLimitExceeded:
   await self._terminate_group(proc); raise RpcFault("EXEC_OUTPUT_LIMIT","Command output exceeded the configured limit")
  except asyncio.TimeoutError:
   await self._terminate_group(proc); raise RpcFault("EXEC_TIMEOUT","Command timed out")
  except asyncio.CancelledError:
   await self._terminate_group(proc); raise
  finally:
   for task in (out_task,err_task,wait_task):
    if not task.done(): task.cancel()
   await asyncio.gather(out_task,err_task,wait_task,return_exceptions=True)
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
   path.parent.mkdir(parents=bool(p.get("createParents",False)),exist_ok=True); mode=p.get("createMode","replace")
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
   if path.is_dir(): shutil.rmtree(path) if p.get("recursive",False) else path.rmdir()
   else: path.unlink()
   return {}
  if method=="fs.move":
   dest=self.path(p.get("destination",""),True)
   if dest.exists() and not p.get("overwrite",False): raise RpcFault("FS_CONFLICT","Destination exists")
   if dest.exists(): shutil.rmtree(dest) if dest.is_dir() else dest.unlink()
   dest.parent.mkdir(parents=True,exist_ok=True); os.replace(path,dest); return {"path":str(dest)}
  raise RpcFault("EXEC_METHOD_NOT_FOUND","Unknown method")
