# مراجعة Codex للنسخة 9

**التاريخ:** 2026-11-08 • **المراجع:** codex-cli 0.167.0 (Codex CLI، حساب ChatGPT، قراءة فقط، جهد high) • **التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • **الوثيقة:** `docs/product-definition/v9.md` • المراجعة الدائمة (`scripts/codex-review.py`)، المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.

---

- **5 — ERROR:** فحص حالة تعطيل الميكروفون عبر `SensorPrivacyManager` غير متاح لتطبيق عادي؛ واجهته العامة تكشف دعم المفتاح فقط، أما `isSensorPrivacyEnabled` فواجهة نظام محمية، لذلك لا يمكن ضمان التشخيص المنطوق المذكور ([AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/hardware/SensorPrivacyManager.java)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5، 11 — ERROR:** حارس الرفض الجديد لا يضمن صفر إنهاء لمكالمة نشطة، لأن المتصل قد يُجاب بين فحص الحالة واستدعاء `endCall()` الذي ينهي المكالمة النشطة بالأولوية ولا يقبل معرّف المكالمة المستهدفة ([TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager#endCall())؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5 — ERROR:** إدراج `ACCESS_FINE_LOCATION` وحده ناقص لمسار طلب الإذن؛ منذ Android 12 يجب طلب `ACCESS_COARSE_LOCATION` معه، مع قبول اختيار المستخدم للموقع التقريبي ([متطلبات Android](https://developer.android.com/google/play/requirements/target-sdk)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 8 — ERROR:** منع تشغيل YouTube Music في الخلفية بلا Premium بصيغة مطلقة يستبعد قدرة متاحة فعلاً، إذ تسمح الخدمة بتشغيل معظم البودكاست في الخلفية مجاناً، بينما الاشتراك مطلوب لموسيقى الخلفية ([YouTube Music](https://support.google.com/youtubemusic/answer/6313529?hl=en)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5، 11 — MISSING:** غاب `targetSdk` رغم أن بعض القيود المذكورة تتوقف عليه، ومتطلبات Play للتطبيقات الجديدة أصبحت Android 16، أي API 36، منذ 31 أغسطس 2026؛ تحديد `minSdk 31` لا يحسم ذلك ([متطلبات Play](https://developer.android.com/google/play/requirements/target-sdk)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5، 13 — MISSING:** «المشغّل الافتراضي» غير محدد بإعداد قابل للتنفيذ؛ نية التشغيل الضمنية قد تعرض منتقي التطبيقات عند تعدد المستجيبين، فيلزم حفظ المشغّل المختار واستهداف حزمته والتحقق من دعمه ([Common intents](https://developer.android.com/guide/components/intents-common)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5 — MISSING:** إرسال SMS ينقصه تعريف الرسائل الطويلة والفشل الجزئي وتكلفة الأجزاء، فالواجهة توفر `divideMessage` و`sendMultipartTextMessage` ونتيجة إرسال مستقلة لكل جزء عبر `sentIntents` ([SmsManager](https://developer.android.com/reference/android/telephony/SmsManager)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5 — MISSING:** البحث بمكان الصورة يحتاج تقييداً إضافياً: قراءة الأصل عبر `MediaStore.setRequireOriginal` عند الحاجة إلى EXIF غير المنقّح، والتعامل مع صور لا تحتوي GPS أصلاً؛ إذن الموقع لا يُنشئ بيانات مفقودة ([MediaStore](https://developer.android.com/reference/android/provider/MediaStore#setRequireOriginal(android.net.Uri))؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6، 13 — MISSING:** المسار الجديد «إعلان المتصل ثم `Answer`» لا يحدد متى تبدأ نافذة الاستماع وتنتهي وكيف تُلغى بانتهاء الرنين؛ إعلان الاسم وحده لا يشغّل التعرف، وكلمة التنبيه الدائمة مؤجلة، بينما إنشاء جلسة الحوار يحتاج مساراً صريحاً مثل `VoiceInteractionService.showSession` ([الواجهة](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 6، 11 — MISSING:** القراءة التلقائية تنقصها سياسة لاستعادة اتصال مستمع الإشعارات ومنع إعادة قراءة المحتوى السابق؛ `getActiveNotifications()` يعيد الإشعارات القائمة بعد `onListenerConnected()`، ولا يمثل قائمة رسائل جديدة غير مقروءة ([NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6، 8 — CONTRADICTION:** القسم 8 يمنع قراءة البيانات الشخصية فوق القفل دون تفعيل صريح ويجعل الاتصال الصادر الاستثناء الوحيد، بينما القسم 6 يسمح بإعلان اسم المتصل افتراضياً؛ الدليل جدول القفل الذي يجيز الاسم بعد أول فتح للجهاز.

- **3 — SUGGESTION:** أضف `Read that slower` بتصنيف **بلا يدين عبر واجهة رسمية**، لأن `TextToSpeech.setSpeechRate` يتيح إبطاء إعادة النطق دون نموذج لغوي ([TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech#setSpeechRate(float))؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 6 — SUGGESTION:** أضف `Read messages received during this drive` بتصنيف **بلا يدين عبر واجهة رسمية**، باستخدام قائمة محلية مؤقتة للأحداث المرصودة، بموافقة ومدة حذف محددتين؛ مصدرها `onNotificationPosted` وليست أرشيف المحادثة ([الواجهة](https://developer.android.com/reference/android/service/notification/NotificationListenerService)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5، 11 — SUGGESTION:** **الأولوية 1:** علّق ضمان الرفض الصوتي المتأخر حتى معالجة سباق الرد مع الرفض، وأضف اختبار الرد بين الفحص والتنفيذ؛ الدليل أن `endCall()` يستهدف المكالمة الأمامية وقت الاستدعاء ([الواجهة](https://developer.android.com/reference/android/telecom/TelecomManager#endCall())؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5، 11 — SUGGESTION:** **الأولوية 2:** ثبّت `targetSdk 36` وأدرج جهاز Android 16 صراحةً في القبول، لأن اختبار Android 15 وحده لا يغطي إصدار الاستهداف المطلوب للنشر الحالي ([متطلبات Play](https://developer.android.com/google/play/requirements/target-sdk)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6، 13 — SUGGESTION:** **الأولوية 3:** اجعل أول تجربة سلسلة كاملة: إعلان المتصل، فتح الاستماع، التقاط `Answer`، ثم إغلاق الاستماع عند تغير الحالة؛ الدليل أن `VoiceInteractionService` يوفر إنشاء الجلسة لكنه لا يعرّف هذا الحوار تلقائياً ([الواجهة](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 11 — SUGGESTION:** **الأولوية 4:** سمِّ مشغّلاً وحساباً ومحتوى محدداً لمسار الوسائط المقبول، واحفظ الاختيار أثناء الإعداد؛ الدليل أن النوايا الضمنية قد تحتاج منتقي تطبيقات ([Common intents](https://developer.android.com/guide/components/intents-common)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6، 8، 10، 11 — SUGGESTION:** **الأولوية 5:** وحّد استثناءات القفل في قائمة واحدة واختبر كل فعل والمفتاح مطفأ ومفعّل؛ الدليل اختلاف حكم إعلان اسم المتصل بين القسمين 6 و8.
