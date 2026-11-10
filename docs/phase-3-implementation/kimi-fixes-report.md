# تقرير إصلاح بنود مراجعة Kimi للمعالم M0 إلى M5

**التاريخ:** 10 نوفمبر 2026. **الفرع:** `codex/kimi-fixes`، انطلاقاً من `origin/main`.

النطاق هو البنود المقبولة في `docs/reviews/2026-11-10-merged-verdict-phase3-m0-m5-addendum-3.md`، استناداً إلى `docs/reviews/2026-11-10-kimi-review-of-m0-m5.md`.

| البند | ما تغيّر | الاختبار الذي يكشف الخلل |
|---|---|---|
| K-3 | فصل الموافقات في `ProbeConsentStore` داخل عملية التطبيق، لكل قدرة على حدة. كل خانة تحدّث قدرتها فوراً؛ لا يحمل `ProbeConfig` موافقات، ولا يمحو بدء تجربة موافقات القدرات الأخرى. انتقلت قراءات المتحكم والخدمات والتكاملات إلى المخزن، وسحب الموافقة يوقف العمل الجاري. | `CapabilityConsentTest.checkboxesUpdateOnlyTheirCapabilityAcrossScreensAndStarts` يغطي التنقل بين الشاشات، بدء T9، واستقلال الموافقات وسحبها؛ وتغطي `ProxyCancellationTest` و`BluetoothConsentTest` و`NotificationConsentTest` إيقاف العمل ومسح البيانات عند السحب. |
| K-4 | معرّف فريد لكل جملة، ومطابقة `utteranceId` في نداءات الإكمال والخطأ والإيقاف. انتهاء مهلة النطق يستدعي إيقاف TTS. | اختباري `UtteranceLifecycleTest`: تجاهل نداءات جمل قديمة والمعرّف الغائب، قبول النداءات المطابقة، وإيقاف المحرك عند مهلة 15 ثانية مع تفريغ النص المعروض. |
| K-5 | استبدال الجملة التي تحتوي كلمة القياس `stop` في القائمة الثابتة المستخدمة في T13. | `FixedSentencesTest.t13PlaybackDoesNotContainTheMeasuredStopWord` يفحص الجمل العشر كاملة. |
| K-6 | تحرير المفوّض وردّ الاتصال عند الإيقاف وتدمير عميل التعرّف. إن لم تكن الجلسة قد اكتملت، يُرسل `ERROR_NO_MATCH` لإنهاء جلسة إطار العمل أيضاً؛ نداءات المفوّض القديم لا تُمرّر إلى جلسة جديدة. | `ProxyStopTest.stoppingReleasesTheDelegateAndAllowsTheNextStart` يتحقق من التحرير والتدمير وبدء جلسة تالية بلا BUSY؛ و`ProxyStopFrameworkTest.stopAlsoReleasesTheFrameworkCallerSession` يتحقق من تحرير جلسة إطار العمل بعد الرد النهائي. |
| K-7 | تصفير `emptyWindows` بعد إطلاق التنبيه، وعند تغير التجربة سواء عند البدء أو عند الاستماع. | `EmptyWindowsTest.warningsRequireTwoFreshEmptyWindowsAndResetOnEveryExperimentChange` يغطي دورات التنبيه المتكررة، تغير التجربة، النتيجة غير الفارغة، والانتقال إلى تجربة لا تستمع. |
| K-8 | إضافة `'` داخل حقل CSV قبل القيم التي تبدأ بـ `=` أو `+` أو `-` أو `@` أو محرف الجدولة أو الرجوع، مع استمرار اقتباس علامات التنصيص والأسطر. يحتفظ JSON بالقيمة الأصلية. | `QueuedExportTest.csvNeutralizesEveryFormulaPrefixWhileJsonPreservesTheOriginalValues` يصدّر عبر مزود محتوى اختباري ويفحص البادئات الست والقيم العادية والفارغة، ثم يقارن JSON بالأصل. |
| K-9 | ترتيب مفاتيح `canonical()` بمقارنة وحدات UTF-16 باستخدام `<` و`>` بدلاً من `localeCompare`. | `identity-canonical.test.ts` يغطي الأحرف الكبيرة والصغيرة، الحروف ذات العلامات، وزوجاً بديلاً مقابل محرف من المنطقة الخاصة. |
| K-N3 | فحص نصي لمصادر Kotlin وJava في `core/*` و`probes` يرفض `java.net.URL` و`HttpURLConnection` و`java.net.Socket` و`okhttp`، ضمن `check-safety.py` المرتبط بالبناء. | `scripts/test-safety.py::SafetyTest.test_network_apis_are_rejected_in_all_core_and_probes_sources` يحقن كل مرجع في كل وحدة محمية داخل مستودعات Git مؤقتة ويتوقع الفشل. مهمة `safetyPolicyTest` مرتبطة بـ `check`. |
| K-N4 | توسيع أنماط فحص الأسرار لتشمل `sk-or-` و`ghp_` و`github_pat_` و`xoxb-` و`xoxp-` ورموز `AQ.`، مع عدم طباعة القيم المطابقة. | `scripts/test-safety.py::SafetyTest.test_secret_patterns_fail_without_printing_tokens` يتضمن حالة سلبية لكل بادئة جديدة ويحافظ على تغطية الأنماط السابقة؛ واختبار مستقل يقبل النصوص العادية والبادئات القصيرة. |
| K-N6 | تحويل ردود Google من فئة 5xx وأخطاء الشبكة في مرحلتي OAuth وفك Integrity إلى `503 integrity_unavailable`. يبقى رفض فك الرمز `401`؛ وتظل اختبارات رفض الحكم والمهلة وإلغاء العميل قائمة. | `integrity.test.ts`: اختبار مرحلتي OAuth وفك الرمز مع 500 و502 و503 وخطأ شبكة، واختبار إبقاء رفض 400 و401 و403 من نقطة فك الرمز كـ401. |

## التحقق

أُعيد السلوك المعيب مؤقتاً للتحقق العكسي، ثم أُعيدت الإصلاحات: فشلت الاختبارات المقصودة للموافقات والنطق والجمل والإيقاف والعدّاد والتصدير، وفشلت اختبارات ترتيب المفاتيح وأعطال Google، وكذلك اختبارات فحص مصادر الشبكة وأنماط الأسرار. لم يُعطّل أي اختبار أو فحص lint.

نتائج الأوامر المطلوبة:

- `./gradlew build test lint koverVerify assembleAndroidTest`: نجح، 523 مهمة؛ شمل فحوص الأمان واختباراتها السلبية وحد التغطية وlint وبناء حزم اختبارات Android.
- `cd backend-proxy && npm ci && npm test && npm run lint`: نجح؛ 51 اختباراً في 9 ملفات، ثم TypeScript وESLint.
- `git diff --check`: نجح.

## حدود النتائج

التحقق آلي محلي باستخدام اختبارات JVM وRobolectric وVitest والفحوص الساكنة. بناء حزم `androidTest` لا يعني تشغيلها على جهاز. سلوك Android الفعلي، وتوقيت إغلاق الميكروفون، وTTS عبر السيارة، ونتائج التعرّف بعد الإيقاف، وربط Google Play Integrity: **«يُثبت ميدانياً»**. تاريخ هذا الوصف 10 نوفمبر 2026؛ لم تُجرَ تجارب أجهزة ضمن هذه الدفعة.
