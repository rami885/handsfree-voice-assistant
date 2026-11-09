# ملف المشروع الكامل للتنفيذ: مساعد صوتي بلا يدين على Android

**الإصدار:** 1.1 (بعد مراجعة Codex في `docs/reviews/2026-11-09-codex-review-of-phase3-brief.md`) • **التاريخ:** 9 نوفمبر 2026 • **المالك:** رامي أراج • **المنفّذ:** Codex على GitHub • **المراجعون بعد التنفيذ:** Claude وCodex وGemini

هذا الملف وحده يكفي لبدء التنفيذ. اقرأه كاملاً قبل كتابة أي سطر. المراجع الأصلية في المستودع، وعند أي تعارض تحسمه الأولوية التالية:
1. قواعد `AGENTS.md`.
2. هذا الملف.
3. وثيقة التعريف `docs/product-definition/v12.md`.
4. خطة التنفيذ `docs/phase-2-implementation/phase-2-implementation-draft4.html`.

---

## 0. الخلاصة في عشرة أسطر

- المنتج: تطبيق Android يأخذ دور المساعد الافتراضي، يسمع الأمر بالصوت، ويفهمه بمصنّف محلي أولاً ثم بنموذج لغوي عبر وسيط خلفي. ينفّذه بالواجهات الرسمية فقط، ويُعلن النتيجة صوتاً.
- الاستخدام: بلا يدين في السيارة والعمل والحركة. إنجليزي أولاً، Android أولاً.
- **هذه الدفعة (M0 إلى M5) لا تبني المنتج كاملاً.** تبني ما لا يتوقف على نتائج التجارب الميدانية:
  - هيكل المستودع وCI.
  - تطبيق التجارب `probes` الذي يشغّل التجارب T1a إلى T13 على الأجهزة.
  - منطق الحارس والمصنّف المحلي بكوتلن خالص مع اختبارات.
  - هيكل الوسيط الخلفي.
- أدوات التنفيذ الفعلية (الرد على المكالمات، التشغيل، الرسائل) تُبنى بعد أن يشغّل رامي التجارب على ثلاثة أجهزة ويُكتب قرارها.
- كل عمل يُسلَّم كطلب سحب (PR) مسودة على فرع منفصل، لا دفع مباشر إلى `main`.

---

## 1. القواعد التي لا تُكسر

1. منتج استهلاكي عام. لا تحويل إلى منتج أعمال.
2. **لا تحكم بالهاتف من كمبيوتر داخل المنتج:** لا ADB، ولا root، ولا خادم MCP. adb مسموح في المختبر وأدوات الاختبار فقط.
3. **لا `AccessibilityService`** في أي وحدة تُبنى في هذه الدفعة.
4. **لا مفاتيح API داخل التطبيق** ولا في المستودع. مفتاح النموذج يعيش في الوسيط الخلفي كسرّ في منصة النشر.
5. الواجهات الرسمية فقط. لا انعكاس (reflection) على واجهات مخفية، ولا `@SystemApi`.
6. الوثائق بالعربية. أسماء الكود والأوامر والتعليقات في الكود بالإنجليزية.
7. لا مديح في التقارير. كل ادعاء عن سلوك Android يُعلَّم «يُثبت ميدانياً» ما لم يُختبر على جهاز.
8. رسائل git بالإنجليزية القصيرة.
9. لا تُعدَّل نسخة منشورة من وثيقة التعريف أو خطة التنفيذ. أي تعديل مقترح يُكتب في تقرير التسليم (القسم 12).

---

## 2. القرارات التقنية الملزمة

### المنصة والإصدارات
- Kotlin أصلي، Coroutines وFlow، Hilt، Jetpack Compose لشاشات الإعداد فقط.
- `minSdk 31` (Android 12)، `targetSdk 36` (Android 16)، `compileSdk 36`. Google Play يشترط API 36 منذ 31 أغسطس 2026.
- JDK 17. Gradle Wrapper ملتزم في المستودع (`gradlew` و`gradle/wrapper/*`).
- **مصفوفة البناء تُثبَّت في M0 وتُثبت بـ CI أخضر:** إصدار AGP مستقر يدعم `compileSdk 36`، وإصدارات Gradle وKotlin وKSP وHilt المتوافقة معه، كلها في `gradle/libs.versions.toml`. إن اخترت AGP 9 فاتبع نموذج Kotlin المدمج فيه بدل إضافة `kotlin-android` المنفصلة. اذكر المصفوفة وسبب اختيارها في تقرير التسليم، ولا تغيّرها في المعالم اللاحقة بلا سبب مكتوب.

### دور المساعد
- يعلن التطبيق `VoiceInteractionService` و`VoiceInteractionSessionService` و`RecognitionService`، مع `supportsAssist="true"` و`supportsLaunchVoiceAssistFromKeyguard="true"`.
- ينفّذ `onLaunchVoiceAssistFromKeyguard()` الذي يفتح نشاطاً بـ `FLAG_SHOW_WHEN_LOCKED`.
- يستدعي `setDisabledShowContext(SHOW_WITH_SCREENSHOT | SHOW_WITH_ASSIST)`، لأن المنتج لا يستخدم سياق الشاشة.
- الدور لا يُطلب بحوار؛ الإعداد يرسل المستخدم إلى إعدادات «تطبيق المساعد الرقمي». T9 تختبر حوار `RoleManager`.
- `RecognitionService` الخاصة بنا تعمل وكيلاً: تمرر الطلب إلى محرك Google عبر `createSpeechRecognizer(context, ComponentName)` باستهداف صريح. داخل التطبيق نستهدف المحرك الفعلي دائماً.
- لا يُعتمد على الدور لمنح الأذونات. لكل قدرة حساسة موافقة داخلية مستقلة، مهما كانت حالة الإذن في النظام. T12 تختبر ما يمنحه الدور.

### المكالمات
- مصدر رقم المتصل أثناء الرنين بالترتيب:
  1. `CallScreeningService` (دور `CALL_SCREENING` مع `READ_CONTACTS`).
  2. إن رُفض الدور: `PhoneStateListener` المهمل مع `READ_CALL_LOG`.
  3. وإلا يُنطق «مكالمة واردة» بلا اسم.
- **`CallScreeningService` يرسل استجابة السماح فوراً** قبل أي نطق أو تعرف، لأن النظام يؤخر الرنين حتى الاستجابة.
- حالة المكالمة عبر `TelephonyCallback.CallStateListener` وتحتاج `READ_PHONE_STATE`، وهي لا تحمل رقماً ولا معرّف مكالمة. سجّل مستمعاً لكل اشتراك نشط عبر `TelephonyManager.createForSubscriptionId`، وأعد التسجيل عند `OnSubscriptionsChangedListener`.
- **الرد بصوت فقط:** `acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)`.
- **الرفض «أفضل جهد»:** `endCall()` لا يُستدعى إلا إذا كان اشتراك واحد بالضبط في `RINGING`، والبقية `IDLE` (لا `OFFHOOK` ولا حالة مجهولة)، مع وضع الصوت `MODE_RINGTONE` لا `MODE_IN_CALL`. يُعاد الفحص مباشرة قبل الاستدعاء، وعند أي شك يُرفض التنفيذ ويُنطق السبب.
- **لا استماع أثناء أي مكالمة نشطة،** بما فيها مكالمة تنتظر أثناء مكالمة جارية. الشرط: لا `OFFHOOK` على أي اشتراك، ووضع الصوت ليس `MODE_IN_CALL` ولا `MODE_IN_COMMUNICATION`.
- الاتصال الصادر: `ACTION_CALL` من جلسة المساعد مع `EXTRA_PHONE_ACCOUNT_HANDLE` للشريحة المختارة.

### الصوت والسيارة
- قناة SCO عبر `setCommunicationDevice`، وتحتاج `BLUETOOTH_CONNECT` و`MODIFY_AUDIO_SETTINGS`.
- النطق لا يبدأ قبل `onCommunicationDeviceChanged` بالجهاز المطلوب، بمهلة ثانيتين. إن لم يتأكد المسار لا يُقرأ أي محتوى شخصي.
- القناة تبقى مفتوحة حتى انتهاء آخر نافذة استماع، ثم `clearCommunicationDevice`.
- انقطاع السماعة أثناء القراءة يوقفها فوراً، ولا ينتقل المحتوى إلى مكبر الهاتف.
- **النطق المحلي شرط:** نتائج الأوامر المحلية تُنطق بصوت `Voice.isNetworkConnectionRequired() == false`. إن لم يوجد صوت محلي، تُنطق نتيجة مختصرة عامة ويُسجَّل الفشل.
- **التعرف المحلي للأوامر المحلية والملاحظات:** `createOnDeviceSpeechRecognizer` حين يتوفر. إن لم يتوفر لا يُعلن أن الأمر بقي على الجهاز.
- كشف صمت الميكروفون ليس شرط قبول. بعد نافذتي استماع بلا نص يُنطق: «I can't hear you; check the microphone and the privacy toggle».
- الخدمة الأمامية للميكروفون: `foregroundServiceType="microphone"` (مطلوب منذ استهداف Android 11) مع `FOREGROUND_SERVICE_MICROPHONE` (منذ Android 14).

### الإشعارات والرسائل
- `NotificationListenerService`. عند `onListenerConnected` تُسجَّل الإشعارات القائمة «مرئية» ولا تُقرأ. القراءة التلقائية لما يصل عبر `onNotificationPosted` فقط.
- **منع التكرار لكل رسالة لا لكل إشعار:** المفتاح المركّب يتكون من مفتاح الإشعار، وطابع الرسالة، والمرسل، وتجزئة النص. طابع `MessagingStyle.Message` وحده ليس معرّفاً فريداً.
- الرد عبر `RemoteInput`، والتأكيد مرتبط بلقطة ثابتة: مفتاح الإشعار، وفعل `RemoteInput`، والوجهة (فرد أم مجموعة)، والنص. تغيّر أي عنصر يبطل التأكيد. لا إعادة إرسال تلقائية.
- SMS عبر `SmsManager.createForSubscriptionId(subId)`:
  - الرسالة الطويلة تُقسم بـ `divideMessage` و`sendMultipartTextMessage`، مع نتيجة لكل جزء.
  - الأرقام القصيرة تُرفض.
  - أخطاء `RESULT_ERROR_SHORT_CODE_*` تُنطق بلا إعادة.

### الأمان
- تأكيد منطوق مرتبط بالفعل قبل أي اتصال أو إرسال.
- أفعال محظورة بالكود: شراء، دفع، حذف، إرسال جماعي، تغيير إعدادات أمنية.
- **حماية من الحقن:** نص الإشعار أو الرسالة بيانات لا تعليمات. لا يغيّر الأدوات المتاحة ولا يطلق فعلاً، ولا يبدأ مسار تأكيد. الفعل يُقبل فقط إن طلبه أمر المستخدم المنطوق في الجلسة الحالية.
- `Cancel` أو «stop» أو قفل الشاشة يلغي مهمة الوكيل الجارية. النتيجة المتأخرة من النموذج بعد الإلغاء تُهمل.
- فقدان دور المساعد يوقف فوراً استخدام `SEND_SMS` و`READ_CALL_LOG`.
- **استثناءات القفل، القائمة الوحيدة:**
  1. نطق اسم المتصل أثناء الرنين.
  2. الاتصال الصادر بجهة محفوظة بعد التأكيد.
  الأفعال التي لا تكشف بيانات (رد، رفض، تشغيل، صوت) تعمل دائماً. كل قراءة لبيانات شخصية وكل إرسال آخر خلف مفتاح «نتائج شخصية فوق القفل».

### manifest المرجعي لتطبيق `probes`
- **الأذونات:**
  - `RECORD_AUDIO`، `FOREGROUND_SERVICE`، `FOREGROUND_SERVICE_MICROPHONE`، `POST_NOTIFICATIONS`.
  - `READ_PHONE_STATE`، `READ_CONTACTS`، `ANSWER_PHONE_CALLS`، `CALL_PHONE`، `READ_CALL_LOG`.
  - `BLUETOOTH_CONNECT`، `MODIFY_AUDIO_SETTINGS`، `SET_ALARM`.
- **لا** `INTERNET`، ولا `QUERY_ALL_PACKAGES`، ولا `SEND_SMS` (لا تجربة إرسال SMS في هذه الدفعة). المشغّلات المختبرة تُعلن في `<queries>` بنية `MEDIA_PLAY_FROM_SEARCH`.
- **الخدمات وحمايتها:**

| الخدمة | الإذن الحامي | ملاحظات |
|---|---|---|
| `VoiceInteractionService` | `android.permission.BIND_VOICE_INTERACTION` | `<meta-data android:name="android.voice_interaction" android:resource="@xml/interaction_service"/>`، ويعلن الملف `sessionService` و`recognitionService` و`supportsAssist` و`supportsLaunchVoiceAssistFromKeyguard` |
| `VoiceInteractionSessionService` | `BIND_VOICE_INTERACTION` | لا ملاحظات |
| `RecognitionService` | لا إذن، و`exported="true"` | `<meta-data android:name="android.speech">` |
| `CallScreeningService` | `BIND_SCREENING_SERVICE` | لا ملاحظات |
| `NotificationListenerService` | `BIND_NOTIFICATION_LISTENER_SERVICE` | لا ملاحظات |
| خدمة الميكروفون الأمامية | لا إذن | `foregroundServiceType="microphone"` |

- كل إذن خطير يُطلب في شاشة التجربة التي تحتاجه، لا دفعة واحدة. التجربة التي ينقصها إذن تسجّل «إذن مرفوض» ولا تنهار.

### خارج النطاق دائماً
- خادم MCP.
- Accessibility في نسخة المتجر.
- الأوامر الصوتية أثناء المكالمة.
- كلمة تنبيه دائمة.
- Direct Boot في الدفعة 1.
- Huawei بلا خدمات Google.
- Android 10 و11.

---

## 3. النواقص قبل بناء المنتج كاملاً

| # | الناقص | يملكه | يمنع ماذا |
|---|---|---|---|
| 1 | **التجارب الميدانية T1a إلى T13** على ثلاثة أجهزة: Android 16، و14، و12 أو 13، وجولة خلفية على 15 | رامي، بتطبيق `probes` من هذه الدفعة | بناء أدوات المكالمات والوسائط والرسائل (M6 وما بعدها) |
| 2 | **حسم سياسة Spotify للمطورين، البند III.3**. قد تحظر المساعدات الصوتية، وقد تمتد إلى أوامر التشغيل العامة | رامي، قراءة قانونية | المسار 2 كله، لا Spotify وحده، حتى الحسم |
| 3 | **حكما الدمج على مراجعتي Codex للنسخة 12 والمسودة 4** | Claude، بالتوازي مع M0 إلى M5 بقرار رامي | النسخة 13 والمسودة 5، وM6. ملاحظاتهما الحرجة أُدرجت في القسم 2 هنا، فلا تنتظرها M0 إلى M5 |
| 4 | الأجهزة الثلاثة، وسماعة HFP، وشريحة ثانية، وجهاز لجولة Android 15 | رامي | التجارب |
| 5 | حساب مطوّر Google Play | رامي | الاختبار المغلق لاحقاً |
| 6 | حساب منصة الوسيط (Cloudflare أو ما يعادلها) ومفتاح مزود النموذج كسرّ فيها | رامي | تشغيل الوسيط فعلياً (M5 تبني الكود بلا نشر) |
| 7 | متطلبات نشر Play: تصريح الخدمة الأمامية وفيديو لكل ميزة، والإبلاغ عن المحتوى المولّد داخل التطبيق، ونماذج الأذونات المقيّدة | لاحقاً | النشر، لا هذه الدفعة |
| 8 | ربط Gemini كمراجع ثالث (مفتاح API محفوظ في البيئة) | رامي | المراجعة الثلاثية بعد التنفيذ |

**هل نبدأ الآن؟** نعم، بالجزء المستقل عن النواقص 1 و2: الأقسام M0 إلى M5 أدناه. هذا الجزء ينتج الأداة التي يحتاجها رامي لسد الناقص 1. أما M6 وما بعدها فتنتظر وثيقة قرار التجارب.

---

## 4. نطاق هذه الدفعة ومعالمها

نفّذ المعالم بالترتيب. كل معلم يُسلَّم كطلب سحب مسودة مستقل، على فرع `codex/m<N>-<short-name>`.

القبول نوعان منفصلان:
- **قبول CI:** آلي، شرط لفتح طلب السحب للمراجعة.
- **قبول ميداني:** يشغّله رامي على الأجهزة، ولا يُطلب من Codex.

| المعلم | المحتوى | قبول CI (آلي) | قبول ميداني (رامي) |
|---|---|---|---|
| M0 | هيكل المستودع وGradle Wrapper وCI | `./gradlew build test lint` أخضر في GitHub Actions | لا شيء |
| M1 | `core-guard` بكوتلن خالص | اختبارات القسم 6 تنجح، وتغطية الأسطر 90% أو أكثر بـ Kover، ويفشل CI تحتها | لا شيء |
| M2 | `core-intent`: المصنّف المحلي بكوتلن خالص | كل أوامر القاموس والقوالب تُطابق بمعاملاتها الأصلية، وقاعدة منع اعتماديات الشبكة تنجح | لا شيء |
| M3 | تطبيق `probes`: الدور والجلسة والتعرف والصوت (T1a وT1b وT1c وT2 وT3 وT4 وT7 وT9 وT12 وT13) | APK يستهدف 36، واختبار مؤتمت على محاكي API 36 في CI يفتح كل شاشة تجربة ويضغط «ابدأ»، ويتحقق من سجل واحد على الأقل ومن عدم الانهيار، حتى لو كان السجل «غير مدعوم هنا» أو «إذن مرفوض» | تشغيل كل تجربة على الأجهزة الثلاثة وتصدير نتيجتها |
| M4 | تطبيق `probes`: المكالمات والوسائط والقفل والأزرار والإشعارات (T5 وT6 وT8 وT10 وT11) وتصدير النتائج | كما في M3، مع اختبار ينشئ سجلات وهمية ويتحقق من ملفي CSV وJSON المصدَّرين | كما في M3 |
| M5 | `backend-proxy` بلا نشر | اختبارات المصادقة والحصص الذرية وحجز التكلفة وعدم تسجيل المحتوى (في النجاح والخطأ والبث) تنجح في CI | لا شيء قبل النشر |

**لا يُبنى في هذه الدفعة:**
- `tools-*` الإنتاجية.
- شاشات الإعداد النهائية.
- `ClaudePlanner` المتصل فعلياً بالشبكة؛ واجهة `Planner` ونسخة وهمية للاختبار كافيتان.
- أي نشر.

---

## 5. بنية المستودع

```
/
├── AGENTS.md                      # موجود، لا يُعدَّل إلا قسم الحالة إن لزم
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── .github/workflows/android.yml  # build + test + lint على كل PR
├── .github/workflows/backend.yml  # test للوسيط
├── app/                           # تطبيق المنتج: هيكل فارغ يجمع الوحدات (M0)
├── core/assist/                   # تعريفات الخدمات للمنتج: هياكل فقط في هذه الدفعة
├── core/audio/                    # واجهات فقط في هذه الدفعة
├── core/guard/                    # M1: كوتلن خالص (JVM module)
├── core/intent/                   # M2: كوتلن خالص (JVM module)
├── probes/                        # M3 وM4: تطبيق التجارب، منفصل عن app ولا يُنشر
├── backend-proxy/                 # M5: TypeScript
└── docs/                          # موجود
```

- `core/guard` و`core/intent` وحدتا JVM خالصتان بلا اعتماد على Android، لتُختبرا بسرعة في CI.
- `probes` له `applicationId` مختلف: `com.handsfree.probes`. وللمنتج `com.handsfree.assistant`. الاسم النهائي قرار رامي، فاجعله قيمة واحدة في `libs.versions.toml` أو `gradle.properties`.
- لا وحدة تستدعي أخرى من مستوى الأدوات مباشرة.

---

## 6. مواصفات الوحدات

### M1: `core-guard`

**الأنواع الأساسية:**

```kotlin
data class ToolSpec(val name: String, val params: Map<String, ParamType>, val permission: Capability, val sensitivity: Sensitivity)
enum class Sensitivity { NONE, CONFIRM, FORBIDDEN }
enum class ActionKind { SYSTEM_NO_DATA, PERSONAL_READ, SEND, CALL_OUT, NEEDS_LISTENING }  // يحدد معاملة القفل
enum class Capability { NONE, CALL_PHONE, ANSWER_CALLS, SEND_SMS, READ_CALL_LOG, READ_CONTACTS, NOTIFICATION_ACCESS, LOCATION, CALENDAR_WRITE, MEDIA_SESSION }
enum class Origin { USER_UTTERANCE, PLANNER_FROM_USER, UNTRUSTED_CONTENT }  // مصدر كل معامل
data class ActionSnapshot(val tool: String, val kind: ActionKind, val recipientId: String?, val recipientLabel: String?, val recipientSaved: Boolean, val subscriptionId: Int?, val content: String?, val notificationKey: String?, val remoteInputActionId: String?, val conversationTarget: String?, val origins: Map<String, Origin>)
data class Context(val roleHeld: Boolean, val screenLocked: Boolean, val personalResultsOnLock: Boolean, val grantedCapabilities: Set<Capability>, val internalConsents: Set<Capability>, val inActiveCall: Boolean, val ringingCallerNameRequest: Boolean)
sealed interface Decision { data class Allow(val snapshot: ActionSnapshot) : Decision; data class NeedConfirm(val prompt: String, val snapshot: ActionSnapshot) : Decision; data class Deny(val reason: DenyReason, val spoken: String) : Decision }
```

**القواعد واختبار لكل واحدة:**
1. الأداة `FORBIDDEN` تُرفض دائماً، حتى مع تأكيد.
2. القدرة غير الممنوحة في النظام **أو** بلا موافقة داخلية تُرفض بجملة منطوقة تذكر السبب.
3. فقدان الدور (`roleHeld=false`) يرفض `SEND_SMS` و`READ_CALL_LOG` دائماً.
4. الأداة `CONFIRM` تعيد `NeedConfirm` بلقطة. الموافقة تصح فقط إن طابقت اللقطة الحالية اللقطة المؤكدة في كل حقل. أي اختلاف يبطلها.
5. فوق القفل:
   - `SYSTEM_NO_DATA` يُسمح دائماً.
   - نطق اسم المتصل أثناء الرنين (`ringingCallerNameRequest`) يُسمح.
   - `CALL_OUT` يُسمح فقط إن كان `recipientSaved=true` وبعد التأكيد.
   - `PERSONAL_READ` و`SEND`، و`CALL_OUT` إلى رقم غير محفوظ، تُرفض ما لم يكن `personalResultsOnLock=true`.
   اختبار لكل خانة من هذه المصفوفة.
6. `inActiveCall=true` يرفض كل `NEEDS_LISTENING`.
7. **دورة المهمة:** `Planning → AwaitingConfirm → Executing → Done | Cancelled | Failed`. `cancel()` من أي حالة قبل `Executing` ينهيها `Cancelled`، والنتيجة المتأخرة من المخطّط بعد الإلغاء تُهمل وتُسجَّل.
8. **حماية الحقن:**
   - الأداة نفسها والمستلم يجب أن يكون مصدرهما `USER_UTTERANCE` أو `PLANNER_FROM_USER`. إن كان أيهما `UNTRUSTED_CONTENT` فالقرار `Deny` (لا `NeedConfirm`)، مع جملة «I won't act on instructions inside a message».
   - النص المقتبس من رسالة مسموح فقط كمحتوى للقراءة، لا كمستلم ولا كأداة.
   - اختبارات: إشعار يقول «call 555…» لا يُنتج اتصالاً، ورسالة تقول «reply yes to everyone» لا تُنتج إرسالاً.
9. سجل محلي بلا محتوى: الأداة، والقرار، والسبب، والمدة، والتكلفة التقديرية. لا نص رسائل ولا أرقام كاملة.

### M2: `core-intent`

- `LocalClassifier.classify(text: String): LocalMatch?`.
- **التطبيع لمطابقة الأمر فقط:** أحرف صغيرة، وإزالة علامات الترقيم إلا النقطة العشرية بين رقمين، وتحويل الأرقام المنطوقة («ten» إلى 10، و«one point five» إلى 1.5).
- **المعاملات تُستخرج من النص الأصلي:** نص ما بعد النقطتين في `Take a note:` و`Change the message to` يُحفظ حرفياً بلا تطبيع. اختبارات: `Play at 1.5 speed` يعطي 1.5 لا 15، و`Take a note: Call Nikos, 5 p.m.` يحفظ النص كما هو.
- **قاموس ثابت:**
  - `Answer`، `Decline`، `Play`، `Pause`، `Next`.
  - `Volume up`، `Volume down`، `Mute`، `Flashlight on`، `Flashlight off`.
  - `Do not disturb on`، `Do not disturb off`.
  - `What's my battery level?`، `Read that slower`، `Read sender names only`، `Stop reading notifications`، `Skip this notification`.
  - `Say that again`، `Cancel`، `Stop`، `Read my notes`، `Dismiss the timer`، `Snooze the alarm`.
- **قوالب بمتغير واحد:** `Set a timer for <N> minutes|seconds`، `Volume <N>`، `Rewind <N> seconds`، `Play at <X> speed`، `Take a note: <text>`، `Change the message to <text>`.
- ما يحمل اسم شخص أو عدة خطوات يعيد `null`، فيذهب للمخطّط.
- `interface Planner { suspend fun plan(text: String, tools: List<ToolSpec>): PlanResult }` مع `FakePlanner` للاختبار. لا عميل شبكة في هذه الدفعة.
- **اختبار «لا شبكة»:** وحدة `core-intent` لا تعتمد على أي مكتبة شبكة، ويُتحقق من ذلك في Gradle بقاعدة تفشل البناء إن ظهر OkHttp أو ما يشبهها في اعتمادياتها.

### M3 وM4: تطبيق `probes`

تطبيق تشخيصي يأخذ دور المساعد ودور فحص المكالمات ليختبر السلوك على الجهاز. هو تجارب تُرمى، لكنه يُكتب بنظافة لأنه سيُعاد تشغيله على أجهزة مختلفة.

**مبادئ مشتركة:**
- شاشة رئيسية فيها قائمة التجارب، ولكل تجربة شاشة تعرض الخطوات اليدوية المطلوبة من المختبِر، وزر «ابدأ»، وحالة مباشرة.
- كل حدث يُسجَّل بطابع زمني بالمللي ثانية في Room: `experimentId`، `device`، `sdkInt`، `step`، `event`، `value`، `outcome`.
- تصدير CSV وJSON إلى `Downloads/probes/` عبر `MediaStore`، مع معلومات الجهاز: الطراز، والإصدار، وإصدارات التطبيقات المعنية.
- **نطاق «لا شبكة»:** عملية تطبيق `probes` نفسها بلا شبكة، ولا إذن `INTERNET` في manifest. خدمات النظام التي يستدعيها (محرك التعرف، محرك النطق) لها اتصالها الخاص خارج سيطرتنا. لذلك تسجّل كل تجربة تعرّف ونطق: هل استُخدم `createOnDeviceSpeechRecognizer`، وقيمة `Voice.isNetworkConnectionRequired()` للصوت المستخدم.
- كل سطر يُنطق يُكتب أيضاً على الشاشة.

**التجارب:**

| التجربة | ما يبنيه Codex | ما يسجّله |
|---|---|---|
| T1a | `RecognitionService` وكيلة تمرر إلى محرك Google، مع عدّاد للطلبات والنتائج والإلغاء. وشاشة عميل تستدعي المحرك **الافتراضي** للجهاز | هل وصل الطلب إلى خدمتنا، وزمن النتيجة، والأخطاء. قارن مع Gboard يدوياً |
| T1b | السلسلة: `CallScreeningService` يسمح فوراً ويمرر الرقم، ثم `showSession`، وانتظار `onCommunicationDeviceChanged`، ونطق الاسم، ونافذة استماع حتى انتهاء الرنين أو 8 ثوانٍ، و`Answer` بـ `acceptRingingCall(STATE_AUDIO_ONLY)`، وإغلاق عند تغيّر الحالة. على كل اشتراك نشط | زمن كل خطوة، ونجاح الالتقاط، وهل أُغلق الاستماع خلال ثانية. وبلا `READ_CONTACTS`: هل وصلت مكالمة الجهة إلى الفحص؟ |
| T1c | مستمع `AudioManager.OnModeChangedListener` بلا إذن، و`PhoneStateListener` مع `READ_CALL_LOG` | هل يكشف الأول الرنين والرد؟ وهل يعطي الثاني الرقم على API 36؟ |
| T2 | من الجلسة والشاشة مطفأة: `ACTION_CALL` إلى رقم اختبار، ونية تشغيل بالبحث، ونية ملاحة، ومؤقّت، وفتح تطبيق | هل ظهر حوار «حُظر الإطلاق»؟ نجاح أو فشل لكل نية |
| T3 | خدمة أمامية من نوع ميكروفون تُبدأ والتطبيق مساعد حالي وتطبيق آخر في المقدمة، ثم بعد سحب الدور | هل يُستقبل صوت غير صفري؟ (عينات `AudioRecord` لا محرك التعرف) |
| T4 | فتح SCO وقياس الزمن من `setCommunicationDevice` إلى `onCommunicationDeviceChanged`، 20 تكراراً، ثم نطق 10 جمل محفوظة | وسيط الزمن والمئين 90، وخانة يملؤها المختبِر لعدد الجمل المفهومة |
| T5 | قراءة إشعار مكالمة واتساب وإرسال `PendingIntent` لزري «Answer» و«Decline» | نجاح أو فشل لكل زر، على Android 14 و15 و16 ومع القفل الآمن |
| T6 | لكل مشغّل يختاره المختبِر: نية `MEDIA_PLAY_FROM_SEARCH` بـ `setPackage`، و`playFromSearch` عبر `MediaController` من المنصة إن أعلنته الجلسة. حالتا بارد ونشط | اسم الحزمة وإصدارها، وهل ظهر منتقٍ، وزمن أول `MediaMetadata`، وهل طابق العنوان الاستعلام |
| T7 | `AudioFocusRequest` مؤقت من نافذة الجلسة والشاشة مطفأة بلا خدمة أمامية | `GRANTED` أو `FAILED`، وهل انخفضت الموسيقى |
| T8 | أثناء مكالمة خلوية جارية: محاولة `AudioRecord` من الخدمة | هل العينات صفرية؟ (المتوقع: نعم) |
| T9 | `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` و`isRoleAvailable` | هل ظهر حوار؟ |
| T10 | مع PIN: ملاحة، ومؤقّت، واتصال صادر من الجلسة فوق القفل | هل طلب النظام فتح الجهاز؟ وهل سُمع الإرشاد؟ |
| T11 | مستقبل لأزرار السماعة والمقود: الضغطة القصيرة والطويلة أثناء الرنين وخارجه | ماذا حدث: رد، أو استدعاء المساعد، أو لا شيء |
| T12 | قراءة حالة كل إذن معلن قبل تعيين الدور وبعده | الأذونات التي صارت ممنوحة دون حوار |
| T13 | على API 33+: `EXTRA_AUDIO_SOURCE` بمصدر مفتوح `VOICE_COMMUNICATION` مع `AcousticEchoCanceler`، أثناء نطق جملة عبر السيارة، والتقاط «stop» | عدد مرات الالتقاط من 20 |

**قيد:** T5 وT6 وT8 وT10 وT11 تحتاج تطبيقات حقيقية أو سيارة أو مكالمة خلوية. يبنيها Codex، ورامي يشغّلها. على المحاكي يكفي أن تفتح الشاشة وتسجّل «غير مدعوم هنا» بلا انهيار.

### M5: `backend-proxy`

- TypeScript على Cloudflare Workers أو ما يعادلها، مع Vitest للاختبار.
- نقطة واحدة `POST /v1/plan` تمرر الطلب إلى مزود النموذج بالبث.
- **الهوية:**
  - `POST /v1/register` يستقبل رمز Play Integrity (طلب قياسي مربوط بـ `requestHash` لجسم الطلب). إن كان الحكم مقبولاً (`MEETS_DEVICE_INTEGRITY` والتطبيق معترف به) يصدر الخادم رمز تثبيت موقّعاً (HMAC) صالحاً 30 يوماً، يُجدَّد بالمسار نفسه.
  - `/v1/plan` يقبل رمز التثبيت فقط.
  - إعادة إنشاء الهوية لتجاوز الحصة محدودة بحد تسجيلات يومي لكل حكم Integrity، وبسقف الإنفاق العام.
  - في التطوير متغير `INTEGRITY_BYPASS=true` صريح، يُرفض إن كانت البيئة `production`.
- **الحصص والإنفاق ذرية:** في Durable Object واحد لكل رمز تثبيت، وواحد للسقف العام، لا KV لأنه بلا عمليات ذرية. قبل البث يُحجز أقصى تكلفة متوقعة للطلب، وبعده تُسوّى بالتكلفة الفعلية. إن تجاوز الحجز الحصة أو السقف يُرد `429` بجسم واضح دون استدعاء المزود.
- **لا تسجيل محتوى:** لا يُكتب نص الطلب ولا الرد ولا أجزاء البث في أي سجل، بما فيه رسائل الأخطاء. اختبارات تغطي النجاح، وخطأ المزود، وانقطاع البث، وتتحقق من أن المسجّل لم يستقبل نصاً.
- المفتاح من `env` فقط. اختبار يفشل إن وُجد مفتاح بنمط معروف في المستودع (فحص سري بسيط في CI).
- لا نشر في هذه الدفعة. `wrangler.toml` بقيم وهمية وتعليمات في `backend-proxy/README.md`.

---

## 7. CI وبوابات الجودة

- `android.yml`: JDK 17، كاش Gradle، `./gradlew build test lint`، ورفع APK لـ `probes` كقطعة بناء (artifact) في كل PR.
- `backend.yml`: Node LTS، `npm ci`، `npm test`، `npm run lint`.
- فحص أسرار: يفشل CI إن ظهر نمط مفتاح API في أي ملف.
- قواعد lint: يفشل البناء إن ظهر `AccessibilityService` أو `QUERY_ALL_PACKAGES` أو `INTERNET` في manifest `probes`.
- اختبار مؤتمت على محاكي API 36 في CI إلزامي من M3: يفتح كل شاشة تجربة ويضغط «ابدأ»، ويتحقق من سجل وعدم انهيار. استخدم إجراء محاكٍ موثوقاً في GitHub Actions، وإن تعذر تشغيله فاذكر السبب في التقرير ولا تحذف الاختبار.
- Kover لتغطية `core-guard`، بحد أدنى 90% يفشل CI تحته.

---

## 8. ما يُمنع

- دفع مباشر إلى `main`، أو إعادة كتابة تاريخه.
- تعديل `docs/product-definition/*` أو `docs/phase-2-implementation/*` أو `docs/reviews/*`.
- أي مفتاح أو رمز حقيقي في المستودع.
- واجهات مخفية أو `@SystemApi` أو انعكاس.
- إضافة اعتمادية شبكة إلى `core-guard` أو `core-intent` أو `probes`.
- تعطيل اختبار أو قاعدة lint لتمرير CI.

---

## 9. طريقة العمل على GitHub

1. **فروع متراكبة:** `codex/m0-skeleton` يتفرع من `main`، وكل معلم لاحق يتفرع من فرع المعلم السابق. طلب السحب لكل معلم وجهته فرع المعلم السابق، وطلب M0 وجهته `main`. بعد دمج معلم في `main` تُغيَّر وجهة التالي إلى `main`.
2. التزامات صغيرة برسائل إنجليزية قصيرة.
3. طلب سحب **مسودة** لكل معلم، عنوانه `M<N>: <title>`، ووصفه بالعربية:
   - ما بُني.
   - كيف يُختبر محلياً، بالأوامر.
   - نتائج CI.
   - ما لم يُنفَّذ ولماذا.
   - أي انحراف عن هذا الملف مع السبب.
4. لا تبدأ معلماً قبل أن يكون CI أخضر في سابقه.
5. لا تدمج بنفسك. الدمج بعد المراجعة الثلاثية.

---

## 10. المراجعة بعد التنفيذ

بعد فتح طلبات السحب يراجعها Claude وCodex (جلسة مستقلة بوضع القراءة فقط) وGemini، كل على حدة:
- **مطابقة هذا الملف:** القواعد في القسم 1، والقرارات في القسم 2، ومعايير القبول في القسم 4.
- **الأمان:** الحارس والحقن والإلغاء وعدم تسجيل المحتوى.
- **قابلية التشغيل على الأجهزة:** هل يستطيع رامي تشغيل كل تجربة من الشاشة وتصدير نتيجتها دون مساعدة.

كل مراجعة تُحفظ كما وردت في `docs/reviews/<التاريخ>-<النموذج>-review-of-m<N>.md`، ثم يُكتب حكم دمج بنداً بنداً قبل أي تعديل.

---

## 11. ما بعد هذه الدفعة (للعلم، لا للتنفيذ الآن)

1. رامي يشغّل `probes` على الأجهزة، ويرفع ملفات التصدير إلى `docs/experiments/`.
2. وثيقة قرار التجارب، ثم النسخة 13 من التعريف والمسودة 5 من خطة التنفيذ.
3. M6: `core-assist` و`core-audio` الإنتاجيتان، ثم المسار 1 كاملاً في التطبيق. M7: الوسيط متصلاً والمسار 3. M8: المسار 2 بعد حسم Spotify. M9: الرسائل ووضع القيادة. M10: بقية الأدوات وشاشات الإعداد. M11: بوابة القبول.

---

## 12. تقرير التسليم المطلوب من Codex

في نهاية كل طلب سحب، وفي ملف `docs/phase-3-implementation/m<N>-delivery-report.md` على الفرع نفسه:
- الإصدارات المثبتة: AGP وKotlin والمكتبات وNode.
- ما بُني مقابل كل بند في معايير القبول، مع حالته: تم، أو جزئي، أو لم يتم.
- أوامر التشغيل والاختبار.
- ما لم يُنفَّذ ولماذا.
- أي تعارض وجده بين هذا الملف ووثيقة التعريف أو خطة التنفيذ، مع اقتراح، دون تعديلهما.
- كل ادعاء عن سلوك Android لم يُختبر على جهاز يُعلَّم «يُثبت ميدانياً».
