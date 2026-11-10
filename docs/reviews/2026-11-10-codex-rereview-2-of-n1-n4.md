**الإصلاحات تعالج `N-2` و`N-3` و`N-4`، وتعالج أمثلة `N-1` المحددة، لكن إصلاح `N-1` أدخل تراجعاً آخر في تطبيع الترقيم.**

راجعت الفرق `76885f8..d603afb` والمراجعة السابقة على `origin/main`. لم أعدّل ملفات، ولم أشغّل اختبارات أو بناءً، ولم أنفّذ `commit` أو `push`. تقييم الاختبارات أدناه استدلال من الكود.

1. **`N-1` — مُصلح للأوامر الثابتة، مع تراجع جديد مذكور أدناه.**

   في [LocalClassifier.kt:35](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/core/intent/src/main/kotlin/com/handsfree/intent/LocalClassifier.kt:35)، أصبحت مطابقة القاموس الثابت تسبق فحص السالب؛ لذلك تُقبل `Volume-up` و`Flashlight-on` و`Do-not-disturb-on`. وفحص السالب محصور في المعامل المستخرج عند الأسطر 38–47، مع استمرار حماية الأعداد السالبة وحفظ متن الملاحظات.

   **هل يفشل الاختبار دون الإصلاح؟ نعم.** اختبار [LocalClassifierTest.kt:30](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/core/intent/src/test/kotlin/com/handsfree/intent/LocalClassifierTest.kt:30) يتوقع أوامر مطابقة، بينما الفحص السابق يرفض الشرطات في أمثلته ويُرجع `null`. لكنه لا يغطي التراجع الجديد.

2. **`N-2` — مُصلح ضمن نطاق العيب.**

   في [IntegrationProbes.kt:65](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/IntegrationProbes.kt:65)، يُحفظ `requestedToken` قبل إرسال الطلب. ويحمل callback هوية الجلسة عند السطر 78، ويرفض القياس أي جلسة أخرى عند السطر 53. بيانات الاكتشاف تُسجّل كـ`baseline_at_discovery` دون إكمال القياس؛ والتنظيف باقٍ داخل `finally`.

   **هل يفشل الاختبار دون الإصلاح؟ نعم.** اختبار [ControllerMediaSearchTest.kt:40](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/ControllerMediaSearchTest.kt:40) ينشئ جلستين للحزمة نفسها. التنفيذ السابق يُكمل القياس ببيانات الجلسة الثانية القديمة، فيفشل شرط بقاء البحث معلقاً. ومع فصل baseline وحده، يظل callback الجلسة الأخرى كافياً لكشف غياب ربط الهوية.

3. **`N-3` — مُصلح.**

   في [IntegrationProbes.kt:108](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/IntegrationProbes.kt:108)، انتهاء النافذة دون إرسال طلب `controller` يُنتج `UNSUPPORTED`، بينما انتهاء مهلة طلب مُرسل يبقى `FAILED`.

   **هل يفشل الاختبار دون الإصلاح؟ نعم.** اختبار [ControllerMediaSearchTest.kt:70](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/ControllerMediaSearchTest.kt:70) يشترط `[UNSUPPORTED]` ويرفض وجود `[FAILED]`؛ التنفيذ السابق يخالف الشرطين. الاختبار عند السطر 89 يحمي فرع مهلة الطلب المُرسل، لكنه يمر أيضاً قبل هذا الإصلاح.

4. **`N-4` — مُصلح.**

   في [QueuedExportTest.kt:43](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/QueuedExportTest.kt:43)، يعمل الكاتب والمصدّر وRoom على مجدول الاختبار نفسه. ويُعلَّق الإدراج صراحةً بواسطة `releaseInsert` قبل بدء التصدير، ثم يُشترط بقاء التصدير معلقاً قبل تحريره والتحقق من CSV وJSON.

   **هل يكشف الاختبار غياب إصلاح التصدير؟ نعم، حتمياً بالقراءة:** مع إبقاء تجهيز الاختبار وإعادة القراءة القديمة `repo.db.events().all()`، يكتمل التصدير بقاعدة فارغة قبل تحرير الإدراج، فيفشل الشرط عند السطر 64. أما حذف التزام `N-4` كاملاً فيعيد الاختبار القديم الاحتمالي؛ فهذا إصلاح للاختبار مع إضافة منافذ حقن، وليس إصلاح إنتاج مستقل.

**العيب الجديد: `N-5` — `MINOR` — M2**

الموضع: [LocalClassifier.kt:38](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/core/intent/src/main/kotlin/com/handsfree/intent/LocalClassifier.kt:38)، خصوصاً المطابقة الكاملة عند السطر 42 وحفظ الشرطات عند السطر 76.

**السيناريو:** `Set a timer for ten seconds—` كان يُطابق قبل هذه الالتزامات: فحص السالب لا يجد عدداً سالباً، والتطبيع يزيل الشرطة النهائية. الآن يحتفظ `numericText` بها، فتصبح النهاية `seconds-` وتفشل مطابقة القالب؛ يُرجع المصنّف `null` رغم أن `command` المطبع صالح. يحدث أيضاً مع `Rewind ten seconds—`.

**الإصلاح:** استخراج المعامل العددي مع حفظ إشاراته، وتطبيق تطبيع الترقيم المعتاد على أجزاء القالب المحيطة به. أضف حالتي الشرطة النهائية إلى الاختبارات، مع إبقاء حالات السالب الحالية. لذلك إصلاح `N-1` غير مكتمل بالنسبة إلى عقد تطبيع الترقيم. لم أجد عيباً جديداً مستقلاً آخر في هذا الفرق.

**البنود المقبولة سابقاً لم تتراجع بسبب هذه الالتزامات:**

| البنود | نتيجة التحقق |
|---|---|
| `0-1`، `0-2`؛ `1-1..1-5`؛ `A-1`، `A-2`؛ `5-1..5-7` | ملفات التنفيذ والاختبارات المعنية لم تتغير في الفرق. |
| `2-1` | فحص السالب الرقمي والمنطوق ورموز Unicode باقٍ داخل المعامل، وكذلك حفظ متن النص. `N-5` يخص تطبيع ترقيم القالب. |
| `3-1..3-8`؛ `A-3`، `A-4` | المسارات المعنية لم تتغير. |
| `3-9` | ترتيب الأحداث واللقطة في الطابور نفسه باقٍ؛ حقن DAO والمجدول يحافظ على إعدادات الإنتاج الافتراضية. |
| `4-1`، `4-2`، `4-3` | مراقبة الجلسات المتأخرة، ومصدرا الاكتشاف، ومنع تكرار callbacks والتنظيف باقية؛ تعريف المستقبل لم يتغير. `N-2` يعالج الجزء المتبقي من `4-1`. |

الإصلاحات موجودة في الفروع اللاحقة، ولذلك ترث M3 وM4 وM5 العيب `N-5` من M2. التحقق بتاريخ **2026-11-10**؛ السلوك الفعلي على أجهزة Android «يُثبت ميدانياً».

M0: قابل للدمج  
M1: قابل للدمج  
M2: يحتاج إصلاحات قبل الدمج  
M3: يحتاج إصلاحات قبل الدمج  
M4: يحتاج إصلاحات قبل الدمج  
M5: يحتاج إصلاحات قبل الدمج