راجعت الفرق عند `bbe4d8d` دون تعديل أو التزام أو دفع. وجدت الآتي:

1. **MAJOR — مشاركة ملفات قابلة للتغيير تكشف نتائج لاحقة وتسمح بقراءة محتوى غير مكتمل.**  
   الموضع: [ProbeExports.kt:45](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExports.kt:45)، [ProbeExporter.kt:77](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExporter.kt:77).  
   **السيناريو:** يشارك المستخدم النتائج أثناء تجربة جارية؛ يستقبل التطبيق الآخر URI لملفي اليوم، ثم يعيد التصدير التلقائي كتابتهما بـ`wt`. يمكن للمستقبِل قراءة نتائج أُضيفت بعد المشاركة، أو ملف أثناء اقتطاعه وإعادة كتابته. الأقفال تحمي الكتّاب داخل التطبيق، ولا تشمل القارئ الخارجي. كذلك لا تُطبَّق قيمة `IS_PENDING=1` على الملفات الموجودة.  
   **الإصلاح:** إنشاء لقطة ثابتة مستقلة لكل مشاركة، وإتاحتها عبر `FileProvider` غير مُصدَّر، بمسار ضيق ومنحة قراءة فقط. يبقى الزوج اليومي للتحديث التلقائي. أضف اختباراً يؤخر قراءة المرفقات حتى بعد تصدير لاحق. تحقق مرجعي: **10 نوفمبر 2026**؛ [منح URI قد تبقى طوال نشاط مكدس المستقبِل](https://developer.android.com/reference/androidx/core/content/FileProvider). السلوك على الجهاز **يُثبت ميدانياً**.

2. **MAJOR — إعادة المحاولة تتجاهل ملفات MediaStore المعلّقة وتُنشئ سجلات إضافية.**  
   الموضع: [ProbeExporter.kt:64](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExporter.kt:64).  
   **السيناريو:** تنقطع العملية بعد الإدراج بـ`IS_PENDING=1` وقبل النشر. عند التشغيل التالي لا يشمل الاستعلام العناصر المعلّقة، فيُدرج ملفات جديدة بدلاً من استعادة السابقة؛ لا يتحقق ضمان عدم التكرار بعد الانقطاع. المزود الوهمي في الاختبارات يعيد العناصر المعلّقة أيضاً، فيخفي الحالة.  
   **الإصلاح:** الاستعلام باستخدام `QUERY_ARG_MATCH_PENDING=MATCH_INCLUDE` مع قيد الملكية والاسم والمسار، ثم استعادة الكتابة أو تنظيف السجلات المتروكة قبل الإدراج. أضف اختبار إعادة إنشاء المُصدِّر بعد ترك سجل معلّق. تحقق مرجعي: **10 نوفمبر 2026**؛ [MediaStore يستبعد العناصر المعلّقة افتراضياً](https://developer.android.com/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING). الاستنتاج **يُثبت ميدانياً**.

3. **MINOR — ضياع تسجيل جواب الإعدادات عند إعادة إنشاء الشاشة.**  
   الموضع: [ProbeActivity.kt:19](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeActivity.kt:19)، [ProbeActivity.kt:171](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeActivity.kt:171).  
   **السيناريو:** يفتح المستخدم إعدادات الوصول للإشعارات أو الدور، وتُعاد إنشاء الشاشة أثناء غيابه. تعود `pendingSettings` إلى `null`، فلا يُسجَّل الجواب عند الرجوع، رغم تغيير الإذن فعلاً.  
   **الإصلاح:** حفظ القدرة المعلّقة ومعرّف التجربة واستعادتهما، واختبار إعادة الإنشاء قبل العودة من الإعدادات. عبارة الدليل عن تسجيل الإجابات تحتاج هذا الاستكمال.

4. **MINOR — تبدّل اليوم بين الكتابتين يجعل سجل النجاح يشير إلى زوج مختلف.**  
   الموضع: [ProbeExports.kt:27](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExports.kt:27)، [ProbeExporter.kt:59](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExporter.kt:59).  
   **السيناريو:** تنتهي الكتابة الأولى قبل منتصف الليل، فيُسجَّل نجاح يحمل URI الأمس، ثم تبدأ الكتابة الثانية بعده فتُنشئ زوج اليوم وتعيده للمشاركة. بذلك لا يُدرج النجاح في «الزوج نفسه» كما يقول التقرير.  
   **الإصلاح:** تثبيت تاريخ ووجهة واحدة لعملية الحفظ بكاملها، واختبار انتقال الساعة بين المرورين. اختبار تبدّل اليوم الحالي يستدعي المُصدِّر مباشرة، ولا يغطي هذه الحالة.

5. **MINOR — الاختبارات لا تفشل عند حذف بعض المحفّزات التلقائية.**  
   الموضع: [AutoExportTest.kt:232](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:232)، [AutoExportTest.kt:200](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:200).  
   **السيناريو:** اختبار الإجابات يفرض تصديراً إضافياً عند السطر 239؛ لذلك لا يكشف حذف التصدير التلقائي من `permissionAnswer` أو محفّز الموافقة. ولا توجد تأكيدات خاصة بمحفّزات نهاية الميكروفون والمهام الصوتية. اختبار التزامن يبدأ بعد إنشاء الملفات، فلا يختبر سباق الإدراج الأول.  
   **الإصلاح:** اختبار كل محفّز عبر واجهته الفعلية دون تصدير مساعد، واستخدام حواجز حتمية لتأخير نهائيات الإلغاء والإدراج الأول. تضييق ادعاء التغطية في التقرير حتى تُضاف هذه الاختبارات.

بالفحص، يشترك التصدير اليدوي والتلقائي في قفل واحد، وحاجز `snapshot()` يفرّغ الأحداث السابقة في الطابور. اختبار الطابور القديم يؤخر الإدراج فعلياً. وفي الـmanifest المدمج الموجود، بتاريخ التحقق **10 نوفمبر 2026**، لا يوجد `INTERNET` ولا provider تابع للتطبيق؛ المشاركة تستخدم MediaStore ومنحة قراءة للملفين المحددين، دون كتابة أو منح بادئة. صلاحيات الوصول الفعلية بين التطبيقات **تُثبت ميدانياً**.

تقارير الاختبارات الموجودة تسجل **45 اختباراً ناجحاً، منها 14 جديداً**. لم أُعد تشغيل البناء أو اختبارات حذف الميزات، التزاماً بالقراءة فقط. تحديثا الدليل متوافقان إجمالاً مع التنفيذ، مع التحفظات أعلاه.

يحتاج إصلاحات قبل الدمج