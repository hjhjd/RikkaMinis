#!/usr/bin/env python3
"""ExecPlane v0.2 runtime: argv execution, streaming, cancellation and safe file RPC."""
import asyncio, base64, hashlib, importlib.util, json, os, pathlib, shutil, signal, stat, time, uuid
from transfer_runtime import TransferManager, DIR_LIMIT

PROTOCOL_VERSION='0.2'
CAPS={"exec","cancel","status","fs.stat","fs.list","fs.read","fs.write","fs.mkdir","fs.remove","fs.move","transfer.push","transfer.pull","env.inject"}
MAX_RPC=1024*1024; MAX_PAYLOAD=256*1024; DEFAULT_STDOUT_LIMIT=16*1024*1024; DEFAULT_STDERR_LIMIT=8*1024*1024; DEFAULT_TOTAL_OUTPUT_LIMIT=20*1024*1024; DEFAULT_MAX_EXEC=256; TERMINATE_GRACE_SECONDS=1.0

def load_instruction_set(path):
 if not path:return None
 p=pathlib.Path(path);content=p.read_text(encoding='utf-8')
 if not content or len(content.encode())>MAX_PAYLOAD:raise ValueError('instruction set must be 1..262144 UTF-8 bytes')
 return {'title':p.stem,'revision':hashlib.sha256(content.encode()).hexdigest()[:16],'content':content,'updatedAt':int(p.stat().st_mtime*1000)}
def load_dispatch_handler(path):
 if not path:return None
 spec=importlib.util.spec_from_file_location('execplane_dispatch_plugin',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 handler=getattr(module,'dispatch',None)
 if not asyncio.iscoroutinefunction(handler):raise ValueError('dispatch plugin must export async def dispatch(payload, emit)')
 return handler

class RpcFault(Exception):
 def __init__(self,code,message): self.code,self.message=code,message; super().__init__(message)
class OutputLimitExceeded(Exception): pass

class Runtime:
 def __init__(self,roots,stdout_limit=DEFAULT_STDOUT_LIMIT,stderr_limit=DEFAULT_STDERR_LIMIT,total_output_limit=DEFAULT_TOTAL_OUTPUT_LIMIT,max_exec=DEFAULT_MAX_EXEC,transfer_options=None,name=None,instruction_set=None,dispatch_handler=None):
  self.roots=[pathlib.Path(r).expanduser().resolve(strict=True) for r in roots]
  if not self.roots: raise ValueError('at least one --allow-root is required')
  if min(stdout_limit,stderr_limit,total_output_limit,max_exec)<1: raise ValueError('runtime limits must be positive')
  self.stdout_limit=stdout_limit; self.stderr_limit=stderr_limit; self.total_output_limit=total_output_limit; self.max_exec=max_exec; self.name=name or os.uname().nodename; self.server_id=str(uuid.uuid4()); self.exec_slots=asyncio.Semaphore(max_exec); self.active={}; self.transfers=TransferManager(self,**(transfer_options or {})); self.instruction_set=instruction_set; self.dispatch_handler=dispatch_handler
 def capabilities(self):
  caps=set(CAPS);caps.update({'dispatch'} if self.dispatch_handler else set());out={'protocol':PROTOCOL_VERSION,'serverId':self.server_id,'name':self.name,'caps':sorted(caps),'limits':{'maxStdoutBytes':self.stdout_limit,'maxStderrBytes':self.stderr_limit,'maxTotalOutputBytes':self.total_output_limit,'maxTransferBytes':DIR_LIMIT,'maxConcurrentCommands':self.max_exec,'maxTimeoutMs':3600000}}
  if self.instruction_set:out['instructionSet']=self.instruction_set
  return out
 def inside_roots(self,resolved): return any(resolved==r or r in resolved.parents for r in self.roots)
 def lexical_root(self,path):
  absolute=pathlib.Path(os.path.abspath(path)); matches=[r for r in self.roots if absolute==r or r in absolute.parents]
  if not matches: raise RpcFault('FS_PERMISSION_DENIED','Path is outside allowed roots')
  return absolute,max(matches,key=lambda r:len(r.parts))
 def validate_parent(self,path,create=False):
  absolute,root=self.lexical_root(path)
  if absolute!=root:
   current=root
   for part in absolute.parent.relative_to(root).parts:
    current=current/part
    if current.is_symlink(): raise RpcFault('FS_PERMISSION_DENIED','Symlink parents are forbidden')
    if current.exists() and not current.is_dir(): raise RpcFault('FS_INVALID_PATH','Parent is not a directory')
  if not create and not path.exists() and not path.is_symlink(): raise RpcFault('FS_NOT_FOUND',f'Path not found: {path}')
  return path
 def path(self,value,create=False):
  if not isinstance(value,str) or not value or '\0' in value: raise RpcFault('FS_INVALID_PATH','Invalid path')
  raw=pathlib.Path(value).expanduser()
  if raw.is_symlink(): raise RpcFault('FS_PERMISSION_DENIED','Symlink paths are forbidden')
  self.validate_parent(raw,create=create)
  if raw.exists():
   try: resolved=raw.resolve(strict=True)
   except (OSError,RuntimeError): raise RpcFault('FS_NOT_FOUND',f'Path not found: {value}')
   if not self.inside_roots(resolved): raise RpcFault('FS_PERMISSION_DENIED','Path is outside allowed roots')
  return raw
 async def _terminate_group(self,proc):
  if proc.returncode is not None:return
  try: os.killpg(proc.pid,signal.SIGTERM)
  except ProcessLookupError:return
  try: await asyncio.wait_for(proc.wait(),TERMINATE_GRACE_SECONDS)
  except asyncio.TimeoutError:
   try: os.killpg(proc.pid,signal.SIGKILL)
   except ProcessLookupError:pass
   await proc.wait()
 async def _stream(self,stream,name,request_id,limit,total,emit,tail):
  size=0; sequence=0
  while True:
   block=await stream.read(64*1024)
   if not block:return size
   size+=len(block)
   async with total['lock']: total['size']+=len(block); combined=total['size']
   if size>limit or combined>self.total_output_limit: raise OutputLimitExceeded
   text=block.decode(errors='replace'); tail.append(text)
   if sum(map(len,tail))>256*1024: tail[:]=[''.join(tail)[-256*1024:]]
   if emit: await emit({'event':'exec.output','data':{'requestId':request_id,'sequence':sequence,'stream':name,'data':text}})
   sequence+=1
 async def exec(self,owner,request_id,p,emit=None):
  if self.exec_slots.locked(): raise RpcFault('EXEC_RESOURCE_LIMIT','Too many commands are running')
  async with self.exec_slots:
   key=(owner,request_id);task=asyncio.current_task();self.active[key]=task
   try:return await self._exec(request_id,p,emit)
   finally:self.active.pop(key,None)
 async def _exec(self,request_id,p,emit):
  cmd=p.get('cmd'); timeout=min(max(int(p.get('timeoutMs',600000)),1000),3600000)/1000; env=p.get('env',{}); cwd=p.get('cwd'); shell=bool(p.get('shell',False))
  if not isinstance(cmd,list) or not cmd or len(cmd)>256 or any(not isinstance(x,str) or not x or '\0' in x for x in cmd) or sum(map(len,cmd))>64*1024: raise RpcFault('EXEC_INVALID_PARAMS','Invalid command argv')
  if shell and cmd[:2]!=['/bin/sh','-lc']: raise RpcFault('EXEC_INVALID_PARAMS','Shell commands must use /bin/sh -lc')
  if p.get('envMode','overlay')!='overlay' or not isinstance(env,dict) or len(env)>128: raise RpcFault('EXEC_INVALID_PARAMS','Invalid environment')
  safe={}
  for k,v in env.items():
   if not isinstance(k,str) or not k.replace('_','a').isalnum() or k[0].isdigit() or not isinstance(v,str) or '\0' in v: raise RpcFault('EXEC_INVALID_PARAMS','Invalid environment')
   safe[k]=v
  if cwd is not None: cwd=str(self.path(cwd))
  child_env=os.environ.copy(); child_env.update(safe); proc=await asyncio.create_subprocess_exec(*cmd,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,env=child_env,cwd=cwd,start_new_session=True)
  started=time.monotonic(); total={'size':0,'lock':asyncio.Lock()}; out_tail=[]; err_tail=[]
  out=asyncio.create_task(self._stream(proc.stdout,'stdout',request_id,self.stdout_limit,total,emit,out_tail)); err=asyncio.create_task(self._stream(proc.stderr,'stderr',request_id,self.stderr_limit,total,emit,err_tail)); wait=asyncio.create_task(proc.wait())
  try:
   done,_=await asyncio.wait({out,err,wait},timeout=timeout,return_when=asyncio.FIRST_EXCEPTION)
   if not done: raise asyncio.TimeoutError
   for task in (out,err):
    if task.done() and task.exception(): raise task.exception()
   if not wait.done(): await asyncio.wait_for(wait,max(.001,timeout-(time.monotonic()-started)))
   stdout_bytes,stderr_bytes=await asyncio.gather(out,err)
  except OutputLimitExceeded: await self._terminate_group(proc); raise RpcFault('EXEC_OUTPUT_LIMIT','Command output exceeded the configured limit')
  except asyncio.TimeoutError: await self._terminate_group(proc); raise RpcFault('EXEC_TIMEOUT','Command timed out')
  except asyncio.CancelledError: await self._terminate_group(proc); raise RpcFault('EXEC_CANCELLED','Command was cancelled')
  finally:
   for task in (out,err,wait):
    if not task.done():task.cancel()
   await asyncio.gather(out,err,wait,return_exceptions=True)
  return {'stdout':''.join(out_tail),'stderr':''.join(err_tail),'exitCode':proc.returncode,'durationMs':int((time.monotonic()-started)*1000),'stdoutBytes':stdout_bytes,'stderrBytes':stderr_bytes,'truncated':False}
 async def opaque_dispatch(self,owner,request_id,p,emit=None):
  if not self.dispatch_handler:raise RpcFault('CAPABILITY_UNSUPPORTED','No dispatch handler is configured')
  payload=p.get('payload');timeout=min(max(int(p.get('timeoutMs',600000)),1000),3600000)
  if not isinstance(payload,str) or not payload or '\0' in payload or len(payload.encode())>MAX_PAYLOAD:raise RpcFault('EXEC_INVALID_PARAMS','Invalid dispatch payload')
  key=(owner,request_id);task=asyncio.current_task();self.active[key]=task;started=time.monotonic();state={'sequence':0,'bytes':0}
  async def output(data,stream='output'):
   if not isinstance(data,str):raise RpcFault('EXEC_INTERNAL','Dispatch handler emitted non-text output')
   size=len(data.encode());state['bytes']+=size
   if size>MAX_RPC or state['bytes']>self.total_output_limit:raise RpcFault('EXEC_OUTPUT_LIMIT','Dispatch output exceeded limit')
   if emit:await emit({'event':'dispatch.output','data':{'requestId':request_id,'sequence':state['sequence'],'stream':stream,'data':data}})
   state['sequence']+=1
  try:
   result=await asyncio.wait_for(self.dispatch_handler(payload,output),timeout/1000)
   if isinstance(result,str):result={'output':result}
   if not isinstance(result,dict):raise RpcFault('EXEC_INTERNAL','Dispatch handler returned invalid result')
   text=result.get('output','')
   if not isinstance(text,str) or len(text.encode())>MAX_RPC:raise RpcFault('EXEC_OUTPUT_LIMIT','Dispatch result exceeded limit')
   return {'output':text,'durationMs':int((time.monotonic()-started)*1000),'truncated':bool(result.get('truncated',False))}
  except asyncio.TimeoutError:raise RpcFault('EXEC_TIMEOUT','Dispatch timed out')
  except asyncio.CancelledError:raise RpcFault('EXEC_CANCELLED','Dispatch was cancelled')
  finally:self.active.pop(key,None)
 async def cancel(self,owner,request_id):
  task=self.active.get((owner,request_id))
  if not task:return {'requestId':request_id,'cancelled':False}
  task.cancel();return {'requestId':request_id,'cancelled':True}
 def revision(self,p):
  s=p.stat();return hashlib.sha256(f'{s.st_dev}:{s.st_ino}:{s.st_size}:{s.st_mtime_ns}'.encode()).hexdigest()
 async def dispatch(self,method,p,request_id=0,emit=None,owner='local'):
  if method=='capabilities':
   if p.get('protocol') not in (None,PROTOCOL_VERSION): raise RpcFault('EXEC_UNSUPPORTED_VERSION','Unsupported protocol version')
   return self.capabilities()
  if method=='dispatch':return await self.opaque_dispatch(owner,request_id,p,emit)
  if method=='exec':return await self.exec(owner,request_id,p,emit)
  if method=='cancel':return await self.cancel(owner,int(p.get('requestId',-1)))
  if method in ('ping','status'):return {}
  if method=='transfer.open':return self.transfers.open(p)
  if method=='transfer.chunk':return self.transfers.chunk(p)
  if method=='transfer.commit':return self.transfers.commit(p)
  if method=='transfer.resume':return self.transfers.resume(p.get('transferId'))
  if method=='transfer.abort':return self.transfers.abort(p.get('transferId'))
  path=self.path(p.get('path',''),method in ('fs.write','fs.mkdir')) if method.startswith('fs.') else None
  if method=='fs.stat':
   if not path.exists():raise RpcFault('FS_NOT_FOUND','Path not found')
   s=path.stat();kind='directory' if path.is_dir() else 'file';return {'path':str(path),'type':kind,'size':s.st_size,'mtimeMs':s.st_mtime_ns//1000000,'revision':self.revision(path),'readable':os.access(path,os.R_OK),'writable':os.access(path,os.W_OK)}
  if method=='fs.list':
   if not path.is_dir():raise RpcFault('FS_INVALID_PATH','Path is not a directory')
   limit=min(max(int(p.get('limit',200)),1),1000);start=max(int(p.get('cursor',0)),0);rows=sorted(path.iterdir(),key=lambda x:x.name);items=[]
   for x in rows[start:start+limit]:
    s=x.lstat();kind='symlink' if stat.S_ISLNK(s.st_mode) else ('directory' if stat.S_ISDIR(s.st_mode) else 'file');items.append({'name':x.name,'type':kind,'size':s.st_size,'mtimeMs':s.st_mtime_ns//1000000})
   return {'items':items,'nextCursor':start+len(items) if start+len(items)<len(rows) else None}
  if method=='fs.read':
   if not path.is_file():raise RpcFault('FS_INVALID_PATH','Path is not a file')
   offset=max(int(p.get('offset',0)),0);count=min(max(int(p.get('maxBytes',MAX_RPC)),1),MAX_RPC)
   with path.open('rb') as f:f.seek(offset);data=f.read(count);more=bool(f.read(1))
   return {'data':base64.b64encode(data).decode(),'offset':offset,'nextOffset':offset+len(data) if more else None,'revision':self.revision(path)}
  if method=='fs.write':
   data=base64.b64decode(p.get('data',''),validate=True)
   if len(data)>MAX_RPC:raise RpcFault('TRANSFER_TOO_LARGE','Use transfer for writes over 1 MiB')
   expected=p.get('expectedRevision')
   if expected and path.exists() and self.revision(path)!=expected:raise RpcFault('FS_CONFLICT','File changed since it was read')
   path.parent.mkdir(parents=bool(p.get('createParents',False)),exist_ok=True);mode=p.get('createMode','replace')
   if mode=='create' and path.exists():raise RpcFault('FS_CONFLICT','Path already exists')
   if mode=='append':
    with path.open('ab') as f:f.write(data)
   else:tmp=path.with_name(path.name+f'.minis-{os.getpid()}.tmp');tmp.write_bytes(data);os.replace(tmp,path)
   return {'size':path.stat().st_size,'revision':self.revision(path)}
  if method=='fs.mkdir':path.mkdir(parents=bool(p.get('parents',False)),exist_ok=bool(p.get('existOk',False)));return {}
  if method=='fs.remove':
   expected=p.get('expectedRevision')
   if expected and self.revision(path)!=expected:raise RpcFault('FS_CONFLICT','Path changed')
   if path.is_dir():shutil.rmtree(path) if p.get('recursive',False) else path.rmdir()
   else:path.unlink()
   return {}
  if method=='fs.move':
   dest=self.path(p.get('destination',''),True)
   if dest.exists() and not p.get('overwrite',False):raise RpcFault('FS_CONFLICT','Destination exists')
   if dest.exists():shutil.rmtree(dest) if dest.is_dir() else dest.unlink()
   dest.parent.mkdir(parents=True,exist_ok=True);os.replace(path,dest);return {'path':str(dest)}
  raise RpcFault('EXEC_METHOD_NOT_FOUND','Unknown method')
