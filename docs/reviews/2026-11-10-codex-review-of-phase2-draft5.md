# مراجعة Codex للمسودة 5 من المرحلة 2

**التاريخ:** 10 نوفمبر 2026 • **المراجع:** Codex CLI 0.167.0 (حساب ChatGPT، قراءة فقط، جهد high) • **الوثيقة:** `docs/phase-2-implementation/phase-2-implementation-draft5.html` مقابل `docs/product-definition/v13.md` • المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.

---

- **06 — ERROR:** تجربة T3 لا تعزل توقف الالتقاط بسبب النوم؛ فمستقبل `ACTION_SCREEN_OFF` في [ProbeController.kt](/home/user/handsfree-voice-assistant/probes/src/main/kotlin/com/handsfree/probes/ProbeController.kt:64) يوقف خدمة الميكروفون ويلغي التجربة برمجياً، بينما [الإشارة تعني انتقال الجهاز إلى الحالة غير التفاعلية](https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF)، فتختلط نتيجة A-5 بسلوك التطبيق نفسه؛ تحقق 2026-11-10، «يُثبت ميدانياً».

- **06 — ERROR:** تنفيذ T6 أضعف من معيار الخطة؛ إذ يكمل [IntegrationProbes.kt](/home/user/handsfree-voice-assistant/probes/src/main/kotlin/com/handsfree/probes/IntegrationProbes.kt:51) القياس عند أول metadata دون اشتراط المطابقة أو `STATE_PLAYING`، وينتظر عشرين ثانية بدل أربع، مع أن [بيانات المقطع وحالة التشغيل واجهتان مستقلتان](https://developer.android.com/reference/android/media/session/MediaController)؛ تحقق 2026-11-10، «يُثبت ميدانياً».

- **06 — ERROR:** المئين 90 في T4 قد يخفي معظم المحاولات الفاشلة؛ فـ[measureSco()](/home/user/handsfree-voice-assistant/probes/src/main/kotlin/com/handsfree/probes/ProbeController.kt:228) يحسبه من الفتحات الناجحة وحدها، ولذلك يمكن لمحاولة ناجحة واحدة وتسع عشرة مهلة منتهية أن تنتج زمناً دون الثانيتين.

- **06 — MISSING:** بوابة T1b تقيس التقاط `Answer` دون اشتراط نجاح الرد الفعلي، كما أن [screened()](/home/user/handsfree-voice-assistant/probes/src/main/kotlin/com/handsfree/probes/ProbeController.kt:189) لا يطلب التركيز الصوتي ويسجّل `audio_only_requested` دون حكم يربطه بانتقال حالة الاشتراك، رغم وصف التجربة بأنها السلسلة الكاملة.

- **03 — CONTRADICTION:** وضع عميل الوسيط و`ClaudePlanner` داخل `core-intent` يناقض حظر الشبكة في القسم 04، ويتعذر تنفيذه مع [verifyNoNetwork](/home/user/handsfree-voice-assistant/build.gradle.kts:57) الذي يمنع OkHttp واعتماديات الشبكة في الوحدة نفسها.

- **05 — CONTRADICTION:** أسماء الأدوات المقترحة مثل `calls.last_incoming` و`media.play_from_search` مرفوضة بعقد الكود المدموج؛ فـ[ToolSpec](/home/user/handsfree-voice-assistant/core/guard/src/main/kotlin/com/handsfree/guard/Types.kt:13) و[planInput](/home/user/handsfree-voice-assistant/backend-proxy/src/provider.ts:5) يسمحان بالحروف والشرطة السفلية فقط، دون النقطة.

- **07 — CONTRADICTION:** بناء سجل المكالمات في M7 قبل الوسائط في M8 يخالف ترتيب [v13، القسم 13](/home/user/handsfree-voice-assistant/docs/product-definition/v13.md:391)، الذي يشترط إثبات المسارين الأول والثاني قبل طلب `READ_CALL_LOG`.

- **06 — CONTRADICTION:** وصف T10 بأنها مبنية لا يغطي معيارها المعلن للقراءة والإرسال مع مفتاح النتائج الشخصية؛ فـ[ProbeSession](/home/user/handsfree-voice-assistant/probes/src/main/kotlin/com/handsfree/probes/ProbeSession.kt:39) يتيح في T10 الاتصال والملاحة والمؤقّت فقط، دون فعل قراءة أو إرسال لاختبار المنع.

- **08 — MISSING:** حالة «`Cancel`، ثم قفل الشاشة» لا تثبت أن القفل يلغي المهمة مستقلاً؛ فنجاح الإلغاء الأول يخفي تعطل الثاني، بينما [v13، القسم 10](/home/user/handsfree-voice-assistant/docs/product-definition/v13.md:315) يلزم بكليهما ويشمل حوار التأكيد والسلسلة غير المكتملة.

- **03 — MISSING:** مسار البلاغ يتوقف عند الحفظ المحلي دون معلم لإرساله ومعالجته؛ فـ[backend-proxy/src/index.ts](/home/user/handsfree-voice-assistant/backend-proxy/src/index.ts:27) يقبل التسجيل والتخطيط فقط، بينما v13 تشترط إرسال البلاغ بعد التأكيد وآلية لمعالجته.

- **07 — RISK:** قد يتعطل قبول M7 بالهوية الحقيقية قبل حسم قناة الاختبار، لأن [integrity.ts](/home/user/handsfree-voice-assistant/backend-proxy/src/integrity.ts:61) يشترط `PLAY_RECOGNIZED` المطابق، وفق [Google](https://developer.android.com/google/play/integrity/verdicts)، لإصدارات وشهادات موزعة عبر Play، بينما القسم 10 يؤخر النشر دون تمييز صريح بين الاختبار والإنتاج؛ تحقق 2026-11-10، «يُثبت ميدانياً».

- **07 — RISK:** تأجيل الإعداد كله إلى M10 يترك المعالم السابقة بلا مسار مستخدم لاختبار الموافقات والشريحة والمشغّل المحفوظ، وهي تبعيات صريحة للمسارات في القسم 05 وليست تفاصيل بصرية يمكن تأجيلها كلها.

- **06 — SUGGESTION:** الأولوية 1: أصلح بروتوكول T3 لتمييز الإيقاف البرمجي عن توقف عينات الصوت، مع تسجيل سبب الإنهاء ومدة القياس، استناداً إلى تعارض مستقبل الشاشة مع هدف A-5.

- **06 — SUGGESTION:** الأولوية 2: اجعل T1b وT6 تنتجان حكماً على النتيجة النهائية، وافصل في T4 معدل نجاح الفتح عن توزيعه الزمني، لأن سجلات الطلب والـmetadata والأزمنة الناجحة وحدها لا تطابق شروط البوابات.

- **03 — SUGGESTION:** الأولوية 3: ضع تنفيذ المخطّط الشبكي في وحدة مستقلة وأضف تحقق عقد بين مواصفة الأدوات والحارس والوسيط، لأن حظر الاعتماديات وقواعد الأسماء الحالية يمنعان التكامل الموصوف.

- **07 — SUGGESTION:** الأولوية 4: قدّم الوسائط على سجل المكالمات، وابنِ إعداداً وظيفياً أدنى في M6 وحدد قناة Integrity قبل المعلم المتصل، وفق ترتيب v13 وتبعيات القسم 05.

- **08 — SUGGESTION:** الأولوية 5: افصل اختبارات الإلغاء والقفل، وألحق البلاغ والإعداد الموقّع بمعالم قبول محددة، لأن جدول M6–M11 لا يعيّن تسليماً صريحاً لهاتين القدرتين المطلوبتين في v13.
