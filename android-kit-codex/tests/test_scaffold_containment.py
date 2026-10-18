"""Destination checks must precede network or subprocess work in both supported copies."""
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
COPIES = [ROOT / ".codex/android-kit/scaffold.py",
          ROOT / "android-kit-codex/template/.codex/android-kit/scaffold.py"]


class WrapperContainmentTests(unittest.TestCase):
    def test_symlinked_ancestor_and_dangling_file_are_rejected_before_download(self):
        for script in COPIES:
            wrapper = runpy.run_path(str(script))["gradle_wrapper"]
            for relative in ["gradle", "gradle/wrapper", "gradlew", "gradlew.bat",
                             "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]:
                with self.subTest(script=script, relative=relative), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp) / "project"
                    outside = Path(tmp) / "outside"
                    root.mkdir()
                    outside.mkdir()
                    link = root / relative
                    link.parent.mkdir(parents=True, exist_ok=True)
                    target = outside if relative in ["gradle", "gradle/wrapper"] else outside / "missing"
                    link.symlink_to(target, target_is_directory=target == outside)
                    created = []
                    with patch.dict(wrapper.__globals__, fetch=unittest.mock.Mock(side_effect=AssertionError("network"))), \
                         patch("subprocess.run") as command:
                        with self.assertRaisesRegex(ValueError, "symlink in scaffold destination"):
                            wrapper(root, "9.8.0", created)
                        command.assert_not_called()
                    self.assertEqual([], list(outside.iterdir()))
                    self.assertEqual([], created)

    def test_partial_wrapper_is_never_overwritten(self):
        for script in COPIES:
            wrapper = runpy.run_path(str(script))["gradle_wrapper"]
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / "gradlew").write_text("preserve me")
                with self.assertRaisesRegex(ValueError, "wrapper files already exist"):
                    wrapper(root, "9.8.0", [])
                self.assertEqual("preserve me", (root / "gradlew").read_text())


if __name__ == "__main__":
    unittest.main()
