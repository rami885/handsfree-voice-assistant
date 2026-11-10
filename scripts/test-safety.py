"""Exercise the safety command against isolated Git repositories, including untracked sources."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-safety.py")


class SafetyTest(unittest.TestCase):
    def scan(self, name, content):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / "scripts").mkdir()
            shutil.copyfile(SCRIPT, root / "scripts/check-safety.py")
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            return subprocess.run([sys.executable, str(root / "scripts/check-safety.py")],
                                  cwd=root, capture_output=True, text=True)

    def test_network_apis_are_rejected_in_all_core_and_probes_sources(self):
        for module in ("core/guard", "core/intent", "core/audio", "core/assist", "probes"):
            for api in ("java.net.URL", "HttpURLConnection", "java.net.Socket", "okhttp3.OkHttpClient"):
                with self.subTest(module=module, api=api):
                    name = f"{module}/src/main/kotlin/Forbidden.kt"
                    result = self.scan(name, f"import {api}\n")
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn(f"network-source: {name}", result.stderr)

    def test_secret_patterns_fail_without_printing_tokens(self):
        fixtures = (
            "sk-" + "a" * 24, "sk-proj-" + "a" * 24,
            "AIza" + "a" * 32, "AKIA" + "A" * 16,
            "sk-or-" + "a" * 40, "ghp_" + "a" * 36,
            "github_pat_" + "a" * 82, "xoxb-" + "1" * 24,
            "xoxp-" + "1" * 24, "AQ." + "a" * 80,
        )
        for token in fixtures:
            with self.subTest(prefix=token[:6]):
                result = self.scan("fixture.txt", token)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("secret-pattern: fixture.txt", result.stderr)
                self.assertNotIn(token, result.stdout + result.stderr)

    def test_benign_source_and_documentation_pass(self):
        for name, content in (
            ("probes/src/main/kotlin/Local.kt", "import java.io.File\n"),
            ("docs/example.md", "java.net.URL HttpURLConnection java.net.Socket okhttp"),
            ("fixture.txt", "sk-or- ghp_ github_pat_ xoxb- xoxp- AQ. AQ.short"),
        ):
            with self.subTest(name=name):
                result = self.scan(name, content)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("Repository safety checks passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
