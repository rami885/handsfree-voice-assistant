# برومبت التشغيل الليلي لـ Codex

**التاريخ:** 9 نوفمبر 2026 • **المستودع:** https://github.com/rami885/handsfree-voice-assistant • **يُستخدم مع:** `docs/phase-3-implementation/project-brief-for-codex.md` الإصدار 1.1

البرومبت بالإنجليزية لأنه أمر لوكيل برمجي. الشرح حوله بالعربية.

## 1. تجهيز بيئة Codex مرة واحدة

في إعدادات بيئة Codex للمستودع:

- **وصول الإنترنت للوكيل:** فعّله مع «الاعتماديات الشائعة» على الأقل، لأن Gradle يحتاج `dl.google.com` و`maven.google.com` و`repo.maven.apache.org` و`plugins.gradle.org` و`services.gradle.org`، وnpm يحتاج `registry.npmjs.org`.
- **متغير بيئة:** `ANDROID_HOME=/root/android-sdk`.
- **سكربت الإعداد:**

```bash
set -euo pipefail
export ANDROID_HOME=/root/android-sdk
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sSLo /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
java -version
node --version
```

إن فشل تنزيل الأدوات لأن رقم الإصدار قديم، فخذ الرابط الحالي من صفحة Android Studio في قسم «Command line tools only». البيئة تحتاج JDK 17 وNode LTS، والصورة الافتراضية لـ Codex تحملهما عادة؛ السطران الأخيران يتحققان من ذلك.

## 2. البرومبت

انسخ ما بين السطرين كما هو.

---

```
You are working alone overnight on the GitHub repository rami885/handsfree-voice-assistant. Nobody will answer questions until morning. Do not stop to ask for permission or confirmation. Keep working until every milestone M0 to M5 is done, or until you are truly blocked, and then write the final report described at the end.

SOURCE OF TRUTH
1. Read AGENTS.md fully. Its rules are absolute.
2. Read docs/phase-3-implementation/project-brief-for-codex.md (version 1.1) fully. It is the specification you implement. It is written in Arabic; code, identifiers, commit messages and code comments are in English.
3. Consult docs/product-definition/v12.md and docs/phase-2-implementation/phase-2-implementation-draft4.html only when the brief points to them or is silent.
Priority on conflict: AGENTS.md, then the brief, then v12, then draft4.

SCOPE
Implement milestones M0, M1, M2, M3, M4, M5 exactly as specified in sections 2, 4, 5, 6 and 7 of the brief, in that order. Do not build anything listed as out of scope (production tools-*, final setup screens, a networked planner, any deployment).

HARD RULES (never break, even to make CI pass)
- No AccessibilityService, no QUERY_ALL_PACKAGES, no hidden or @SystemApi APIs, no reflection on framework internals.
- No real API key, token or secret anywhere in the repository. Use placeholders and env variables.
- No INTERNET permission in the probes app. No network library in core-guard, core-intent or probes.
- Never disable, skip or delete a test or a lint rule to get green. Fix the cause or document the blocker.
- Do not edit anything under docs/product-definition/, docs/phase-2-implementation/ or docs/reviews/.
- Do not push to main, do not merge, do not rewrite history.

HOW TO WORK
- One milestone at a time. For each milestone:
  a. Implement it fully, with the unit tests the brief requires.
  b. Run locally: ./gradlew build test lint (and the Kover check from M1, and npm ci && npm test && npm run lint in backend-proxy for M5). Fix until green.
  c. Write docs/phase-3-implementation/m<N>-delivery-report.md in Arabic, following section 12 of the brief: pinned versions, status of every acceptance item (done / partial / not done), commands to run, what was not done and why, any conflict found between the brief and v12 or draft4 with a proposal, and every Android behavior claim not verified on a device marked «يُثبت ميدانياً».
  d. Commit with short English messages that start with "M<N>: ".
- Branching: if you can create branches and pull requests, follow section 9 of the brief (stacked branches codex/m<N>-<name>, one draft PR per milestone, PR description in Arabic). If your environment produces only one branch and one pull request per task, put all milestones on that single branch, keep the "M<N>: " commit prefixes, and open one draft pull request titled "M0-M5: phase 3 foundation" whose description lists each milestone with its status.
- Android emulator: the brief requires an emulator smoke test for M3 and M4 in GitHub Actions. Write that workflow and test. If you cannot run an emulator in your own environment, still write them, make sure they compile with ./gradlew assembleAndroidTest, and say in the report that they were not executed locally.
- Pin the build matrix in M0 (AGP that supports compileSdk 36, matching Gradle, Kotlin, KSP, Hilt) and keep it unless a later milestone proves it broken; record any change and the reason.

WHEN SOMETHING IS UNCLEAR OR BLOCKED
- Ambiguity: choose the option most consistent with AGENTS.md and the brief, implement it, and record the decision and the alternative in the milestone report under a heading "قرارات اتخذتها".
- Blocker for one part (a missing SDK package, a network failure, an API that does not exist): retry up to three times with backoff, then implement everything else in that milestone, leave a clear TODO with the reason, record it in the report, and move to the next milestone if it does not depend on the blocked part.
- Never invent results. Never claim a test passed if it did not run.

FINAL REPORT
When you finish, or when you can go no further, write docs/phase-3-implementation/overnight-run-report.md in Arabic with:
1. A table: milestone, status, branch or commit, CI result.
2. Every decision you took under ambiguity.
3. Every blocker with what you tried.
4. What Rami must do next on real devices with the probes app (step by step, per experiment T1a to T13).
5. Open questions for the morning review by Claude, Codex and Gemini.
Commit it and include its content in the pull request description.

SHUT DOWN THE COMPUTER (very last step)
Only after the final report is committed and pushed, and the draft pull request is open, shut down the computer you are running on. Do nothing after this step.
- First check that git status is clean and that the last commit is on the remote. If the push failed, do not shut down; write the reason at the top of overnight-run-report.md and stop.
- Windows: run `shutdown /s /t 60`
- macOS: run `osascript -e 'tell application "System Events" to shut down'`
- Linux: run `systemctl poweroff`
- If you are running in a cloud container instead of Rami's computer, or the command is blocked by the sandbox, do not retry and do not look for workarounds; add one line to the end of overnight-run-report.md saying the shutdown was not possible and why.
```

---

## 3. بعد انتهاء Codex

1. افتح طلب السحب أو الطلبات التي فتحها، واقرأ `overnight-run-report.md`.
2. قل لـ Claude «راجع عمل Codex». يراجع كل معلم مع Codex بوضع القراءة فقط، ومع Gemini إن رُبط، ثم يكتب حكم دمج بنداً بنداً قبل أي دمج.
3. لا تدمج شيئاً قبل حكم الدمج.

## 4. التشغيل على كمبيوترك مع إطفائه بعد الانتهاء

البرومبت نفسه يأمر Codex بإطفاء الجهاز كآخر خطوة، بعد دفع التقرير وفتح طلب السحب، وبمهلة دقيقة. لكي يُسمح له بتنفيذ أمر الإطفاء، يجب تشغيله بصلاحية الجهاز الكاملة (`--sandbox danger-full-access`)؛ صندوق `workspace-write` يمنع الإطفاء. هذه الصلاحية تسمح له بالكتابة خارج المستودع أيضاً، والبرومبت يقيّده بالمستودع.

السطر الأخير في كل أمر أدناه احتياط: إن توقف Codex قبل الإطفاء لأي سبب، يطفئ الجهاز نفسه بعد خروج Codex. على GitHub (Codex السحابي) لا يمكن الإطفاء، وسيكتب Codex ذلك في التقرير.

**الشروط مرة واحدة:**
- Codex CLI مثبت ومسجّل: `npm i -g @openai/codex` ثم `codex login`.
- نسخة من المستودع على جهازك، وgit مسجّل بحسابك ويستطيع الدفع.
- JDK 17 وAndroid SDK (المنصة 36) وNode LTS مثبتة، و`ANDROID_HOME` مضبوط.
- الجهاز موصول بالكهرباء، والسكون التلقائي معطّل لهذه الليلة.

**Windows (PowerShell)، من مجلد المستودع:**

```powershell
git pull
Get-Content docs\phase-3-implementation\codex-overnight-prompt.txt -Raw | codex exec --sandbox danger-full-access -c model_reasoning_effort=high -C . - *> overnight-codex.log
shutdown /s /t 60
```

للإلغاء خلال الدقيقة: `shutdown /a`.

**macOS (Terminal)، من مجلد المستودع:**

```bash
git pull
codex exec --sandbox danger-full-access -c model_reasoning_effort=high -C . - < docs/phase-3-implementation/codex-overnight-prompt.txt > overnight-codex.log 2>&1
sleep 60; osascript -e 'tell application "System Events" to shut down'
```

للإلغاء خلال الدقيقة: اضغط Ctrl+C.

**Linux، من مجلد المستودع:**

```bash
git pull
codex exec --sandbox danger-full-access -c model_reasoning_effort=high -C . - < docs/phase-3-implementation/codex-overnight-prompt.txt > overnight-codex.log 2>&1
sleep 60; systemctl poweroff
```

**ملاحظات:**
- السجل الكامل في `overnight-codex.log` داخل مجلد المستودع. لا تلتزمه، فقد يحوي مسارات من جهازك.
- `--sandbox danger-full-access` يعطي Codex صلاحية الجهاز كاملة ليستطيع الإطفاء والتنزيل والدفع. إن لم ترد الإطفاء من داخل Codex، استبدله بـ `--sandbox workspace-write -c sandbox_workspace_write.network_access=true` ويبقى سطر الإطفاء الاحتياطي يعمل.
- في الصباح شغّل الجهاز، وافتح طلب السحب، وقل لـ Claude «راجع عمل Codex».
