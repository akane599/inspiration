"""Check the picker contract and postponed annotations without claiming a Python 3.9 runtime."""
import ast
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELATIVE = ".codex/android-kit/emulator/skills/android-emulator-qa/scripts/ui_pick.py"


class PickerCompatibilityTests(unittest.TestCase):
    def test_annotations_are_postponed_and_cli_picks_coordinates(self):
        for script in [ROOT / RELATIVE, ROOT / "android-kit-codex/template" / RELATIVE]:
            with self.subTest(script=script):
                ast.parse(script.read_text(), feature_version=(3, 9))
                module = runpy.run_path(str(script))
                self.assertIsInstance(module["clean_text"].__annotations__["value"], str)
                self.assertIsInstance(module["find_node"].__annotations__["return"], str)
                with tempfile.TemporaryDirectory() as tmp:
                    xml = Path(tmp) / "ui.xml"
                    xml.write_text('<hierarchy><node text="Settings" bounds="[10,20][30,60]" /></hierarchy>')
                    result = subprocess.run([sys.executable, str(script), str(xml), "Settings"],
                                            check=True, capture_output=True, text=True)
                    self.assertEqual("20 40", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
