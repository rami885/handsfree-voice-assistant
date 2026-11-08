- 5 — ERROR: نفي حوار تعيين المساعد غير دقيق؛ يتيح [RoleManager.createRequestRoleIntent](https://developer.android.com/reference/android/app/role/RoleManager#createRequestRoleIntent(java.lang.String)) طلب `ROLE_ASSISTANT` منذ Android 10، مع فحص توفر الدور وأهلية التطبيق.

- 5 — ERROR: جلسة المساعد ليست المسار الوحيد لميكروفون الخلفية؛ توثيق [قيود الخدمات الأمامية](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) يستثني التطبيق الموفّر لـ`VoiceInteractionService` من قيود أذونات الاستخدام، مع بقاء شروط الخدمة.

- 5،13 — ERROR: مسار هوية المتصل ناقص، والرفض ليس انتظاراً مفتوحاً للأمر؛ [CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService) يحتاج `READ_CONTACTS` لفحص أرقام جهات الاتصال، ويتجاهل الاستجابة بعد خمس ثوانٍ، ولا يمرّر الأرقام المحجوبة.

- 5 — ERROR: تاريخ الإهمال خاطئ؛ أُهملت `acceptRingingCall` و`endCall` في [TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager) عند API 29، لا 28، وهما لا تستهدفان مكالمة بمعرّف مستقل.

- 3،6 — ERROR: وعد `Hang up` صوتياً يتجاهل التقاط الصوت أثناء المكالمة؛ قواعد [مشاركة الميكروفون](https://developer.android.com/media/platform/sharing-audio-input) لا تمنح المساعد المثبّت من المستخدم امتيازات المساعد المسبق التثبيت، وقد يستقبل صمتاً.

- 3،5 — ERROR: اختبار جهازين لا يجعل مكبر المكالمة قدرة مضمونة؛ [setCommunicationDevice](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) يعطي الأولوية للتطبيق المسيطر على وضع الصوت، وعادةً يكون تطبيق الهاتف أثناء الاتصال الخلوي.

- 3،6 — ERROR: الإشعار القائم ومعاينته لا يكفيان للرد؛ يلزم `RemoteInput` نصي فعلي، ويمكن للتطبيق اشتراط فتح القفل عبر [setAuthenticationRequired](https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean)) مهما كان مفتاح النتائج الشخصية.

- 3،6 — ERROR: وصول الإشعارات وحده لا يضمن قراءة «أي إشعار»؛ يحجب [Android 15](https://developer.android.com/about/versions/15/behavior-changes-all) محتوى OTP عن المستمعين غير الموثوقين.

- 3،5،8 — ERROR: الجلسة النشطة لا تثبت دعم البحث في Spotify أو YouTube Music؛ يلزم التحقق من [ACTION_PLAY_FROM_SEARCH](https://developer.android.com/reference/android/media/session/PlaybackState#ACTION_PLAY_FROM_SEARCH) ومعالج النية، وإثبات تشغيل المحتوى المطلوب لكل تطبيق وإصدار.

- 3،8 — ERROR: إطفاء الشاشة لا يضمن استمرار موسيقى YouTube Music؛ تربط [سياسة التشغيل الخلفي](https://support.google.com/youtubemusic/answer/6313552) الميزة باشتراك Premium للموسيقى.

- 3،8 — ERROR: قيد Wi-Fi يبدأ مع استهداف Android 10، لا 13؛ تعيد [setWifiEnabled](https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean)) الفشل للتطبيق العادي المستهدف API 29 فأحدث.

- 3،5 — ERROR: صلاحية عدم الإزعاج لا تعني التحكم المطلق؛ عند استهداف [Android 15](https://developer.android.com/about/versions/15/behavior-changes-15) تؤثر الاستدعاءات في `AutomaticZenRule` خاصة بالتطبيق، ولا تلغي قواعد الآخرين.

- 6 — ERROR: خفض الموسيقى تلقائياً يحتاج شرطاً إضافياً؛ استهداف [Android 15](https://developer.android.com/about/versions/15/behavior-changes-15) يمنع طلب التركيز الصوتي دون تطبيق متصدر أو خدمة أمامية.

- 6 — ERROR: حصر ميكروفون البلوتوث في SCO غير دقيق؛ يدعم [setCommunicationDevice](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager) سماعات BLE Audio أيضاً، ولذلك تُحصر تقديرات الجودة والزمن المذكورة بمسار HFP المختبَر.

- 5 — ERROR: جدول الصور لا يغطي Android 10–12؛ تتطلب قراءة صور التطبيقات الأخرى [READ_EXTERNAL_STORAGE](https://developer.android.com/training/data-storage/shared/media) حتى API 32، وتبقى أهلية الوصول الواسع خاضعة لسياسة Play ووظيفة التطبيق الأساسية.

- 3،5 — ERROR: المنبه ليس بديلاً عاماً لتذكير بتاريخ محدد؛ [ACTION_SET_ALARM](https://developer.android.com/reference/android/provider/AlarmClock) لا يحمل تاريخاً تقويمياً، وإذن `SCHEDULE_EXACT_ALARM` بدأ عند Android 12، لا 14.

- 5،12 — ERROR: ليست جميع نطاقات Gmail مقيّدة؛ [gmail.send](https://developers.google.com/workspace/gmail/api/auth/scopes) حساس، والتقييم الأمني يرتبط ببيانات النطاقات المقيّدة المخزنة أو المنقولة عبر الخوادم، ومنها إرسال البريد للنموذج.

- 3،5 — MISSING: لم يُحدّد اختيار الشريحة صوتياً؛ قد يفشل [SmsManager.getDefault](https://developer.android.com/reference/android/telephony/SmsManager) عند غياب اشتراك افتراضي، وقد يعرض الاتصال الصادر منتقي SIM.

- 4،10 — MISSING: لا يوجد مسار موثوق لإثبات تسليم رد واتساب؛ نجاح إرسال `PendingIntent` للرد السريع يثبت استدعاء الفعل، ولا يوفر إيصال تسليم يجيز إعادة المحاولة بأمان.

- 7 — MISSING: لا توجد مدد احتفاظ أو إجراءات حذف لدى المزود؛ «عدم التدريب» لا يثبت انعدام التخزين، وتطلب [سياسة بيانات المستخدم](https://support.google.com/googleplay/android-developer/answer/10144311) بيان الاحتفاظ والحذف.

- 4،7،8 — CONTRADICTION: الملاحظات موصوفة بالمحلية دائماً، لكن قاموس الخطوة 3 لا يتضمن `Take a note` أو `Read my notes`؛ وفق الخطوة 4 ينتقل الأمر غير المطابق للنموذج.

- 6،10 — CONTRADICTION: القفل يوقف «أي مهمة» في 10، بينما 6 يعد بالملاحة والتشغيل فوقه؛ يلزم التفريق بين إلغاء الحوار واستمرار الفعل المصرّح.

- 3 — SUGGESTION: أضف `Send an SMS to Nikos`، بلا يدين عبر واجهة رسمية؛ يتيح [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager) إنشاء رسالة بلا إشعار بعد اعتماد `SEND_SMS` وتأكيد المستلم.

- 3 — SUGGESTION: أضف `Rewind 30 seconds`، بلا يدين عبر واجهة رسمية؛ يدعم [MediaController.seekTo](https://developer.android.com/reference/android/media/session/MediaController.TransportControls#seekTo(long)) الانتقال حين يتيحه المشغّل.

- 3 — SUGGESTION: أضف `Add a calendar event` بمسار يحتاج لمسة؛ تفتح نية [ACTION_INSERT](https://developer.android.com/guide/components/intents-common#Calendar) نموذج الحدث في تطبيق التقويم، ويؤكد المستخدم حفظه.

- 3 — SUGGESTION: صنّف `Call Nikos on WhatsApp` مؤجلاً؛ لا يمنح [TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager) المساعد واجهة عامة لبدء مكالمة واتساب إلى مستلم محدد.

- 5،13 — SUGGESTION: الأولوية 1: أصلح إثبات المكالمات قبل التوسع؛ اختبر هوية جهة محفوظة، والرفض بعد مهلة الفحص، والتقاط `Hang up` أثناء اتصال حقيقي.

- 6،10 — SUGGESTION: الأولوية 2: ضع جدول أفعال فوق القفل؛ اختبر رفض المصادقة وفقدان الميكروفون وإيقاف المهمة على الجهاز المستهدف.

- 3،11 — SUGGESTION: الأولوية 3: اجعل قبول الوسائط خاصاً بالتطبيق والاشتراك؛ اختبر التشغيل البارد وغياب الجلسة والمشغّل غير الداعم، وتحقق من هوية المقطع.

- 5،11 — SUGGESTION: الأولوية 4: أضف Android 15 ورفض الأذونات وسحب الدور لبوابة القبول؛ تلزم [سياسة Play](https://support.google.com/googleplay/android-developer/answer/10208820) بإيقاف استخدام أذونات SMS والسجل فور فقدان الدور.

- 4،7 — SUGGESTION: الأولوية 5: اختبر محلية الإملاء والملاحظات والتأكيدات؛ راقب الطلبات الشبكية لأن تجاوز نموذج اللغة لا يمنع محرك التعرف السحابي من استقبال الصوت.
