# مراجعة Codex للنسخة 12

**التاريخ:** 2026-11-09 • **المراجع:** codex-cli 0.167.0 (Codex CLI، حساب ChatGPT، قراءة فقط، جهد high) • **التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • **الوثيقة:** `docs/product-definition/v12.md` • المراجعة الدائمة (`scripts/codex-review.py`)، المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.

---

- **5 — ERROR:** إعلان `foregroundServiceType="microphone"` مطلوب منذ Android 11 للتطبيقات المستهدِفة له، وليس ابتداءً من Android 14؛ الأخير أضاف إذن `FOREGROUND_SERVICE_MICROPHONE` المنفصل. ([Android](https://developer.android.com/about/versions/11/privacy/foreground-services)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **3،5 — ERROR:** إيقاف مؤقّت محدد غير مكتمل التعريف، لأن `ACTION_DISMISS_TIMER` يحتاج رابط المؤقّت، وبدونه يوقف جميع المؤقّتات المنتهية، فلا يضمن إلغاء مؤقّت جارٍ أو اختيار واحد بعينه. ([AlarmClock](https://developer.android.com/reference/android/provider/AlarmClock#ACTION_DISMISS_TIMER)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **3 — ERROR:** الاعتماد على طابع آخر رسالة وحده قد يُسقط محتوى جديداً يحمل الطابع نفسه، إذ يسمح `MessagingStyle.Message` صراحةً بتمثيل أجزاء الرسالة المتعددة بطابع مشترك؛ يلزم تمييز العناصر داخل الطابع أيضاً. ([Android](https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **3،6 — MISSING:** لم تُحسم سياسة الرد على مكالمات الفيديو فوق القفل، بينما `acceptRingingCall()` بلا وسيط يقبل حالة الفيديو المطلوبة؛ ينبغي تحديد الرد بصوت فقط عبر `acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)` واختبار رفض التخفيض. ([TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager#acceptRingingCall())، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **5،13 — MISSING:** يلزم اشتراط إرسال استجابة السماح فوراً قبل النطق والتعرف، لأن `CallScreeningService` يُستدعى قبل الرنين، والنظام يؤخر الرنين حتى الاستجابة أو انتهاء المهلة، حتى عندما تستخدم الخدمة للتعريف بالمتصل فقط. ([Android](https://developer.android.com/reference/android/telecom/CallScreeningService)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **5،7 — MISSING:** وعد «لا تُلتقط لقطات شاشة» يحتاج تعطيل سياق الشاشة صراحةً عند استدعاء المساعد، عبر `setDisabledShowContext(SHOW_WITH_SCREENSHOT | SHOW_WITH_ASSIST)` ما دام المنتج لا يستخدمه. ([VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService#setDisabledShowContext(int))، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **5،11 — MISSING:** البحث والفهرسة في الصور يحتاجان معالجة تغيّر الصور المسموح بها وانتهاء صلاحية الوصول، لأن Android يوصي بإعادة استعلام `MediaStore` وعدم اعتبار الذاكرة المؤقتة أو صلاحية URI دائمة. ([الوصول الجزئي](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **10،11 — MISSING:** تنقص النسخة المنشورة آلية للإبلاغ عن الردود المولّدة المسيئة ومعالجة البلاغات، إذ تشترط سياسة المحتوى المولّد بالذكاء الاصطناعي الإبلاغ داخل التطبيق دون مغادرته. ([سياسة Play](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en)، تحقق: 2026-11-09).

- **5،12 — MISSING:** عبارة «مسموح مع إعلان النوع» لا تستوعب متطلبات النشر للخدمة الأمامية، إذ يلزم أيضاً تصريح Play Console وتبرير الاستخدام وفيديو يوضح كل ميزة تستخدم الخدمة. ([متطلبات Play](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)، تحقق: 2026-11-09).

- **8،11 — CONTRADICTION:** النفي المطلق لقراءة الرسائل القديمة أو القراءة بلا إشعار قائم يتعارض مع إضافة `Read my last SMS` عبر `READ_SMS` في الدفعة الثانية؛ ينبغي استثناء أرشيف SMS صراحةً من حدود الإشعارات.

- **3،8،11 — CONTRADICTION:** تعليق Spotify على الحسم القانوني يجاوره وعد بالتحكم الصوتي «لأي مشغّل»، مع أن البند III.3 يتعلق بالتحكم الصوتي عموماً؛ يجب أن يشمل التعليق أوامر التشغيل العامة أيضاً حتى يُحسم نطاق السياسة. ([Spotify](https://developer.spotify.com/policy)، تحقق: 2026-11-09).

- **3 — SUGGESTION:** أضف `Call Anna on speaker` بتصنيف **بلا يدين عبر واجهة رسمية**، لأن `EXTRA_START_CALL_WITH_SPEAKERPHONE` يطلب المكبر عند بدء الاتصال، قبل فقدان إمكانية استقبال الأوامر أثناء المكالمة. ([TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager#EXTRA_START_CALL_WITH_SPEAKERPHONE)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **3،5 — SUGGESTION:** أضف `Use SIM 2 for this call` بتصنيف **بلا يدين عبر واجهة رسمية**، باستخدام `EXTRA_PHONE_ACCOUNT_HANDLE` لتغيير شريحة هذه المكالمة فقط بعد إعادة التأكيد، بدلاً من تعديل الإعداد الدائم. ([TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE)، تحقق: 2026-11-09؛ يُثبت ميدانياً).

- **3،6،11 — SUGGESTION:** **الأولوية 1:** ثبّت سياسة الرد بصوت فقط، وأضف حالة قبول لمكالمة فيديو والشاشة مقفلة، لأن النسخة بلا وسيط من `acceptRingingCall` تقبل حالة الفيديو الواردة؛ يُثبت ميدانياً.

- **3،10،11 — SUGGESTION:** **الأولوية 2:** اختبر تحديث إشعار بالمفتاح والطابع نفسيهما مع إضافة نص جديد، واجعل معيار النجاح قراءة كل عنصر مرة واحدة، لأن طابع `MessagingStyle.Message` ليس معرّفاً فريداً؛ يُثبت ميدانياً.

- **5،13 — SUGGESTION:** **الأولوية 3:** اجعل استجابة السماح في `CallScreeningService` سابقة لأي عمل صوتي، وقِس التأخير حتى الرنين ضمن المسار الكامل، لأن الخدمة تقع قبل بدء الرنين؛ يُثبت ميدانياً.

- **3،5،11 — SUGGESTION:** **الأولوية 4:** افصل قبول إيقاف المؤقّت المنتهي عن إلغاء المؤقّت الجاري، واختبر مؤقّتين متزامنين، لأن `ACTION_DISMISS_TIMER` بلا URI يستهدف جميع المنتهية؛ يُثبت ميدانياً.

- **5،10،11،12 — SUGGESTION:** **الأولوية 5:** أضف شروط نشر قابلة للمراجعة تشمل بلاغات المحتوى المولّد وتصريح الخدمة الأمامية وفيديوها، لأن سياسات Play المذكورة تفرضها قبل التوزيع عبر المتجر؛ تحقق: 2026-11-09.
