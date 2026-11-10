"""Check tracked and build manifests without printing sensitive matches."""
from pathlib import Path
import re
import os
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
violations = []
key_pattern = re.compile(r"(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{30,}|AKIA[A-Z0-9]{16})")
network_pattern = re.compile(r"java\s*\.\s*net\s*\.\s*(?:URL|Socket)\b|\bHttpURLConnection\b|\bokhttp", re.IGNORECASE)
for name in subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard"], cwd=root, text=True).splitlines():
    path = root / name
    if path.is_file() and path.suffix not in {".jar", ".png", ".zip"}:
        content = path.read_text(errors="replace")
        if key_pattern.search(content):
            violations.append(f"secret-pattern: {name}")
        if (path.suffix in {".kt", ".java"} and path.relative_to(root).parts[0] in {"core", "probes"}
                and "src" in path.relative_to(root).parts and network_pattern.search(content)):
            violations.append(f"network-source: {name}")
for directory, subdirs, names in os.walk(root):
    subdirs[:] = [name for name in subdirs if name not in {".cache", ".git", ".gradle", ".kotlin", "node_modules"}]
    if "AndroidManifest.xml" not in names:
        continue
    path = Path(directory) / "AndroidManifest.xml"
    content = path.read_text()
    forbidden = ["AccessibilityService", "QUERY_ALL_PACKAGES"]
    if "probes" in path.parts:
        forbidden += ["android.permission.INTERNET", "android.permission.SEND_SMS"]
    if any(item in content for item in forbidden):
        violations.append(f"forbidden manifest declaration: {path.relative_to(root)}")
if violations:
    print("\n".join(violations), file=sys.stderr)
    sys.exit(1)
print("Repository safety checks passed")
