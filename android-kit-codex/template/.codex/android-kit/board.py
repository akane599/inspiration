#!/usr/bin/env python3
"""Project-local Codex task ledger. Not the Sidequest database or dispatcher."""
import argparse, hashlib, json, os, signal, sqlite3, subprocess, sys, time
from pathlib import Path
ROOT=Path.cwd().resolve(); DATA=ROOT/'.codex/android-kit/runtime'; DB=DATA/'board.sqlite3'
def fingerprint():
 def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT,stderr=subprocess.DEVNULL)
 head=git('rev-parse','HEAD').strip()
 names=set(git('ls-files','-z').split(b'\0')) | set(git('ls-files','--others','--exclude-standard','-z').split(b'\0'))
 h=hashlib.sha256(head)
 # The shared index is part of the evidence; staging changes invalidates validation too.
 h.update(git('diff','--cached','--binary','HEAD'))
 for raw in sorted(names):
  if not raw: continue
  name=raw.decode('utf-8','surrogateescape')
  if name.startswith(('.codex/android-kit/runtime/','.codex/android-kit/logs/')): continue
  p=ROOT/name
  h.update(raw)
  if p.is_symlink(): h.update(str(p.readlink()).encode())
  elif p.is_file(): h.update(p.read_bytes()); h.update(str(p.stat().st_mode).encode())
  else: h.update(b'<absent>')
 return h.hexdigest()
def main():
 ap=argparse.ArgumentParser(description=__doc__); sub=ap.add_subparsers(dest='action',required=True)
 a=sub.add_parser('add'); a.add_argument('title'); a.add_argument('--description',required=True); a.add_argument('--files',nargs='*',default=[]); a.add_argument('--depends',nargs='*',type=int,default=[]); a.add_argument('--verify-json',required=True,help='JSON argv array, no shell expansion')
 sub.add_parser('list')
 for action in ['start','finish','verify','block']:
  p=sub.add_parser(action); p.add_argument('id',type=int)
  if action=='block': p.add_argument('--reason',required=True)
  if action=='verify': p.add_argument('--timeout',type=int,default=900)
 args=ap.parse_args()
 for path in [DB, DATA, *DATA.parents]:
  if path == ROOT: break
  if path.is_symlink(): raise ValueError("refusing symlink in board storage")
 DATA.mkdir(parents=True,exist_ok=True)
 con=sqlite3.connect(DB,timeout=15); con.row_factory=sqlite3.Row
 con.execute('CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY,title TEXT,description TEXT,files TEXT,deps TEXT,command TEXT,status TEXT,fingerprint TEXT,evidence TEXT)')
 if args.action=='add':
  command=json.loads(args.verify_json)
  if not isinstance(command,list) or not command or any(not isinstance(x,str) or not x for x in command): raise ValueError('verification must be a nonempty argv array')
  if Path(command[0]).name in {'true','echo','printf'}: raise ValueError('choose a real verification command')
  for f in args.files:
   if Path(f).is_absolute() or '..' in Path(f).parts: raise ValueError('files must be project-relative')
  for dep in args.depends:
   if not con.execute('SELECT id FROM tasks WHERE id=?',(dep,)).fetchone(): raise ValueError('unknown dependency')
  with con:
   cur=con.execute('INSERT INTO tasks(title,description,files,deps,command,status) VALUES(?,?,?,?,?,?)',(args.title,args.description,json.dumps(args.files),json.dumps(args.depends),json.dumps(command),'todo'))
  print(cur.lastrowid); return
 if args.action=='list':
  print(json.dumps([dict(r) for r in con.execute('SELECT * FROM tasks')],indent=2)); return
 row=con.execute('SELECT * FROM tasks WHERE id=?',(args.id,)).fetchone()
 if not row: raise ValueError('unknown task')
 if args.action=='start':
  if row['status'] not in ('todo','blocked'): raise ValueError('task must be todo or blocked')
  for dep in json.loads(row['deps']):
   if con.execute('SELECT status FROM tasks WHERE id=?',(dep,)).fetchone()[0]!='done': raise ValueError('dependency is not done')
  with con: con.execute('UPDATE tasks SET status=?,fingerprint=NULL,evidence=NULL WHERE id=?',('active',args.id))
 elif args.action=='block':
  if row['status']=='done': raise ValueError('completed task cannot be blocked')
  with con: con.execute('UPDATE tasks SET status=?,fingerprint=NULL,evidence=? WHERE id=?',('blocked',args.reason,args.id))
 elif args.action=='verify':
  if row['status']!='active': raise ValueError('start the task before verification')
  before=fingerprint(); log=DATA/f'verify-{args.id}-{time.time_ns()}.log'
  with con: con.execute('UPDATE tasks SET fingerprint=NULL,evidence=? WHERE id=?',(str(log.relative_to(ROOT)),args.id))
  with log.open('wb') as stream:
   process = None
   try:
    process = subprocess.Popen(json.loads(row['command']),cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
    code = process.wait(timeout=args.timeout)
   except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
    if process is not None:
     try: os.killpg(process.pid, signal.SIGKILL)
     except ProcessLookupError: pass
     process.wait()
    if isinstance(error, KeyboardInterrupt): raise
    code=124; stream.write(b'\nVerification timed out\n')
   except OSError as e: code=127; stream.write(str(e).encode())
  after=fingerprint()
  if code!=0 or before!=after: raise ValueError(f'verification failed or changed repository (exit {code}); log: {log}')
  with con: con.execute('UPDATE tasks SET fingerprint=? WHERE id=?',(after,args.id))
  print(f'PASS {log}')
 elif args.action=='finish':
  if row['status']!='active' or not row['fingerprint'] or row['fingerprint']!=fingerprint(): raise ValueError('current successful verification required')
  with con: con.execute('UPDATE tasks SET status=? WHERE id=?',('done',args.id))
 print(f'{args.action}: {args.id}')
if __name__=='__main__':
 try: main()
 except (ValueError,OSError,subprocess.CalledProcessError,sqlite3.Error) as e: sys.exit(str(e))
