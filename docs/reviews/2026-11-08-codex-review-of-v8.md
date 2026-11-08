# مراجعة Codex للنسخة 8

**التاريخ:** 2026-11-08 • **المراجع:** codex-cli 0.167.0 (Codex CLI، حساب ChatGPT، قراءة فقط، جهد high) • **التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • **الوثيقة:** `docs/product-definition/v8.md` • المراجعة الدائمة (`scripts/codex-review.py`)، المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.

---

- **5 — ERROR:** السمة `supportsLaunchFromKeyguard` مكتوبة خطأ؛ الاسم الرسمي هو `supportsLaunchVoiceAssistFromKeyguard`، وترتبط باستدعاء `onLaunchVoiceAssistFromKeyguard()`، فلا يكفي إعلان السمة المذكورة لتمكين المسار فوق القفل ([التوثيق](https://developer.android.com/reference/android/service/voice/VoiceInteractionService#onLaunchVoiceAssistFromKeyguard())؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5 — ERROR:** حارس `CALL_STATE_RINGING` لا يثبت أن المكالمة وحيدة، ولا يغلق السباق بين الفحص والتنفيذ؛ هذه الحالة تشمل انتظار مكالمة أثناء مكالمة نشطة، بينما `endCall()` قد ينهي النشطة ([تعريف الحالة](https://developer.android.com/reference/android/telephony/TelephonyManager#CALL_STATE_RINGING)، [عقد الرفض](https://developer.android.com/reference/android/telecom/TelecomManager#endCall())؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 11 — ERROR:** عبارة «قد تحظر» أضعف من السياسة الفعلية؛ البند III.3 يحظر المساعدات الصوتية المبنية على Spotify Platform صراحةً، بينما انطباق التعريف على نية Android وحدها ما زال غير محسوم، فلا يصح اعتماد Spotify ضماناً للدفعة ([السياسة](https://developer.spotify.com/policy)، [تعريف المنصة](https://developer.spotify.com/terms)؛ تحقق 2026-11-08).

- **4، 6 — ERROR:** زر السماعة لا يستدعي المساعد دائماً لمجرد تعيينه افتراضياً؛ يعالج `CallsManager` الضغطة القصيرة أثناء الرنين باعتبارها إجابة على المكالمة، ولذلك يلزم تحديد الزر ونوع الضغطة المدعومين ([AOSP](https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/CallsManager.java)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **5، 6 — MISSING:** منح `RECORD_AUDIO` والاحتفاظ بالدور لا يكفيان لضمان الالتقاط؛ مفتاح خصوصية الميكروفون في Android 12+ يجعل التطبيق يستقبل صوتاً صامتاً، ويحتاج تشخيصاً منطوقاً مستقلاً عن سحب الإذن ([التوثيق](https://developer.android.com/training/permissions/explaining-access#toggles)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5 — MISSING:** اختيار الشريحة للمكالمات لا يحسم شريحة SMS، خصوصاً «الرفض برسالة»؛ `SmsManager.getDefault()` قد يفشل عند غياب اشتراك رسائل افتراضي، فيلزم اختيار مستقل وربط الإرسال باشتراك صريح ([التوثيق](https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,java.lang.String,java.lang.String,android.app.PendingIntent,android.app.PendingIntent))؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5 — MISSING:** البحث في «صورك» يحتاج قيداً واضحاً بأنه يشمل الصور المحلية المتاحة للتطبيق، ولا يشمل تلقائياً مكتبة Google Photos السحابية؛ ملفات السحابة وحدها لا تحمل `MEDIA_STORE_URI` ([التوثيق](https://developer.android.com/reference/android/provider/CloudMediaProviderContract.MediaColumns#MEDIA_STORE_URI)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5 — MISSING:** وصول الإشعارات لا يغطي كل ملفات المستخدم؛ النظام يتجاهل `NotificationListenerService` داخل ملف العمل، ويمكن لسياسة الجهاز حجب إشعاراته عن المستمع الموجود في الملف الشخصي ([التوثيق](https://developer.android.com/reference/android/service/notification/NotificationListenerService)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **6، 10 — MISSING:** جدول القفل يغفل الاتصال الصادر، وإنشاء SMS جديدة، ومشاركة الموقع، وإنشاء موعد؛ هذه قدرات معلنة في القسم 3، بينما القسم 10 يقر بأن الموافقة الصوتية ليست إثبات هوية، فيلزم حكم افتراضي صريح لكل منها.

- **4، 8 — CONTRADICTION:** عدم الإزعاج موعود ضمن الأوامر المحلية العاملة دون إنترنت، لكنه غائب عن قاموس التصنيف وقوالبه؛ وفق دورة القسم 4 ينتقل الأمر غير المطابق إلى النموذج ثم يفشل دون اتصال.

- **11 — CONTRADICTION:** اختبار جميع الأوامر المحلية بوضع الطيران لا يصلح حرفياً للرد والرفض على مكالمة خلوية؛ افصل اختبار انقطاع الإنترنت مع بقاء الهاتف مسجلاً بالشبكة عن اختبار وضع الطيران (`TelephonyManager`؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3 — SUGGESTION:** أضف `What's my battery level?` بتصنيف **بلا يدين عبر واجهة رسمية**؛ يوفر `BatteryManager.BATTERY_PROPERTY_CAPACITY` النسبة محلياً دون نموذج أو إذن بيانات إضافي ([التوثيق](https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CAPACITY)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 8 — SUGGESTION:** حدّد `Call emergency services` بتصنيف **يحتاج لمسة** عبر طالب الاتصال، واجعل الوعد بالاتصال التلقائي **مؤجلاً** ضمن المعمارية الحالية؛ `ACTION_CALL` لا يسمح بأرقام الطوارئ ويشير توثيقه إلى `ACTION_DIAL` ([التوثيق](https://developer.android.com/reference/android/content/Intent#ACTION_CALL)؛ تحقق 2026-11-08؛ يُثبت ميدانياً).

- **3، 5، 10 — SUGGESTION:** **الأولوية 1:** أعد تعريف قبول الرفض حول منع إنهاء مكالمة نشطة، وارفض التنفيذ عند الشك بدلاً من اعتبار الرنين ضماناً؛ عقد `endCall()` لا يستقبل معرّف المكالمة المقصودة.

- **5، 6، 11 — SUGGESTION:** **الأولوية 2:** صحح سمة القفل وأكمل مصفوفة الأفعال الحساسة، ثم اختبرها والقفل الآمن مفعّل؛ `supportsLaunchVoiceAssistFromKeyguard` يتيح نقطة الاستدعاء ولا يحدد سياسة الإفصاح الخاصة بالمنتج.

- **3، 11، 13 — SUGGESTION:** **الأولوية 3:** اجعل قرار Spotify شرطاً سابقاً لمسار التنفيذ الثاني، وحدد بديلاً موثقاً إن تعذر اعتماده؛ سياسة Spotify III.3 تجعل نجاح التجربة التقنية وحده غير كافٍ.

- **4، 6، 11 — SUGGESTION:** **الأولوية 4:** وسّع القبول إلى Android 12 و13، وأزرار السيارة أثناء الرنين، وتعطيل الميكروفون؛ `minSdk 31` يعد بدعم إصدارين لا تغطيهما بوابة الجهازين الحالية.

- **4، 10 — SUGGESTION:** **الأولوية 5:** أضف `Change the message to…` قبل الإرسال بتصنيف **بلا يدين** ضمن الحوار المحلي، مع إعادة قراءة النص وإبطال التأكيد السابق؛ قاعدة القسم 10 نفسها تجعل تعديل أي عنصر سبباً لإعادة الموافقة.
