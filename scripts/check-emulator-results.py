"""Require both instrumented tests to have run successfully on CI."""
from pathlib import Path
import xml.etree.ElementTree as ET

required = {"everyExperimentStartsAndRecords", "exportsSeededRecordsToCsvAndJson"}
seen = set()
for path in Path("probes/build/outputs/androidTest-results/connected").rglob("*.xml"):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        assert case.find("failure") is None and case.find("error") is None, f"Failed test: {case.get('name')}"
        assert case.find("skipped") is None, f"Skipped test: {case.get('name')}"
        seen.add(case.get("name"))
assert required <= seen, f"Missing executed instrumentation tests: {sorted(required - seen)}"
print(f"Verified {len(required)} required emulator tests executed without failure or skip")
