#!/usr/bin/env python3
import argparse
import subprocess
import sys
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parents[1]
SKILL_SCRIPTS = PLUGIN_ROOT / "skills" / "android-emulator-qa" / "scripts"


def run_command(args, *, stdout=None, stderr=None, text=True):
    try:
        return subprocess.run(args, check=False, stdout=stdout, stderr=stderr, text=text)
    except FileNotFoundError:
        print(f"error: command not found: {args[0]}", file=sys.stderr)
        return subprocess.CompletedProcess(args, 127)


def cmd_devices(_args):
    result = run_command(["adb", "devices", "-l"])
    return result.returncode


def cmd_resolve_activity(args):
    result = run_command(
        ["adb", "-s", args.serial, "shell", "cmd", "package", "resolve-activity", "--brief", args.package]
    )
    return result.returncode


def cmd_screenshot(args):
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as handle:
        result = run_command(
            ["adb", "-s", args.serial, "exec-out", "screencap", "-p"],
            stdout=handle,
            text=False,
        )
    return result.returncode


def cmd_dump_ui(args):
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as handle:
        result = run_command(
            ["adb", "-s", args.serial, "exec-out", "uiautomator", "dump", "/dev/tty"],
            stdout=handle,
        )
    return result.returncode


def cmd_summarize_ui(args):
    helper = SKILL_SCRIPTS / "ui_tree_summarize.py"
    result = run_command([sys.executable, str(helper), args.input, args.out])
    return result.returncode


def cmd_pick(args):
    helper = SKILL_SCRIPTS / "ui_pick.py"
    result = run_command([sys.executable, str(helper), args.input, args.target])
    return result.returncode


def resolve_pid(serial, package):
    result = run_command(
        ["adb", "-s", serial, "shell", "pidof", "-s", package],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        return "", result.returncode
    return result.stdout.strip(), 0


def cmd_logcat(args):
    pid, pid_status = resolve_pid(args.serial, args.package)
    if pid_status != 0 or not pid:
        print(f"error: no running process found for package {args.package}", file=sys.stderr)
        return 2

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as handle:
        result = run_command(["adb", "-s", args.serial, "logcat", "--pid", pid, "-d"], stdout=handle)
    return result.returncode


def build_parser():
    parser = argparse.ArgumentParser(description="Android emulator QA helper commands")
    subparsers = parser.add_subparsers(dest="command", required=True)

    devices = subparsers.add_parser("devices", help="List connected Android devices and emulators")
    devices.set_defaults(func=cmd_devices)

    resolve = subparsers.add_parser("resolve-activity", help="Resolve the launch activity for a package")
    resolve.add_argument("--serial", required=True)
    resolve.add_argument("--package", required=True)
    resolve.set_defaults(func=cmd_resolve_activity)

    screenshot = subparsers.add_parser("screenshot", help="Capture a PNG screenshot from an emulator")
    screenshot.add_argument("--serial", required=True)
    screenshot.add_argument("--out", required=True)
    screenshot.set_defaults(func=cmd_screenshot)

    dump_ui = subparsers.add_parser("dump-ui", help="Dump uiautomator XML from an emulator")
    dump_ui.add_argument("--serial", required=True)
    dump_ui.add_argument("--out", required=True)
    dump_ui.set_defaults(func=cmd_dump_ui)

    summarize_ui = subparsers.add_parser("summarize-ui", help="Summarize a uiautomator XML file")
    summarize_ui.add_argument("--input", required=True)
    summarize_ui.add_argument("--out", required=True)
    summarize_ui.set_defaults(func=cmd_summarize_ui)

    pick = subparsers.add_parser("pick", help="Print center coordinates for exact text or content-desc")
    pick.add_argument("--input", required=True)
    pick.add_argument("--target", required=True)
    pick.set_defaults(func=cmd_pick)

    logcat = subparsers.add_parser("logcat", help="Capture logcat output for a running package")
    logcat.add_argument("--serial", required=True)
    logcat.add_argument("--package", required=True)
    logcat.add_argument("--out", required=True)
    logcat.set_defaults(func=cmd_logcat)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
