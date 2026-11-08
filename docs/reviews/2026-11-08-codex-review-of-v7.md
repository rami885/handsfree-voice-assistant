# مراجعة Codex للنسخة 7

**التاريخ:** 8 نوفمبر 2026 • **المراجع:** Codex CLI 0.167.0 (حساب ChatGPT، قراءة فقط، جهد high) عبر مهارة codex-delegate • **التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • **الوثيقة:** `docs/product-definition/v7.md` • المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.

---

- **13 — ERROR:** مراقبة حالة المكالمة تحتاج `READ_PHONE_STATE` حتى دون رقم؛ الدليل: توثيق [`TelephonyCallback.CallStateListener`](https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener) يشترط الإذن صراحةً، خلاف مسار الإثبات المقترح (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3،5 — ERROR:** استخدام `endCall` للرفض يحتاج حماية من تغيّر حالة المكالمات؛ الدليل: الواجهة تنهي المكالمة النشطة بالأولوية عند تعدد المكالمات، وقد تقطع مكالمة أخرى بدلاً من رفض الواردة في حالة الانتظار ([TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager#endCall())؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5 — ERROR:** وصف مسار `playFromSearch` و`seekTo` بأنه «بلا إذن» ناقص؛ الدليل: الحصول على جلسات التطبيقات الأخرى عبر [`getActiveSessions`](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)) يتطلب وصول الإشعارات أو `MEDIA_CONTENT_CONTROL` المحمي (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5 — ERROR:** حصر إطلاق الأنشطة من الخلفية في `startAssistantActivity` غير دقيق؛ الدليل: توجد استثناءات أخرى في [قواعد BAL](https://developer.android.com/guide/components/activities/secure-bal)، كما تتيح جلسة المساعد [`startVoiceActivity`](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#startVoiceActivity(android.content.Intent))، فيجب فصل شرط السماح عن اختيار الواجهة (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3،5 — ERROR:** إدراج موعد لا يكفي لتنفيذ تذكير مؤرّخ؛ الدليل: [`CalendarContract.Reminders`](https://developer.android.com/identity/providers/calendar-provider#reminders) جدول مستقل يحتاج ربطاً بالحدث وتحديد وقت التنبيه وطريقته (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6 — ERROR:** عبارة «لا شيء» يمنع الملاحة والمؤقّت فوق القفل ضمان زائد؛ الدليل: دور المساعد لا يفتح القفل الآمن، وإظهار نشاط التطبيق المستهدف أو طلب المصادقة يخضع لسلوكه و[`KeyguardManager`](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback)) (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3،5 — MISSING:** مسار المؤقّت والمنبّه يغفل طلب تجاوز الواجهة؛ الدليل: [`EXTRA_SKIP_UI`](https://developer.android.com/reference/android/provider/AlarmClock#EXTRA_SKIP_UI) افتراضه `false`، ولذلك يلزم إرساله واختبار تطبيق الساعة قبل تصنيف المسار بلا يدين (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5 — MISSING:** الوصول الواسع للصور يحتاج بوابة قبول وخطة بديلة؛ الدليل: [سياسة Play](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en) تشترط وظيفة أساسية لا يكفيها المنتقي، ورفض التصريح يحوّل الاختيار عبر Photo Picker إلى «يحتاج لمسة» (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3،12 — MISSING:** ينقص حسم نطاق شروط Spotify قبل ضمان تكامله؛ الدليل: [السياسة III.3](https://developer.spotify.com/policy) تحظر بناء مساعد صوتي باستخدام Spotify Platform، بينما انطباق تعريف المنصة على نوايا Android المقترحة يحتاج تحليلاً مستقلاً (تحقق 2026-11-08).

- **3،10 — MISSING:** اسم مرسل الإشعار لا يثبت مستلم الرد؛ الدليل: [`MessagingStyle`](https://developer.android.com/reference/android/app/Notification.MessagingStyle) يميّز المرسل والمحادثة الجماعية، وقد يعني الرد على رسالة Nikos إرسالاً إلى المجموعة، فيلزم نطق وجهة المحادثة (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6،10 — MISSING:** مفتاح النتائج الشخصية فوق القفل لا يغطي الملاحظات والتقويم وسجل المكالمات؛ الدليل: جدول القسم 6 يسمح بالسجل بعد أول فتح، رغم إقرار القسم 10 بأن التأكيد الصوتي لا يثبت الهوية.

- **7 — CONTRADICTION:** عمود «يُحفظ عند المزود» ينفي الحفظ بينما عمود الاحتفاظ يقر بإمكان تخزين السجلات؛ الدليل: الصف نفسه يربط «لا» بعقد عدم تدريب ثم يوضح أن عدم التدريب لا يعني عدم التخزين.

- **7،10 — CONTRADICTION:** ضمان بقاء الملاحظات دون إرسال لأي مزود يتعارض مع السماح بالتعرف السحابي؛ الدليل: [`SpeechRecognizer`](https://developer.android.com/reference/android/speech/SpeechRecognizer) قد يرسل صوت الإملاء للخوادم قبل وصوله للتصنيف المحلي (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **1،7،11 — CONTRADICTION:** خادم MCP للتحكم من Claude Code يخالف حدود المستودع؛ الدليل: الوضع «ج» يعرض أفعال الهاتف لوكيل خارجي، بينما تعليمات المشروع تمنع التحكم بالهاتف من كمبيوتر داخل المنتج.

- **3 — SUGGESTION:** أضف `Dismiss the timer` و`Snooze the alarm` بتصنيف **بلا يدين عبر واجهة رسمية**؛ الدليل: [`AlarmClock`](https://developer.android.com/reference/android/provider/AlarmClock) يوفر نوايا الإيقاف والغفوة بإذن `SET_ALARM` (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3 — SUGGESTION:** أضف `Play my local audio` بتصنيف **بلا يدين عبر واجهة رسمية** بعد منح القراءة؛ الدليل: [`MediaStore.Audio`](https://developer.android.com/training/data-storage/shared/media) يتيح فهرسة الملفات الصوتية المحلية بالأذونات المناسبة للإصدار (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3 — SUGGESTION:** أضف `Share my location by SMS` بتصنيف **بلا يدين عبر واجهة رسمية** بعد تأكيد المستلم؛ الدليل: [`SmsManager`](https://developer.android.com/reference/android/telephony/SmsManager) يستطيع إرسال رابط الموقع نصاً دون فتح محادثة (تحقق 2026-11-08؛ يُثبت ميدانياً).

- **11،13 — SUGGESTION:** **الأولوية 1:** صحّح إذن مراقبة المكالمة وأضف اختبار انتهاء الرنين وتزامن مكالمتين؛ الدليل: بوابة «المكالمة الصحيحة» لا تغطي حالياً خطر `endCall` المذكور.

- **3،11،12 — SUGGESTION:** **الأولوية 2:** احسم شروط Spotify واستبدل «مضمون» بمصفوفة قبول للتطبيق والإصدار والحساب؛ الدليل: القسم 3 يجمع ضمان التشغيل مع اشتراط إثبات دعم كل إصدار.

- **6،10 — SUGGESTION:** **الأولوية 3:** اجعل سياسة القفل تشمل كل قراءة أو إرسال لبيانات شخصية؛ الدليل: الحماية الحالية تقتصر عملياً على الرسائل.

- **3،10،11 — SUGGESTION:** **الأولوية 4:** اربط التأكيد بمعرّف المحادثة وفعل الرد الحالي، وأبطله عند تغيّرهما؛ الدليل: ثبات الاسم والنص وحدهما لا يمنع الرد إلى مجموعة خاطئة.

- **7،10،11 — SUGGESTION:** **الأولوية 5:** اشترط التعرف المحلي لمسار الملاحظات الموعود بخصوصيته، واختبره بمراقبة الشبكة؛ الدليل: التصنيف المحلي يحدث بعد مرحلة التعرف التي قد تكون سحابية.
