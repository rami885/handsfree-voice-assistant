"""Regression tests exercise Gradle with real local dependency declarations."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent

class LocalDependenciesTest(unittest.TestCase):
    def test_local_archives_are_rejected_in_every_protected_module(self):
        for module in (":core:guard", ":core:intent", ":probes"):
            for declaration in ('files("unreviewed.jar")', 'fileTree("libs")'):
                with self.subTest(module=module, declaration=declaration):
                    with tempfile.TemporaryDirectory(dir=ROOT / ".cache") as directory:
                        init = Path(directory) / "injected.gradle"
                        init.write_text('gradle.projectsEvaluated {\n'
                            f'def target = gradle.rootProject.project("{module}")\ntarget.dependencies.add("implementation", target.{declaration})\n'
                            '}\n')
                        result = subprocess.run([str(ROOT / "gradlew"), "safetyCheck", "--offline", "--project-cache-dir", str(Path(directory) / "project-cache"), "--init-script", str(init)],
                            cwd=ROOT, env=os.environ, capture_output=True, text=True)
                        self.assertNotEqual(result.returncode, 0, result.stdout[-2000:])
                        self.assertIn("Local file dependency forbidden in " + module, result.stdout + result.stderr)

if __name__ == "__main__":
    unittest.main()
