راجعت الفرق وتقارير M3 وM4 والتقرير الليلي بالقراءة فقط، دون بناء أو تعديل ملفات. التحقق بتاريخ **2026-11-10**؛ السلوك على الأجهزة «يُثبت ميدانياً». أسماء ملفات Kotlin أدناه تقع داخل `probes/src/main/kotlin/com/handsfree/probes/`.

1) الأخطاء والسلوك غير الصحيح:

- [MAJOR] `ProbeController.kt:272` — تستخدم T2 وT10 الاستدعاء `startVoiceActivity`، وكذلك الاتصال عند السطر 315؛ هذا الاستدعاء يضيف `CATEGORY_VOICE`، ولذلك قد ترفض نية الملاحة أو التشغيل نشاطاً يعالج النية العادية لكنه لا يدعم التفاعل الصوتي، فتُسجَّل نتيجة فشل تخص مسار إطلاق مختلف عن `startAssistantActivity` المعتمد في v12. [توثيق Android](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#startVoiceActivity(android.content.Intent))

- [MAJOR] `ProbeSession.kt:21` — تُبنى الأزرار وفق التجربة وقت إنشاء الواجهة فقط، بينما `onShow` لا يحدّثها؛ عند إعادة استخدام الجلسة والانتقال من T2 إلى T5 تبقى أزرار `call/play/...` وتُمرّر إلى مسار WhatsApp، فلا تتاح أزرار Answer/Decline المطلوبة. إعادة عرض الجلسة لا تستلزم إعادة إنشاء محتواها. [توثيق دورة الجلسة](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#onShow(android.os.Bundle,int))

- [MAJOR] `IntegrationProbes.kt:39` — تبحث T6 عن `MediaController` مرة واحدة قبل إرسال الطلب؛ إذا كان المشغّل بارداً وأنشأ جلسته بعد النية، يسجّل السطر 69 `no_exposed_session` وينتهي دون اكتشافها أو قياس زمن أول metadata.

- [MAJOR] `AudioEngine.kt:35` — يشترط فتح أي مسار صوتي إذن `BLUETOOTH_CONNECT` حتى لمسار الهاتف أو السماعة السلكية؛ رفض Bluetooth يمنع تأكيد الاتصال في T2 وT10 رغم تصنيفه اختيارياً في `Experiments.kt` وعدم استخدام سماعة Bluetooth.

2) الأمان والخصوصية:

- [MAJOR] `ProbeSession.kt:62` — إخفاء الجلسة يستدعي `interrupt` دون إلغاء `actionJob`؛ إذا أُخفيت T2/T10 أثناء انتظار تهيئة TTS أو تأخير فتح المسار، تستطيع المهمة استئناف النطق والاستماع والتأكيد بعد اختفاء الجلسة، لأن `ProbeController.kt:303` لا يعيد التحقق من ظهورها.

- [MAJOR] `ProxyRecognitionService.kt:19` — يُفحص أمان الاستماع والموافقة عند البداية فقط، بينما `ProbeController.interrupt` لا يغلق `delegate` الخاص بالوكيل؛ طلب تعرف بدأه عميل خارجي يمكن أن يستمر بعد سحب الموافقة أو انتقال الهاتف إلى OFFHOOK، خلاف ادعاء الإيقاف الفوري.

- [MAJOR] `ProbeController.kt:59` — قفل الشاشة يلغي وظائف المتحكم لكنه لا يوقف `MicrophoneService`؛ التقاط PCM في T3/T8 يعمل داخل نطاق مستقل عند `MicrophoneService.kt:28`، فيستمر حتى نهاية نافذته بعد القفل بدلاً من إلغاء الالتقاط الجاري.

- [MAJOR] `ProbeNotificationListener.kt:24` — يُحتفظ بكائن الإشعار الكامل قبل فحص الموافقة، وتُحمّل جميع الإشعارات القائمة عند السطر 19؛ منح وصول النظام وحده يؤدي إلى الاحتفاظ بمحتوى رسائل غير متعلق بالتجارب، ويستمر ذلك بعد سحب الموافقة الداخلية لأن مسار السحب لا يمسح الخريطة.

- [MAJOR] `AudioEngine.kt:35` — فحص Bluetooth يراجع إذن النظام وحده ولا يراجع الموافقة الداخلية؛ إذا منح النظام الإذن سابقاً واختار المختبِر T1b/T2/T10 دون موافقة Bluetooth، يستطيع التطبيق اختيار SCO واستخدامه رغم قاعدة الموافقة المستقلة لكل قدرة حساسة.

3) مطابقة المواصفات والاختبارات:

- [MAJOR] `ProbeExporter.kt:15` — لا ينتظر التصدير حفظ أحداث `ProbeRepository` المنتظرة في القناة؛ قد يظهر القياس النهائي على الشاشة ثم يغيب عن الملفين عند التصدير فوراً، لأن الإدراج غير متزامن عند `ProbeRepository.kt:45`. اختبار `ExportTest.kt:16` يزرع السجلات مباشرة في Room، فيتجاوز هذا المسار ولا يثبت تصدير جميع الأحداث التي سجّلها التطبيق قبل الطلب.

4) قرارات التقارير التي أختلف معها:

- [MAJOR] `docs/phase-3-implementation/m4-delivery-report.md:47` — لا أوافق على تضييق T6 إلى الجلسات التي تعلن Token داخل إشعارها باعتباره بديلاً لازماً لتجنب `MEDIA_CONTENT_CONTROL`؛ الواجهة الرسمية `getActiveSessions(ComponentName)` تسمح لمستمع إشعارات مفعّل بالوصول دون ذلك الإذن النظامي، والتضييق الحالي يستبعد جلسات قد تدعم البحث فعلياً ويضعف صلاحية التجربة. [توثيق Android](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))

M3: **يحتاج إصلاحات قبل الدمج**.  
M4: **يحتاج إصلاحات قبل الدمج**.