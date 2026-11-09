# مراجعة Codex للنسخة 10

**التاريخ:** 2026-11-09 • **المراجع:** codex-cli 0.167.0 (Codex CLI، حساب ChatGPT، قراءة فقط، جهد high) • **التعليمات:** `docs/reviews/review-instructions-v6.md` مع تعديل رقم النسخة • **الوثيقة:** `docs/product-definition/v10.md` • المراجعة الدائمة (`scripts/codex-review.py`)، المخرجات كما وردت. حكم الدمج يُكتب في ملف منفصل.

---

- **5 — ERROR:** صف البلوتوث يغفل `MODIFY_AUDIO_SETTINGS`؛ فواجهة [`setCommunicationDevice`](https://developer.android.com/reference/kotlin/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) تحتاج هذا الإذن العادي، ولا يكفي `BLUETOOTH_CONNECT` وحده (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **3، 5 — ERROR:** مسار هوية المتصل البديل غير مكتمل؛ فواجهة [`TelephonyCallback.CallStateListener`](https://developer.android.google.cn/reference/android/telephony/TelephonyCallback.CallStateListener) تعيد الحالة فقط دون الرقم حتى مع `READ_CALL_LOG`، ولذلك يجب تسمية قناة استخراج الرقم أثناء الرنين صراحةً (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **3، 5 — MISSING:** اختيار اشتراك للمكالمات لا يحدد وحده حساب الاتصال المستخدم؛ فنية `ACTION_CALL` تستعمل [`EXTRA_PHONE_ACCOUNT_HANDLE`](https://developer.android.com/reference/android/telecom/TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE)، ويلزم تعريف ربط الاختيار بهذا الحساب والتعامل مع اختفائه (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **5 — MISSING:** البحث حول المستخدم من الخلفية يحتاج مسار وصول للموقع يتجاوز سرد الإذنين؛ فالتوثيق يميز بين نشاط ظاهر و[خدمة أمامية للموقع](https://developer.android.com/develop/background-work/services/fgs/service-types#location) بنوع `location` وإذن `FOREGROUND_SERVICE_LOCATION`، بينما الوثيقة تعرّف خدمة ميكروفون فقط (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **3، 11 — MISSING:** تسجيل الإشعارات القائمة «مرئية» لا يحدد معالجة تحديثها لاحقاً؛ إذ تسمح واجهة الإشعارات [بتحديث الإشعار بالمعرّف نفسه](https://developer.android.com/develop/ui/compose/notifications/create-notification)، ويلزم استخراج الرسائل الجديدة داخل المحادثة بدلاً من تجاهل المفتاح كلياً أو إعادة قراءة محتواه القديم (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **3، 10 — MISSING:** إرسال SMS لا يحدد سياسة للأرقام القصيرة والمميزة؛ فـ[`SmsManager`](https://developer.android.com/reference/android/telephony/SmsManager#RESULT_ERROR_SHORT_CODE_NOT_ALLOWED) يعيد أخطاء خاصة بمنعها، ولذلك يجب استبعادها أو إعلان حاجتها لتدخل المستخدم بدلاً من شمولها بوعد الإرسال بلا يدين (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **6 — MISSING:** الاتصال ببلوتوث السيارة لا يثبت جاهزية مسار الصوت؛ فـ[`ACTION_ACL_CONNECTED`](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#ACTION_ACL_CONNECTED) يعلن اتصالاً منخفض المستوى، ويلزم تحديد السلوك عند انقطاع السماعة أثناء قراءة رسالة، خصوصاً منع انتقال المحتوى تلقائياً إلى مكبر الهاتف (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **6 — MISSING:** جدول القفل لا يسمي عرض الصور والبحث فيها ضمن حالات الاختبار؛ فاستعلام `MediaStore` يكشف بيانات شخصية، ويجب إخضاع النتائج والصور المصغرة للمفتاح نفسه المطبق على الملاحظات والتقويم، لا الاكتفاء بقاعدة عامة.

- **6 — CONTRADICTION:** إغلاق قناة SCO «فور انتهاء النطق» يناقض الاستماع بعدها ثماني ثوانٍ عبر ميكروفون السيارة؛ فمسار [`setCommunicationDevice`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) يجب أن يبقى مختاراً حتى انتهاء الاستماع أيضاً (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **3، 9 — CONTRADICTION:** قسم التمايز يعد بالقراءة والرد «من أي تطبيق مراسلة»، بينما قسم الاستخدامات يشترط إشعاراً قائماً وفعل `RemoteInput` نصياً؛ وهذا الشرط يستبعد تطبيقات وحالات استخدام من الوعد التسويقي نفسه.

- **11، 12، 13 — CONTRADICTION:** بوابة القبول تشترط جهازاً ثالثاً لاختبار الحد الأدنى، بينما التكلفة والخطوة التالية ما زالتا تحسبان جهازين؛ والدليل المباشر اشتراط Android 16 وAndroid 14 وجهاز Android 12 أو 13 في القسم 11.

- **3، 6 — SUGGESTION:** أضف `Read sender names only` و`Stop reading notifications` بتصنيف **بلا يدين عبر واجهة رسمية**؛ فتصفية بيانات `NotificationListenerService` قبل تمريرها إلى [`TextToSpeech`](https://developer.android.com/reference/android/speech/tts/TextToSpeech) تحقق تحكماً عملياً في الخصوصية أثناء القيادة (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **3 — SUGGESTION:** أضف `Play at 1.5 speed` بتصنيف **بلا يدين عبر واجهة رسمية عند إعلان الدعم**؛ فـ[`MediaController.TransportControls.setPlaybackSpeed`](https://developer.android.com/reference/android/media/session/MediaController.TransportControls#setPlaybackSpeed(float)) يتيح تغيير سرعة البودكاست دون التنقل داخل التطبيق (تحقق 2026-11-09؛ يُثبت ميدانياً).

- **5، 13 — SUGGESTION:** **الأولوية 1:** صحّح فصل حالة المكالمة عن هويتها، وأثبت الحصول على الرقم أثناء الرنين عند رفض دور الفحص؛ فتوقيع `onCallStateChanged(int)` لا يوفر الرقم.

- **6، 11 — SUGGESTION:** **الأولوية 2:** اجعل قبول مسار `Answer` يشمل بقاء قناة السيارة مفتوحة حتى انتهاء الاستماع واختبار فصل السماعة خلاله؛ فالتصميم الحالي يغلقها قبل اكتمال الحوار.

- **3، 10، 11 — SUGGESTION:** **الأولوية 3:** أضف اختبارات تحديث إشعار محادثة موجودة وإشعار ملخص مجموعة وإعادة اتصال المستمع؛ فمفتاح الإشعار يحدد الإشعار، ولا يحدد رسالة منفردة داخله.

- **3، 11 — SUGGESTION:** **الأولوية 4:** سمِّ مشغّلاً واحداً اجتاز التشغيل البارد واختيار المقطع المحدد، واجعله شرط إطلاق؛ فوجود معالج لنية البحث لا يثبت تشغيل المحتوى المطلوب، وSpotify ما زال مشروطاً في الوثيقة.

- **6، 10، 11 — SUGGESTION:** **الأولوية 5:** أكمل مصفوفة القفل بالصور والموقع، واربط كل حالة باختبار والمفتاح مطفأ؛ فالجدول الحالي لا يغطي جميع البيانات الشخصية التي تعرضها القدرات المعلنة.
