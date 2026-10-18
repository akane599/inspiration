#!/usr/bin/env python3
"""Preflight, snapshots and hash-based template updates. Python 3.9+, stdlib only."""
import argparse
import datetime
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

SKIP = {'gitignore.append'}
RETIRED = {'.codex/android-kit/review-roles/code-review/history-reviewer.md', '.codex/android-kit/review-roles/pr-review-toolkit/comment-analyzer.md', '.codex/android-kit/review-roles/code-review/NOTICE', '.codex/android-kit/review-roles/code-review/comment-reviewer.md', '.codex/android-kit/review-roles/pr-review-toolkit/code-simplifier.md', '.agents/skills/android-review-pr/SKILL.md', '.codex/android-kit/review-roles/pr-review-toolkit/code-reviewer.md', '.codex/android-kit/review-roles/pr-review-toolkit/type-design-analyzer.md', '.codex/android-kit/review-roles/code-review/LICENSE', '.codex/android-kit/review-roles/code-review/prior-reviewer.md', '.codex/android-kit/review-roles/pr-review-toolkit/finding-validator.md', '.codex/android-kit/review-roles/code-review/finding-validator.md', '.codex/android-kit/review-roles/pr-review-toolkit/silent-failure-hunter.md', '.codex/android-kit/review-roles/pr-review-toolkit/pr-test-analyzer.md', '.codex/android-kit/review-roles/pr-review-toolkit/NOTICE', '.agents/skills/android-code-review/SKILL.md', '.agents/skills/android-security-review/SKILL.md', '.codex/android-kit/review-roles/code-review/bug-reviewer.md', '.codex/android-kit/review-roles/code-review/triage.md', '.agents/skills/android-plan-audit/SKILL.md', '.codex/android-kit/review-roles/code-review/guideline-reviewer.md', '.codex/android-kit/review-roles/pr-review-toolkit/LICENSE'}
RESOLUTIONS = '.codex/android-kit/resolutions.json'
CONFLICTS = '.codex/android-kit/conflicts.json'


def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, indent=2, sort_keys=True)
            stream.write('\n')
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def pending_conflicts(root):
    path = safe(root, CONFLICTS)
    rows = json.loads(path.read_text()) if path.exists() else []
    if not isinstance(rows, list):
        raise ValueError('conflicts.json must be an array of project-relative paths')
    for rel in rows:
        if not isinstance(rel, str) or not (rel.startswith(('.codex/', '.agents/')) or rel in {'AGENTS.md'}):
            raise ValueError('invalid conflict path')
        safe(root, rel)
    return rows


def safe(root, rel):
    p = Path(rel)
    if p.is_absolute() or '..' in p.parts or not p.parts:
        raise ValueError(f'unsafe managed path: {rel}')
    dest = root / p
    for part in (dest, *dest.parents):
        if part == root:
            break
        if part.is_symlink():
            raise ValueError(f'refusing symlink in managed path: {part}')
    if dest.exists() and not dest.is_file():
        raise ValueError(f'expected file, found directory: {dest}')
    return dest


def inventory(root, tpl):
    rows = {str(p.relative_to(tpl)) for p in tpl.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    rows -= {'gitignore.append'}
    rows.update(RETIRED)
    rows |= {'.gitignore',
             '.codex/android-kit/state.json', '.codex/android-kit/manifest.txt', '.codex/android-kit/manifest.json',
             RESOLUTIONS, CONFLICTS}
    rows.update(pending_conflicts(root))
    old = root / '.codex/android-kit/manifest.txt'
    safe(root, '.codex/android-kit/manifest.txt')
    if old.exists():
        for rel in old.read_text().splitlines():
            if rel and not (rel.startswith(('.codex/', '.agents/')) or rel in {'AGENTS.md'}):
                raise ValueError(f'old manifest contains non-kit path: {rel}')
            if rel:
                rows.add(rel)
    for rel in rows:
        safe(root, rel)
    for rel in ( '.codex/android-kit/state.json', '.codex/android-kit/manifest.json', RESOLUTIONS):
        p = root / rel
        if p.exists():
            data = json.loads(p.read_text())
            if not isinstance(data, dict):
                raise ValueError(f'expected JSON object: {rel}')
            if 'settings' in p.name:
                for section in ('hooks', 'permissions', 'enabledPlugins', 'env'):
                    if section in data and not isinstance(data[section], dict):
                        raise ValueError(f'{rel}: {section} must be an object')
                for event, entries in data.get('hooks', {}).items():
                    if not isinstance(entries, list) or any(not isinstance(e, dict) or not isinstance(e.get('hooks'), list) for e in entries):
                        raise ValueError(f'{rel}: malformed hook group {event}')
                for key in ('allow', 'deny', 'ask'):
                    entries = data.get('permissions', {}).get(key, [])
                    if not isinstance(entries, list) or any(not isinstance(e, str) for e in entries):
                        raise ValueError(f'{rel}: permissions.{key} must be a string array')
    return rows


def snapshot(root, tpl):
    rows = inventory(root, tpl)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    folder = root / '.codex/android-kit/backups' / stamp
    # Do not follow a project's backup directory symlink.
    safe(root, str(folder.relative_to(root) / 'snapshot.json'))
    folder.mkdir(parents=True, mode=0o700)
    existing, absent = [], []
    for rel in sorted(rows):
        src = root / rel
        if src.is_file():
            dst = folder / 'files' / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            existing.append(rel)
        else:
            absent.append(rel)
    (folder / 'snapshot.json').write_text(json.dumps({'existing': existing, 'absent': absent}, indent=2) + '\n')
    print('ok snapshot: ' + str(folder.relative_to(root)))


def sync(root, tpl):
    inventory(root, tpl)
    mp = root / '.codex/android-kit/manifest.json'
    previous = json.loads(mp.read_text()) if mp.exists() else {}
    resolution_path = safe(root, RESOLUTIONS)
    resolutions = json.loads(resolution_path.read_text()) if resolution_path.exists() else {}
    legacy = root / '.codex/android-kit/manifest.txt'
    owned = set(legacy.read_text().splitlines()) if legacy.exists() else set()
    hashes, conflicts = {}, []
    incoming = root / '.codex/android-kit/incoming'
    current = {}
    for src in sorted(tpl.rglob('*')):
        if not src.is_file() or "__pycache__" in src.parts:
            continue
        rel = str(src.relative_to(tpl))
        if rel in SKIP:
            continue
        current[rel] = src
        dst = safe(root, rel)
        accepted = resolutions.get(rel)
        if (dst.exists() and isinstance(accepted, dict)
                and accepted.get('template_sha256') == digest(src)
                and accepted.get('merged_sha256') == digest(dst)):
            # Track the shipped baseline, never treat customized merged bytes as kit-owned.
            hashes[rel] = digest(src)
            candidate = safe(root, str((incoming / rel).relative_to(root)))
            if candidate.is_file():
                candidate.unlink()
            print(f'ok kept reviewed merge: {rel}')
            continue
        if dst.exists() and digest(dst) != digest(src) and digest(dst) != previous.get(rel):
            candidate = safe(root, str((incoming / rel).relative_to(root)))
            candidate.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, candidate)
            conflicts.append(rel)
            if rel in previous:
                hashes[rel] = previous[rel]
            print(f'warn kept modified/project file: {rel}; new version: .codex/android-kit/incoming/{rel}')
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        hashes[rel] = digest(src)
        candidate = safe(root, str((incoming / rel).relative_to(root)))
        if candidate.is_file():
            candidate.unlink()
    for rel in sorted((owned | RETIRED) - set(current)):
        if not rel:
            continue
        dst = safe(root, rel)
        if not dst.exists():
            continue
        if rel not in RETIRED:
            print(f'warn preserved file absent from template (no explicit retirement): {rel}')
            if rel in previous: hashes[rel] = previous[rel]
            continue
        retired = root / '.codex/android-kit/retired' / (rel + '.' + digest(dst)[:12])
        safe(root, str(retired.relative_to(root)))
        retired.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(dst), str(retired))
    mp.parent.mkdir(parents=True, exist_ok=True)
    mp.write_text(json.dumps(hashes, indent=2, sort_keys=True) + '\n')
    legacy.write_text('\n'.join(sorted(hashes)) + '\n')
    conflict_file = root / '.codex/android-kit/conflicts.json'
    safe(root, str(conflict_file.relative_to(root)))
    conflict_file.write_text(json.dumps(conflicts, indent=2) + '\n')
    print(f'ok template sync: {len(hashes)} tracked, {len(conflicts)} manual merges')


def resolve(root, tpl, paths):
    """Acknowledge named, manually reviewed merges; never copy template bytes over them."""
    inventory(root, tpl)
    if not paths:
        raise ValueError('name each reviewed file explicitly; no bulk resolve is available')
    conflicts = pending_conflicts(root)
    resolution_path = safe(root, RESOLUTIONS)
    resolutions = json.loads(resolution_path.read_text()) if resolution_path.exists() else {}
    prepared = {}
    for rel in dict.fromkeys(paths):
        dst = safe(root, rel)
        src = safe(tpl, rel)
        incoming = safe(root, '.codex/android-kit/incoming/' + rel)
        if rel not in conflicts or rel in SKIP:
            raise ValueError(f'not a pending template conflict: {rel}')
        if not dst.is_file() or not src.is_file() or not incoming.is_file():
            raise ValueError(f'current, template and incoming copies must all exist: {rel}')
        if digest(src) != digest(incoming):
            raise ValueError(f'incoming template differs from selected kit: {rel}; rerun setup before resolving')
        if dst.suffix == '.py':
            compile(dst.read_bytes(), str(dst), 'exec')
        if dst.suffix == '.json':
            json.loads(dst.read_text())
        prepared[rel] = {'template_sha256': digest(src), 'merged_sha256': digest(dst),
                         'resolved_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    # Validate the full requested batch before changing metadata. The command records the
    # caller's review; it does not infer whether a semantic merge is correct.
    resolutions.update(prepared)
    save_json(resolution_path, resolutions)
    save_json(safe(root, CONFLICTS), [rel for rel in conflicts if rel not in prepared])
    for rel in prepared:
        safe(root, '.codex/android-kit/incoming/' + rel).unlink()
        print(f'ok recorded reviewed merge: {rel}')
    print(f'ok {len(conflicts) - len(prepared)} template conflicts remain')


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('action', choices=['check', 'snapshot', 'sync', 'resolve'])
    ap.add_argument('root', type=Path)
    ap.add_argument('template', type=Path)
    ap.add_argument('paths', nargs='*', help='explicitly reviewed paths for resolve only')
    args = ap.parse_args()
    try:
        if args.paths and args.action != 'resolve':
            raise ValueError('file paths are accepted only by resolve')
        if args.action == 'resolve':
            resolve(args.root.resolve(), args.template.resolve(), args.paths)
        elif args.action == 'check':
            inventory(args.root.resolve(), args.template.resolve())
        else:
            globals()[args.action](args.root.resolve(), args.template.resolve())
    except (ValueError, OSError, SyntaxError) as e:
        ap.exit(1, f'android-kit: {e}\n')
