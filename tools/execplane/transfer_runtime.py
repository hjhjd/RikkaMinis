"""Resumable ExecPlane transfers with bounded temporary storage."""
import asyncio, base64, hashlib, os, pathlib, shutil, tempfile, time, uuid, zipfile
from dataclasses import dataclass

CHUNK=256*1024; FILE_LIMIT=256*1024*1024; DIR_LIMIT=512*1024*1024
DEFAULT_TEMP_LIMIT=1024*1024*1024; DEFAULT_MAX_ACTIVE=8; DEFAULT_TTL=1800; DEFAULT_CLEAN_INTERVAL=300

@dataclass
class Transfer:
 id:str; direction:str; target:pathlib.Path; temp:pathlib.Path; kind:str; size:int; sha256:str; overwrite:str; next_seq:int=0; touched:float=0; reserved:int=0

class TransferManager:
 def __init__(self,runtime,temp_limit=DEFAULT_TEMP_LIMIT,max_active=DEFAULT_MAX_ACTIVE,ttl=DEFAULT_TTL,clean_interval=DEFAULT_CLEAN_INTERVAL):
  if temp_limit<1 or max_active<1 or ttl<=0 or clean_interval<=0: raise ValueError('transfer limits must be positive')
  self.runtime=runtime; self.items={}; self.temp_limit=temp_limit; self.max_active=max_active; self.ttl=ttl; self.clean_interval=clean_interval; self.cleanup_task=None
 def fault(self,code,msg):
  from runtime import RpcFault
  raise RpcFault(code,msg)
 def start(self):
  if self.cleanup_task is None or self.cleanup_task.done(): self.cleanup_task=asyncio.create_task(self._cleanup_loop())
 async def close(self):
  if self.cleanup_task:
   self.cleanup_task.cancel(); await asyncio.gather(self.cleanup_task,return_exceptions=True); self.cleanup_task=None
  for key in list(self.items): self.abort(key)
 async def _cleanup_loop(self):
  while True:
   await asyncio.sleep(self.clean_interval); self.clean()
 def clean(self,now=None):
  now=time.time() if now is None else now
  for key,t in list(self.items.items()):
   if now-t.touched>self.ttl: self.abort(key)
 def reserved_bytes(self): return sum(t.reserved for t in self.items.values())
 def reserve(self,size):
  if self.reserved_bytes()+size>self.temp_limit: self.fault('TRANSFER_TOO_LARGE','Transfer temporary storage quota exceeded')
 def digest(self,p):
  h=hashlib.sha256()
  with p.open('rb') as f:
   for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
  return h.hexdigest()
 def safe_zip(self,archive,dest):
  dest.mkdir(parents=True,exist_ok=True); root=dest.resolve()
  with zipfile.ZipFile(archive) as z:
   for info in z.infolist():
    name=info.filename
    if '\0' in name or name.startswith('/') or '..' in pathlib.PurePosixPath(name).parts: self.fault('FS_INVALID_PATH','Unsafe archive path')
    mode=(info.external_attr>>16)&0o170000
    if mode==0o120000: self.fault('FS_PERMISSION_DENIED','Archive symlinks are forbidden')
    out=(dest/name).resolve()
    if out!=root and root not in out.parents: self.fault('FS_PERMISSION_DENIED','Archive escapes destination')
   z.extractall(dest)
 def archive(self,source):
  fd,name=tempfile.mkstemp(prefix='.minis-pull-',suffix='.zip'); os.close(fd); out=pathlib.Path(name); total=0
  try:
   with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,allowZip64=True) as z:
    for p in source.rglob('*'):
     if p.is_symlink(): self.fault('FS_PERMISSION_DENIED','Directory symlinks are forbidden')
     relative=p.relative_to(source).as_posix()
     if p.is_dir(): z.writestr(relative.rstrip('/')+'/',b'')
     elif p.is_file():
      total+=p.stat().st_size
      if total>DIR_LIMIT: self.fault('TRANSFER_TOO_LARGE','Directory exceeds 512 MiB')
      z.write(p,relative)
   return out
  except BaseException:
   out.unlink(missing_ok=True); raise
 def open(self,p):
  self.clean(); tid=str(p.get('transferId') or uuid.uuid4()); direction=p.get('direction'); kind=p.get('type','file'); overwrite=p.get('overwrite','fail')
  if direction not in ('push','pull') or kind not in ('file','directory'): self.fault('TRANSFER_INVALID_STATE','Invalid transfer open')
  existing=self.items.get(tid)
  if existing: existing.touched=time.time(); return self.info(existing)
  if len(self.items)>=self.max_active: self.fault('TRANSFER_INVALID_STATE','Too many active transfers')
  path=self.runtime.path(p.get('path',''),direction=='push')
  if direction=='push':
   size=int(p.get('size',-1)); digest=p.get('sha256',''); limit=DIR_LIMIT if kind=='directory' else FILE_LIMIT
   if size<0 or size>limit or len(digest)!=64: self.fault('TRANSFER_TOO_LARGE','Invalid transfer size or digest')
   self.reserve(size)
   allowed={'fail','replace_directory'} if kind=='directory' else {'fail','replace_file'}
   if overwrite not in allowed: self.fault('TRANSFER_INVALID_STATE','Invalid overwrite policy for transfer type')
   if path.exists():
    if overwrite=='fail': self.fault('FS_CONFLICT','Destination exists')
    if kind=='file' and path.is_dir(): self.fault('FS_CONFLICT','Cannot replace directory with file')
    if kind=='directory' and not path.is_dir(): self.fault('FS_CONFLICT','Cannot replace file with directory')
   path.parent.mkdir(parents=True,exist_ok=True); temp=path.parent/(f'.{path.name}.minis-{tid}.part'); existing_size=temp.stat().st_size if temp.exists() else 0
   if existing_size>size: temp.unlink(); existing_size=0
   next_seq=existing_size//CHUNK; t=Transfer(tid,direction,path,temp,kind,size,digest,overwrite,next_seq,time.time(),size)
  else:
   if not path.exists(): self.fault('FS_NOT_FOUND','Source not found')
   if kind=='directory' and not path.is_dir(): self.fault('FS_INVALID_PATH','Source is not a directory')
   if kind=='file' and not path.is_file(): self.fault('FS_INVALID_PATH','Source is not a file')
   temp=self.archive(path) if kind=='directory' else path
   try:
    size=temp.stat().st_size; limit=DIR_LIMIT if kind=='directory' else FILE_LIMIT
    if size>limit: self.fault('TRANSFER_TOO_LARGE','Transfer exceeds limit')
    reserved=size if kind=='directory' else 0
    self.reserve(reserved); t=Transfer(tid,direction,path,temp,kind,size,self.digest(temp),overwrite,0,time.time(),reserved)
   except BaseException:
    if kind=='directory': temp.unlink(missing_ok=True)
    raise
  self.items[tid]=t; return self.info(t)
 def info(self,t): return {'transferId':t.id,'chunkSize':CHUNK,'size':t.size,'sha256':t.sha256,'nextSequence':t.next_seq,'type':t.kind,'archive':'zip' if t.kind=='directory' else None}
 def chunk(self,p):
  t=self.items.get(p.get('transferId')); seq=int(p.get('sequence',-1))
  if not t: self.fault('TRANSFER_INVALID_STATE','Unknown transfer')
  t.touched=time.time()
  if t.direction=='push':
   data=base64.b64decode(p.get('data',''),validate=True)
   if len(data)>CHUNK or hashlib.sha256(data).hexdigest()!=p.get('chunkSha256'): self.fault('TRANSFER_CHECKSUM_MISMATCH','Chunk checksum mismatch')
   if seq<t.next_seq: return {'nextSequence':t.next_seq}
   if seq!=t.next_seq: self.fault('TRANSFER_INVALID_STATE','Out of order chunk')
   with t.temp.open('ab') as f: f.write(data)
   if t.temp.stat().st_size>t.size: self.fault('TRANSFER_TOO_LARGE','Received too much data')
   t.next_seq+=1; return {'nextSequence':t.next_seq}
  if seq<0: self.fault('TRANSFER_INVALID_STATE','Invalid sequence')
  with t.temp.open('rb') as f: f.seek(seq*CHUNK); data=f.read(CHUNK)
  return {'data':base64.b64encode(data).decode(),'chunkSha256':hashlib.sha256(data).hexdigest(),'sequence':seq,'eof':(seq*CHUNK+len(data)>=t.size)}
 def commit(self,p):
  tid=p.get('transferId'); t=self.items.get(tid)
  if not t: self.fault('TRANSFER_INVALID_STATE','Unknown transfer')
  if t.direction=='push':
   if not t.temp.exists() or t.temp.stat().st_size!=t.size or self.digest(t.temp)!=t.sha256: self.fault('TRANSFER_CHECKSUM_MISMATCH','Final checksum mismatch')
   if t.target.exists():
    if t.overwrite=='fail': self.abort(tid); self.fault('FS_CONFLICT','Destination exists')
    if t.target.is_dir(): shutil.rmtree(t.target)
    else: t.target.unlink()
   if t.kind=='directory':
    stage=t.target.parent/(f'.{t.target.name}.minis-{tid}.dir'); shutil.rmtree(stage,ignore_errors=True); self.safe_zip(t.temp,stage); os.replace(stage,t.target); t.temp.unlink()
   else: os.replace(t.temp,t.target)
   result={'path':str(t.target),'size':t.size,'sha256':t.sha256,'type':t.kind}
  else: result={'path':str(t.target),'size':t.size,'sha256':t.sha256,'type':t.kind}
  if t.direction=='pull' and t.kind=='directory': t.temp.unlink(missing_ok=True)
  self.items.pop(tid,None); return result
 def abort(self,tid):
  t=self.items.pop(tid,None)
  if t and (t.direction=='push' or t.kind=='directory'): t.temp.unlink(missing_ok=True)
  return {}
