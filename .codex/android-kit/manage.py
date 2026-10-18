#!/usr/bin/env python3
import argparse, importlib.util, json, sys
from pathlib import Path
ROOT=Path.cwd().resolve(); KIT=ROOT/'.codex/android-kit'
spec=importlib.util.spec_from_file_location('project_files',KIT/'project-files.py'); pf=importlib.util.module_from_spec(spec); spec.loader.exec_module(pf)
def main():
 ap=argparse.ArgumentParser(); ap.add_argument('action',choices=['conflicts','resolve']); ap.add_argument('paths',nargs='*'); a=ap.parse_args()
 if a.action=='conflicts': print(json.dumps(pf.pending_conflicts(ROOT),indent=2)); return
 info=json.loads((KIT/'install.json').read_text()); template=Path(info['source'])/'template'
 if not template.is_dir(): raise ValueError('keep the extracted kit available, or rerun setup from a fresh archive before resolving')
 pf.resolve(ROOT,template,a.paths)
if __name__=='__main__':
 try: main()
 except (ValueError,OSError,SyntaxError) as e: sys.exit(str(e))
