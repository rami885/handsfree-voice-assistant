مراجعة أمنية وهندسية لفرعي M3 وM4 (تطبيق `probes`). بعد الفحص الدقيق للمكونات المصدّرة، دورة الحياة، أذونات النظام، وحماية الحارس، إليك التقرير:

### العيوب المكتشفة

**1. عيب منطقي يعطل تجارب توجيه الصوت (Timeout in Route Callback)**
- **الخطورة:** MAJOR
- **الملف والسطر:** `probes/src/main/kotlin/com/handsfree/probes/AudioEngine.kt:38`
- **الخلل:** عند استدعاء `manager.setCommunicationDevice(device)`، إذا كان الجهاز المطلوب هو بالفعل الجهاز النشط حالياً (مثلاً النظام يستخدم مكبر الصوت سلفاً وطلبت التجربة مكبر الصوت)، فإن الدالة تُرجع `true` ولكن النظام **لن يُطلق** أي حدث في المستمع `OnCommunicationDeviceChangedListener`.
- **سيناريو الفشل:** في `T1b` أو `T13`، إذا كان التوجيه الافتراضي يطابق المطلوب، سينتظر الكود حتى تنتهي المهلة (`2_000` مللي ثانية)، ثم يُسجل النتيجة كـ `route_callback_timeout` ويفشل، رغم أن التوجيه صحيح ومتاح. هذا سيفسد بيانات التجارب الميدانية.
- **الحل:** التحقق من الجهاز النشط قبل الطلب.
```kotlin
if (manager.communicationDevice?.id == device.id) {
    confirmed = true
    routeReady?.complete(true)
    repo.emit(id, "audio", "already_active", "type=${device.type};id=${device.id}")
    return@withContext 0L // أو الزمن 0
}
if (!manager.setCommunicationDevice(device)) { ... }
```

**2. إيقاف صامت لتجربة T13 في حالة نادرة**
- **الخطورة:** NOTE
- **الملف والسطر:** `probes/src/main/kotlin/com/handsfree/probes/AudioSource.kt:47`
- **الخلل:** الدالة `getMinBufferSize` قد تُرجع قيمة سالبة أو صفرية إذا كان عتاد الصوت لا يدعم هذه الإعدادات (وهو نادر جداً لـ 16kHz Mono). الدالة تُرجع `null` بصمت.
- **سيناريو الفشل:** في حال إرجاع `null`، فإن السطر `val source = AudioSource.create(...) ?: return` في `ProbeController.kt` سيتسبب في خروج التجربة `T13` بالكامل دون تسجيل الفشل في السجل.
- **الحل:** تسجيل الفشل قبل إرجاع `null` في حال `minimum <= 0`.
```kotlin
if (minimum <= 0) { repo.emit(id, "audio_source", "buffer_size_unsupported", outcome = "UNSUPPORTED"); return null }
```

### تأكيد سلامة المناطق الحساسة (No Defect)

- **Confused Deputy & Proxy Service:** خدمة `ProxyRecognitionService` المصدّرة لا يمكن استغلالها من تطبيقات خبيثة للتنصت؛ لأنها تمرر `listener.callingAttributionSource` بدقة إلى محرك Google، مما يُجبر النظام على فحص إذن التطبيق المتصل وليس إذن تطبيقنا (ملف `ProxyRecognitionService.kt`).
- **Lifecycle & Screen Off Locks:** إلغاء المهام الجارية عند قفل الشاشة (حدث `ACTION_SCREEN_OFF`) أو فقدان الدور أو تغير حالة المكالمة إلى `OFFHOOK` مُنفّذ بشكل محكم لمنع أي أوامر خلفية أو تسريبات (ملف `ProbeController.kt`).
- **Foreground Service Exemptions:** تجربة `T3` الميكروفون الخلفي وتجربة `T10` لإطلاق الأنشطة من الخلفية تستفيد بامتياز من استثناءات دور المساعد `ROLE_ASSISTANT` في Android 12+ بشكل صحيح قانونياً ولا تُعطل النظام (ملفات `MicrophoneService.kt` و `ProbeSession.kt`).
- **Media Controller Metadata Privacy:** قراءة معلومات المشغل `T6` تُحجب بنجاح إذا كانت الشاشة مقفلة ولم يمنح المستخدم صلاحية النتائج الشخصية (ملف `IntegrationProbes.kt`).
- **Pipe Descriptors Leak:** إدارة واصفات الملفات في قناة الصوت `EXTRA_AUDIO_SOURCE` مغلقة بشكل ذري عند التدمير لمنع تسرب الذاكرة أو حجب الميكروفون (ملف `AudioSource.kt`).

### الأحكام

M3: يحتاج إصلاحات قبل الدمج (بسبب عيب مهلة مسار الصوت `AudioEngine`).
M4: قابل للدمج.