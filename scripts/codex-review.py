#!/usr/bin/env python3
"""Standing Codex review of the product definition, always through Codex CLI.

Finds the newest docs/product-definition/v<N>.md (N >= --min-version, default 8).
If docs/reviews/ holds no *-codex-review-of-v<N>.md yet, it runs
`codex exec --sandbox read-only` on the repository with the instructions in
docs/reviews/review-instructions-v6.md (version number adjusted), and saves the
final answer verbatim as docs/reviews/<YYYY-MM-DD>-codex-review-of-v<N>.md.

Codex CLI must be installed and signed in (`codex login --device-auth`, ChatGPT
account). No API key is used and none is stored here.

Usage:
  python3 scripts/codex-review.py              # review the newest unreviewed version, if any
  python3 scripts/codex-review.py --dry-run    # show what would be reviewed, run nothing
  python3 scripts/codex-review.py --check-auth # is Codex CLI installed and signed in
  python3 scripts/codex-review.py --version 8 --force
Exit codes: 0 done or nothing to do, 2 Codex CLI missing, not signed in or failed, 3 bad arguments.
"""
import argparse, datetime, re, shutil, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFS = ROOT / "docs" / "product-definition"
REVIEWS = ROOT / "docs" / "reviews"
INSTRUCTIONS = REVIEWS / "review-instructions-v6.md"
ORDINALS = {7: "السابعة", 8: "الثامنة", 9: "التاسعة", 10: "العاشرة", 11: "الحادية عشرة", 12: "الثانية عشرة"}


def fail(msg):
    print(msg, file=sys.stderr); sys.exit(2)


def codex_version():
    if not shutil.which("codex"):
        fail("codex CLI not found: npm i -g @openai/codex, then codex login --device-auth")
    return subprocess.run(["codex", "--version"], capture_output=True, text=True).stdout.strip()


def check_login():
    r = subprocess.run(["codex", "login", "status"], capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip()
    if r.returncode != 0 or "Not logged in" in out:
        fail("codex CLI is not signed in: run codex login --device-auth")
    return out.splitlines()[0] if out else "signed in"


def latest_version(min_version):
    found = [(int(m.group(1)), p) for p in DEFS.glob("v*.md")
             if (m := re.fullmatch(r"v(\d+)\.md", p.name)) and int(m.group(1)) >= min_version]
    return max(found) if found else (None, None)


def previous_verdict():
    v = sorted(REVIEWS.glob("*-merged-verdict-*.md"))
    return v[-1].relative_to(ROOT).as_posix() if v else None


def build_brief(n):
    t = INSTRUCTIONS.read_text(encoding="utf-8")
    t = t.replace("document-v6.md", f"docs/product-definition/v{n}.md")
    t = t.replace("الوثيقة في نسختها السادسة بعد ثلاث مراجعات مستقلة",
                  f"الوثيقة في نسختها {ORDINALS.get(n, 'رقم ' + str(n))} بعد عدة مراجعات مستقلة")
    pv = previous_verdict()
    ref = f" ويمكنك الرجوع إلى {pv} لتعرف ما حُسم في المراجعة السابقة فلا تكرره." if pv else ""
    t = t.replace("اكتب الإجابة كاملة في الملف review-codex.md في المجلد الحالي ولا تعدّل أي ملف آخر.",
                  f"هذه مهمة قراءة فقط: لا تعدّل أي ملف ولا تنشئ ملفاً ولا تلتزم شيئاً. اقرأ docs/product-definition/v{n}.md من المستودع الحالي.{ref} "
                  "اكتب المراجعة كاملة في رسالتك الأخيرة بصيغة Markdown، بلا مقدمة ولا خاتمة.")
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", type=int)
    ap.add_argument("--min-version", type=int, default=8)
    ap.add_argument("--effort", default="high")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--check-auth", action="store_true")
    ap.add_argument("--force", action="store_true", help="review even if a Codex review exists")
    a = ap.parse_args()

    if a.check_auth:
        print(codex_version(), "|", check_login()); return 0

    if a.version:
        n, path = a.version, DEFS / f"v{a.version}.md"
        if not path.exists():
            print(f"no such file: {path}"); return 3
    else:
        n, path = latest_version(a.min_version)
        if n is None:
            print(f"nothing to do: no v{a.min_version}+ product definition yet"); return 0

    have = sorted(REVIEWS.glob(f"*-codex-review-of-v{n}.md"))
    if have and not a.force:
        print(f"nothing to do: v{n} already has {have[-1].name}"); return 0

    today = datetime.date.today().isoformat()
    out = REVIEWS / f"{today}-codex-review-of-v{n}.md"
    if a.dry_run:
        print(f"would review {path.relative_to(ROOT)} -> {out.relative_to(ROOT)}"); return 0

    version = codex_version(); check_login()
    with tempfile.TemporaryDirectory() as tmp:
        last = Path(tmp) / "final.md"
        r = subprocess.run(["codex", "exec", "--sandbox", "read-only", "--ephemeral",
                            "-c", f"model_reasoning_effort={a.effort}", "-C", str(ROOT),
                            "-o", str(last), "-"],
                           input=build_brief(n), text=True, capture_output=True)
        text = last.read_text(encoding="utf-8").strip() if last.exists() else ""
    if r.returncode != 0 or len(text) < 200:
        fail(f"codex exec failed (exit {r.returncode}, {len(text)} chars); not saved\n{r.stderr[-1500:]}")
    dirty = subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain", "--", "docs/product-definition"],
                           capture_output=True, text=True).stdout.strip()
    if dirty:
        fail(f"product definition changed during a read-only review:\n{dirty}")
    header = (f"# مراجعة Codex للنسخة {n}\n\n"
              f"**التاريخ:** {today} • **المراجع:** {version} (Codex CLI، حساب ChatGPT، قراءة فقط، جهد {a.effort}) • "
              f"**التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • "
              f"**الوثيقة:** `docs/product-definition/v{n}.md` • المراجعة الدائمة (`scripts/codex-review.py`)، "
              f"المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.\n\n---\n\n")
    out.write_text(header + text + "\n", encoding="utf-8")
    print(f"saved {out.relative_to(ROOT)} ({len(text)} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
