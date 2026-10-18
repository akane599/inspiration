import json, os, shutil, subprocess, sys, tempfile, time, unittest
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]
class KitTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name)/'project'; self.root.mkdir(); self.kit=Path(self.tmp.name)/'distribution'; shutil.copytree(BASE,self.kit,ignore=shutil.ignore_patterns('__pycache__','.git')); self.env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')
 def tearDown(self): self.tmp.cleanup()
 def run_cmd(self,*args,ok=0):
  r=subprocess.run(args,cwd=self.root,env=self.env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  self.assertEqual(r.returncode,ok,r.stdout); return r.stdout
 def install(self,ok=0): return self.run_cmd('bash',str(self.kit/'setup.sh'),str(self.root),ok=ok)
 def p(self,rel): return self.root/rel
 def test_install_repeat_preserves_claude_and_config(self):
  for rel in ['.claude/settings.json','.codex/config.toml']:
   p=self.p(rel); p.parent.mkdir(parents=True,exist_ok=True); p.write_text('private existing content')
  self.install(); self.install()
  for rel in ['.claude/settings.json','.codex/config.toml']: self.assertEqual(self.p(rel).read_text(),'private existing content')
  self.assertEqual(json.loads(self.p('.codex/android-kit/conflicts.json').read_text()),[])
 def test_existing_instructions_conflict(self):
  self.p('AGENTS.md').write_text('Project-specific instructions\n'); self.install(ok=2)
  self.assertEqual(self.p('AGENTS.md').read_text(),'Project-specific instructions\n')
  self.assertIn('AGENTS.md',json.loads(self.p('.codex/android-kit/conflicts.json').read_text()))
 def test_reviewed_merge_survives_reinstall(self):
  self.install(); target=self.p('.agents/skills/android-bootstrap/SKILL.md'); target.write_text(target.read_text()+'\nProject custom instruction\n'); self.install(ok=2)
  self.run_cmd(sys.executable,'.codex/android-kit/manage.py','resolve','.agents/skills/android-bootstrap/SKILL.md')
  self.install(); self.assertIn('Project custom instruction',target.read_text())
 def test_new_template_requires_new_review(self):
  self.install(); target=self.p('AGENTS.md'); target.write_text(target.read_text()+'custom\n'); self.install(ok=2)
  self.run_cmd(sys.executable,'.codex/android-kit/manage.py','resolve','AGENTS.md'); template=self.kit/'template/AGENTS.md'; template.write_text(template.read_text()+'new upstream\n'); self.install(ok=2)
  self.assertIn('custom',target.read_text())
 def test_symlink_refused_before_install(self):
  outside=Path(self.tmp.name)/'outside'; outside.mkdir(); self.p('.agents').symlink_to(outside,target_is_directory=True)
  self.install(ok=1); self.assertEqual(list(outside.iterdir()),[]); self.assertFalse(self.p('.codex').exists())
 def test_incoming_symlink_refused(self):
  self.p('.codex/android-kit').mkdir(parents=True); outside=Path(self.tmp.name)/'outside'; outside.mkdir(); self.p('.codex/android-kit/incoming').symlink_to(outside,target_is_directory=True)
  self.install(ok=1); self.assertFalse(self.p('AGENTS.md').exists())
 def test_preflight_no_writes(self):
  self.run_cmd('bash',str(self.kit/'setup.sh'),'--check',str(self.root)); self.assertEqual(list(self.root.iterdir()),[])
 def test_malformed_helper_stops_before_changes(self):
  (self.kit/'template/.codex/android-kit/board.py').write_text('if broken syntax')
  self.install(ok=1); self.assertEqual(list(self.root.iterdir()),[])
 def board(self,*args,ok=0): return self.run_cmd(sys.executable,'.codex/android-kit/board.py',*args,ok=ok)
 def repo(self):
  self.install(); self.run_cmd('git','init','-q'); self.run_cmd('git','add','AGENTS.md'); self.run_cmd('git','-c','user.name=Test','-c','user.email=test@example.invalid','commit','-qm','init')
 def task(self,code='assert 2+2==4'):
  return self.board('add','Example','--description','Concrete assertion','--verify-json',json.dumps([sys.executable,'-c',code])).strip()
 def test_finish_needs_current_verification(self):
  self.repo(); i=self.task(); self.board('start',i); self.board('finish',i,ok=1); self.board('verify',i); self.board('finish',i)
 def test_source_change_invalidates_check(self):
  self.repo(); i=self.task(); self.board('start',i); self.board('verify',i); self.p('source.kt').write_text('changed'); self.board('finish',i,ok=1)
 def test_failed_verify_cannot_finish(self):
  self.repo(); i=self.task('raise SystemExit(7)'); self.board('start',i); self.board('verify',i,ok=1); self.board('finish',i,ok=1)
 def test_verify_that_changes_sources_rejected(self):
  self.repo(); i=self.task("from pathlib import Path; Path('new.kt').write_text('new')"); self.board('start',i); self.board('verify',i,ok=1)
 def test_verification_timeout_stops_descendant_mutations(self):
  self.repo()
  child="import time; from pathlib import Path; time.sleep(2); Path('orphan.txt').write_text('unexpected')"
  parent="import subprocess, sys, time; subprocess.Popen([sys.executable, '-c', "+repr(child)+"]); time.sleep(10)"
  i=self.task(parent); self.board('start',i)
  self.board('verify',i,'--timeout','1',ok=1)
  time.sleep(2)
  self.assertFalse(self.p('orphan.txt').exists())
  self.board('finish',i,ok=1)
 def test_dependencies_gate_start(self):
  self.repo(); i=self.task(); j=self.board('add','Second','--description','dependent','--depends',i,'--verify-json',json.dumps([sys.executable,'-c','assert True'])).strip(); self.board('start',j,ok=1)
 def test_gradle_exit_preserved(self):
  self.install(); self.p('gradlew').write_text('#!/bin/bash\necho failed\nexit 23\n'); self.run_cmd('bash','.codex/android-kit/gradle-check.sh','test',ok=23)
 def test_scaffold_offline_and_adoption_guard(self):
  self.install(); self.run_cmd(sys.executable,'.codex/android-kit/scaffold.py','--name','Sample','--package','com.example.sample','--offline'); self.assertTrue(self.p('app/build.gradle.kts').exists())
 def test_emulator_xml_helper(self):
  self.install(); self.p('ui.xml').write_text('<hierarchy><node text="Settings" content-desc="" resource-id="id/settings" bounds="[10,20][30,60]" /></hierarchy>')
  text=self.run_cmd(sys.executable,'.codex/android-kit/emulator/scripts/android-qa.py','pick','--input','ui.xml','--target','Settings'); self.assertIn('20',text); self.assertIn('40',text)
 def test_skill_roles_and_no_provider_settings(self):
  skills=list((self.kit/'template/.agents/skills').glob('*/SKILL.md')); self.assertEqual(len(skills),19)
  roles=list((self.kit/'template/.codex/android-kit/review-roles').glob('*/*.md')); self.assertEqual(len(roles),14)
  for p in skills:
   text=p.read_text(); self.assertIn('name: '+p.parent.name,text); self.assertNotIn('${CLAUDE_PLUGIN_ROOT}',text)
  self.assertFalse((self.kit/'template/.codex/config.toml').exists())
if __name__=='__main__': unittest.main()
