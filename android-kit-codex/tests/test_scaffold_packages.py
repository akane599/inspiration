"""Package validation for the ancillary template only; no installers or live copies."""
import contextlib
import io
import json
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "template/.codex/android-kit/scaffold.py"
# Independent language inventories, not the implementation's validation constant.
# Java's reserved '_' and Kotlin's punctuation-bearing tokens fail the identifier regex.
JAVA_KEYWORDS = """
abstract assert boolean break byte case catch char class const continue default do
 double else enum extends final finally float for goto if implements import instanceof
 int interface long native new package private protected public return short static
 strictfp super switch synchronized this throw throws transient try void volatile while
 true false null
""".split()
KOTLIN_HARD_KEYWORDS = """
as break class continue do else false for fun if in interface is null object package
return super this throw true try typealias typeof val var when while
""".split()
KEYWORDS = sorted(set(JAVA_KEYWORDS + KOTLIN_HARD_KEYWORDS))
VALID_PACKAGES = (
    "com.example.sample", "a.b", "com.example.app_2", "a0.b_1.c2",
    "com.example.typealiasapp", "com.example.float32", "nativeapp.example.app",
    # Contextual/soft keywords are legal package identifiers, not hard keywords.
    "com.example.data", "com.example.internal", "com.example.record",
)
INVALID_PACKAGES = (
    "", "com", ".com.example", "com.example.", "com..example", "Com.example",
    "com.Example", "com.2app", "com._app", "com._", "com.example-name",
    "com.example/app", "com.example app", " com.example", "com.example ",
    "com.example\n", "com.éxample", "com.as?", "com.!in", "com.`typealias`",
)


class ScaffoldPackageTests(unittest.TestCase):
    def cli(self, package, destination, dry_run=False):
        command = [sys.executable, "-B", str(SCRIPT), "--name", "Sample",
                   "--package", package, "--offline", "--dir", str(destination)]
        if dry_run:
            command.append("--dry-run")
        return subprocess.run(command, capture_output=True, text=True, timeout=30)

    def test_reported_regressions_rejected_by_cli_before_destination_creation(self):
        for keyword in ("typealias", "typeof", "float", "native"):
            for dry_run in (True, False):
                with self.subTest(keyword=keyword, dry_run=dry_run), \
                        tempfile.TemporaryDirectory() as tmp:
                    destination = Path(tmp) / "missing-parent" / "project"
                    result = self.cli(f"com.example.{keyword}", destination, dry_run)
                    self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                    self.assertIn("package contains a Kotlin/Java keyword", result.stderr)
                    self.assertEqual("", result.stdout)
                    self.assertFalse(destination.parent.exists())

    def test_every_keyword_in_every_segment_rejected_before_any_output_work(self):
        main = runpy.run_path(str(SCRIPT))["main"]
        for keyword in KEYWORDS:
            for package in (f"{keyword}.example.app", f"com.{keyword}.app",
                            f"com.example.{keyword}"):
                for dry_run in (True, False):
                    with self.subTest(package=package, dry_run=dry_run), \
                            tempfile.TemporaryDirectory() as tmp:
                        destination = Path(tmp) / "missing-parent" / "project"
                        stdout, stderr = io.StringIO(), io.StringIO()
                        argv = [str(SCRIPT), "--name", "Sample", "--package", package,
                                "--offline", "--dir", str(destination)]
                        if dry_run:
                            argv.append("--dry-run")
                        guards = {name: Mock(side_effect=AssertionError(name))
                                  for name in ("resolve", "gradle_wrapper", "write")}
                        with patch.object(sys, "argv", argv), \
                                patch.dict(main.__globals__, guards), \
                                patch("tempfile.TemporaryDirectory") as staging, \
                                patch.object(Path, "mkdir") as mkdir, \
                                patch("shutil.copy2") as copy, \
                                contextlib.redirect_stdout(stdout), \
                                contextlib.redirect_stderr(stderr):
                            with self.assertRaises(SystemExit) as error:
                                main()
                        self.assertEqual(2, error.exception.code, stderr.getvalue())
                        self.assertIn("package contains a Kotlin/Java keyword", stderr.getvalue())
                        self.assertEqual("", stdout.getvalue())
                        for guard in (*guards.values(), staging, mkdir, copy):
                            guard.assert_not_called()
                        self.assertFalse(destination.parent.exists())

    def test_regex_rejections_leave_no_destination(self):
        for package in INVALID_PACKAGES:
            for dry_run in (True, False):
                with self.subTest(package=package, dry_run=dry_run), \
                        tempfile.TemporaryDirectory() as tmp:
                    destination = Path(tmp) / "missing-parent" / "project"
                    result = self.cli(package, destination, dry_run)
                    # Preserve the existing regex validation's sys.exit(message) contract.
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn("invalid package:", result.stderr)
                    self.assertEqual("", result.stdout)
                    self.assertFalse(destination.parent.exists())

    def test_valid_packages_dry_run_and_generate_sources(self):
        for package in VALID_PACKAGES:
            for dry_run in (True, False):
                with self.subTest(package=package, dry_run=dry_run), \
                        tempfile.TemporaryDirectory() as tmp:
                    destination = Path(tmp) / "missing-parent" / "project"
                    result = self.cli(package, destination, dry_run)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual("", result.stderr)
                    report = json.loads(result.stdout)
                    if dry_run:
                        self.assertIn("versions", report)
                        self.assertFalse(destination.parent.exists())
                    else:
                        self.assertGreater(report["created"], 0)
                        build = (destination / "app/build.gradle.kts").read_text()
                        self.assertIn(f'namespace = "{package}"', build)
                        self.assertIn(f'applicationId = "{package}"', build)
                        kotlin = destination / "app/src/main/kotlin" / package.replace(".", "/")
                        source = (kotlin / "MainActivity.kt").read_text()
                        self.assertIn(f"package {package}\n", source)
                        self.assertIn(f"import {package}.ui.home.HomeRoute", source)
                        self.assertFalse((destination / "gradlew").exists())


if __name__ == "__main__":
    unittest.main()
