#!/usr/bin/env python3
"""Read-only configuration check. Does not prove runtime loading or inference."""
import json, shutil, sys
from pathlib import Path
root=Path.cwd(); kit=root/'.codex/android-kit'; errors=[]
for name in ['codex','git','python3','java','adb','kotlin-lsp','jdtls']:
 print(('OK ' if shutil.which(name) else 'MISSING ')+name+(' (optional)' if name in ['kotlin-lsp','jdtls'] else ''))
 if name in ['codex','git','python3'] and not shutil.which(name): errors.append(name)
skills=list((root/'.agents/skills').glob('android-*/SKILL.md'))
for name in ('full-repo-audit','audit-remediate'):
 p=root/'.agents/skills'/name/'SKILL.md'
 if not p.is_file(): errors.append('missing required audit skill: '+name)
 else: skills.append(p)
for p in skills:
 t=p.read_text()
 if not t.startswith('---\nname: '+p.parent.name+'\n') or '\ndescription: ' not in t: errors.append(str(p))
try:
 conflicts=json.loads((kit/'conflicts.json').read_text())
 if conflicts: errors.append('pending merges: '+', '.join(conflicts))
 manifest=json.loads((kit/'manifest.json').read_text())
 for rel in manifest:
  if not (root/rel).is_file(): errors.append('missing '+rel)
except (OSError,ValueError) as e: errors.append(str(e))
print('Skills:',len(skills))
print('No gateway or hooks are installed by this kit. Existing global providers/hooks are not audited here.')
print('Filesystem checks only: verify skill discovery in /skills; Android builds, device access and inference require local testing.')
for e in errors: print('FAIL '+e)
sys.exit(bool(errors))
