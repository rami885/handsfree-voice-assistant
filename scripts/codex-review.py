#!/usr/bin/env python3
"""Standing Codex review of the product definition.

Finds the newest docs/product-definition/v<N>.md (N >= --min-version, default 8),
and if docs/reviews/ holds no *-codex-review-of-v<N>.md yet, sends the document
with docs/reviews/review-instructions-v6.md (version number adjusted) to an OpenAI
model through the Responses API and saves the answer verbatim as
docs/reviews/<YYYY-MM-DD>-codex-review-of-v<N>.md.

The API key is injected by the session's network proxy for api.openai.com; set
OPENAI_API_KEY only when running outside that environment. No key is stored here.

Usage:
  python3 scripts/codex-review.py            # review the newest unreviewed version, if any
  python3 scripts/codex-review.py --dry-run  # show what would be reviewed, call nothing
  python3 scripts/codex-review.py --check-auth
  python3 scripts/codex-review.py --version 8 --model gpt-5.4
Exit codes: 0 done or nothing to do, 2 auth or API failure, 3 bad arguments.
"""
import argparse, datetime, json, os, re, sys, urllib.error, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFS = ROOT / "docs" / "product-definition"
REVIEWS = ROOT / "docs" / "reviews"
INSTRUCTIONS = REVIEWS / "review-instructions-v6.md"
API = "https://api.openai.com/v1"
DEFAULT_MODEL = "gpt-5.4"
PREFERRED = ["gpt-6.1", "gpt-6", "gpt-5.5", "gpt-5.4", "gpt-5.2", "gpt-5.1", "gpt-5"]
ORDINALS = {6: "السادسة", 7: "السابعة", 8: "الثامنة", 9: "التاسعة", 10: "العاشرة",
            11: "الحادية عشرة", 12: "الثانية عشرة"}


def headers():
    return {"Authorization": "Bearer " + os.environ.get("OPENAI_API_KEY", "proxy-injected"),
            "Content-Type": "application/json"}


def api(path, body=None, timeout=900):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, headers=headers(), method="POST" if data else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        msg = e.read().decode(errors="replace")[:600]
        raise SystemExit(f"API {e.code} on {path}: {msg}")


def latest_version(min_version):
    found = []
    for p in DEFS.glob("v*.md"):
        m = re.fullmatch(r"v(\d+)\.md", p.name)
        if m and int(m.group(1)) >= min_version:
            found.append((int(m.group(1)), p))
    return max(found) if found else (None, None)


def existing_review(n):
    return sorted(REVIEWS.glob(f"*-codex-review-of-v{n}.md"))


def build_prompt(n, doc_text):
    t = INSTRUCTIONS.read_text(encoding="utf-8")
    t = t.replace("document-v6.md", f"document-v{n}.md")
    t = t.replace("الوثيقة في نسختها السادسة بعد ثلاث مراجعات مستقلة",
                  f"الوثيقة في نسختها {ORDINALS.get(n, 'رقم ' + str(n))} بعد عدة مراجعات مستقلة")
    t = t.replace("اكتب الإجابة كاملة في الملف review-codex.md في المجلد الحالي ولا تعدّل أي ملف آخر.",
                  "اكتب الإجابة كاملة في ردك مباشرة بصيغة Markdown، بلا مقدمة ولا خاتمة.")
    return f"{t}\n\n---\n\nمحتوى الملف document-v{n}.md:\n\n{doc_text}"


def pick_model(explicit):
    if explicit:
        return explicit
    try:
        ids = {m.get("id") for m in api("/models", timeout=60).get("data", [])}
    except SystemExit:
        return DEFAULT_MODEL
    for m in PREFERRED:
        if m in ids:
            return m
    gpt = sorted((i for i in ids if i and i.startswith("gpt-") and "realtime" not in i and "audio" not in i), reverse=True)
    if not gpt:
        raise SystemExit("no gpt-* model visible to this key")
    return gpt[0]


def extract_text(resp):
    parts = []
    for item in resp.get("output", []):
        if item.get("type") == "message":
            for c in item.get("content", []):
                if c.get("type") == "output_text":
                    parts.append(c.get("text", ""))
    return "\n".join(parts).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", type=int, help="review this version instead of the newest")
    ap.add_argument("--min-version", type=int, default=8)
    ap.add_argument("--model")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--check-auth", action="store_true")
    ap.add_argument("--force", action="store_true", help="review even if a Codex review exists")
    a = ap.parse_args()

    if a.check_auth:
        r = api("/responses", {"model": pick_model(a.model), "input": "Reply with the single word OK."}, timeout=120)
        print("auth ok, model:", r.get("model"), "answer:", extract_text(r)[:40])
        return 0

    if a.version:
        n, path = a.version, DEFS / f"v{a.version}.md"
        if not path.exists():
            print(f"no such file: {path}"); return 3
    else:
        n, path = latest_version(a.min_version)
        if n is None:
            print(f"nothing to do: no v{a.min_version}+ product definition yet"); return 0

    have = existing_review(n)
    if have and not a.force:
        print(f"nothing to do: v{n} already has {have[-1].name}"); return 0

    out = REVIEWS / f"{datetime.date.today().isoformat()}-codex-review-of-v{n}.md"
    if a.dry_run:
        print(f"would review {path.relative_to(ROOT)} -> {out.relative_to(ROOT)}"); return 0

    model = pick_model(a.model)
    prompt = build_prompt(n, path.read_text(encoding="utf-8"))
    body = {"model": model, "input": prompt}
    if model.startswith(("gpt-5", "gpt-6", "o")):
        body["reasoning"] = {"effort": "high"}
    resp = api("/responses", body)
    text = extract_text(resp)
    if len(text) < 200:
        raise SystemExit(f"answer too short ({len(text)} chars); not saved")
    header = (f"# مراجعة Codex للنسخة {n}\n\n"
              f"**التاريخ:** {datetime.date.today().isoformat()} • **النموذج:** {resp.get('model', model)} عبر OpenAI API • "
              f"**التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • "
              f"**الوثيقة:** `docs/product-definition/v{n}.md` • مراجعة آلية دائمة (`scripts/codex-review.py`)، "
              f"المخرجات كما وردت. حكم الدمج يُكتب يدوياً في ملف منفصل.\n\n---\n\n")
    out.write_text(header + text + "\n", encoding="utf-8")
    print(f"saved {out.relative_to(ROOT)} ({len(text)} chars, model {resp.get('model', model)})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit as e:
        if isinstance(e.code, str):
            print(e.code, file=sys.stderr); sys.exit(2)
        raise
