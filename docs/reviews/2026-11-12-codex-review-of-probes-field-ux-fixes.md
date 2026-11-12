راجعت الفرق `origin/main..origin/codex/probes-field-ux-fixes` عند `a083243` وقرأت `AGENTS.md`. النتائج:

- **P2 — إيقاف وتصدير وهميان عند أول فتح:** [ProbeController.kt:119](probes/src/main/kotlin/com/handsfree/probes/ProbeController.kt:119). يبدأ `currentId` بالقيمة `T1a`، ولذلك فتح `T7` مثلاً في عملية جديدة يستدعي `stop("screen_changed")` رغم عدم بدء أي تجربة. ينتج حدث interruption وتصدير تلقائي باسم `T1a`. الاختبار الجديد يتحقق من غياب `start` فقط، فيمر رغم هذه الأحداث. يجب تمييز الاختيار الأول عن تبديل تجربة قائمة.

- **P2 — جلسة متأخرة تستطيع إعادة تفعيل تجربة سابقة:** [ProbeController.kt:122](probes/src/main/kotlin/com/handsfree/probes/ProbeController.kt:122). إذا طُلب عرض جلسة `T2` ثم اختيرت `T10` قبل وصول `onShow`، فقد تكون `session` فارغة وقت التبديل، فلا تُغلق الجلسة المنتظرة. عند وصولها يقبلها `sessionShown` دون التحقق من الاختيار الحالي؛ تعرض أزرار `T2` بينما `launchFromSession` يستخدم معرّف `T10` وخياراتها. يلزم رفض callbacks القديمة بمعرّف طلب أو جيل، واختبار هذا الترتيب. عرض الجلسة يمر برسائل غير متزامنة في [مصدر AOSP](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/service/voice/VoiceInteractionSession.java).

- **P2 — تصدير تدمير الميكروفون يُنسب إلى التجربة الجديدة:** [MicrophoneService.kt:111](probes/src/main/kotlin/com/handsfree/probes/MicrophoneService.kt:111). عند تبديل شاشة أثناء التقاط `T3` أو `T8`، يطلب `select` إيقاف الخدمة ثم يغيّر `currentId`. يستخدم `onDestroy` اللاحق القيمة الجديدة لتصدير `microphone_destroyed`، فتظهر نتيجة إنهاء الخدمة تحت تجربة أخرى. يجب حفظ معرّف تشغيل الخدمة واستخدامه في التصدير. اختبار التبديل الحالي يوقف `T3` خلال مهلة الخمس ثواني، قبل تشغيل الخدمة، فلا يكشف الحالة.

- **P2 — الاختيار يضيع عند استعادة العملية والقائمة مفتوحة:** [ProbeActivity.kt:36](probes/src/main/kotlin/com/handsfree/probes/ProbeActivity.kt:36). بعد اختيار `T7` والرجوع للقائمة، تُحفظ `screen=null` فقط. إذا ماتت العملية، تُستعاد القائمة ويبقى المتحكم الجديد على `T1a`؛ استدعاء المساعد يستخدم تجربة مختلفة عن الاختيار المحفوظ ضمنياً. جزء استعادة القائمة في الاختبار يعيد استخدام المتحكم نفسه، فلا يحاكي موت العملية. يلزم حفظ الاختيار مستقلاً عن الشاشة.

لم أجد عيباً إضافياً محدداً في مساري الرجوع، الخلفية المعتمة، `RecognizerState` أو تغيير JDK 21. التقارير الموجودة تسجل 138 اختباراً دون فشل وlint دون أخطاء؛ لم أعد تشغيلها ولم أعدّل ملفات أو أنفّذ commit أو push. التحقق بتاريخ **2026-11-12** مراجعة ساكنة؛ سلوك دورة جلسة Android **«يُثبت ميدانياً»**.

**الحكم: غير قابل للدمج قبل معالجة النتائج أعلاه.**
