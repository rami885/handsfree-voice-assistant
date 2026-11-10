راجعت `ae42865` المطابق للمرجع المحلي `origin/codex/probes-auto-export`، دون تعديل أو التزام أو دفع، ودون تشغيل بناء أو اختبارات. البنود الخمسة مُصلحة؛ تقييم حساسية الاختبارات أدناه استنتاج من الشيفرة.

1. **ثبات المرفقات — مُصلح.**  
   تُنشأ ملفات مستقلة داخل مجلد UUID، وتكتمل كتابتها قبل إرجاع URI في [ProbeExporter.kt:66](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExporter.kt:66)، ويستخدمها مسار المشاركة في [ProbeExports.kt:38](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExports.kt:38).  
   **هل يفشل الاختبار دون الإصلاح؟ نعم.** [AutoExportTest.kt:305](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:305) يضيف حدثاً ويُصدّره قبل قراءة المرفقات القديمة؛ إعادة مشاركة الزوج اليومي تُظهر الحدث اللاحق وتُفشل تأكيد غيابه. ويتحقق أيضاً من اختلاف URI للمشاركة التالية.

2. **استعادة سجلات MediaStore المعلّقة — مُصلح.**  
   الاستعلام يضم `MATCH_INCLUDE` ويقيّد الاسم والمسار والمالك في [ProbeExporter.kt:93](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExporter.kt:93). تُعلّق السجلات الموجودة قبل الكتابة، ويُنشر الملفان بعد اكتمالهما.  
   **نعم، يفشل الاختبار دونه.** [AutoExportTest.kt:459](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:459) يبدأ بسجلين معلّقين؛ استبعادهما يؤدي إلى URI جديدة وعدد سجلات أكبر. المزود الوهمي يستبعد المعلّق افتراضياً، ويفحص الاختبار التعليق أثناء الكتابة والنشر بعدها وعزل السجلات غير المطابقة.

3. **جواب الإعدادات بعد إعادة إنشاء الشاشة — مُصلح.**  
   تُحفظ القدرة والتجربة والشاشة وتُستعاد في [ProbeActivity.kt:23](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeActivity.kt:23)، ويُستهلك الجواب مرة واحدة باسم التجربة الأصلية عند السطر 179.  
   **نعم.** [AutoExportTest.kt:502](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:502) يعيد إنشاء الشاشة من `Bundle`، ثم يغيّر الشاشة قبل الاستئناف. حذف الاستعادة يفقد الجواب؛ حذف حفظ التجربة يُفشل نسبته؛ وعدم مسح الحالة يكرر التسجيل.

4. **عبور منتصف الليل — مُصلح.**  
   يُختار تاريخ واحد للكتابتين ولقطة المشاركة في [ProbeExports.kt:31](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeExports.kt:31).  
   **نعم.** [AutoExportTest.kt:487](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:487) يغيّر الساعة أثناء الكتابة الأولى. قراءة تاريخ جديد للثانية تنشئ زوجاً إضافياً وتُفشل تأكيدات العدد والتاريخ وتطابق URI سجل النجاح.

5. **كشف حذف المحفّزات والحواجز — مُصلح ضمن الحدود المعلنة.**  
   اختبارات الموافقة وجواب الإذن تبدأ في [AutoExportTest.kt:254](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/test/kotlin/com/handsfree/probes/AutoExportTest.kt:254)، والنهايات الصوتية عند السطر 324، وحاجز الإلغاء عند 396، وسباق الإدراج الأول عند 536.  
   **نعم.** كل اختبار محفّز ينتظر `auto_export` بسبب محدد دون تصدير مساعد؛ حذف المحفّز ينتهي بمهلة انتظار، ولا يعوضه محفّز آخر. حذف `joinAll()` يسمح بلقطة قبل تحرير النهائية فيُفشل تأكيد الصفر. حذف القفل يسمح للاستدعاء الثاني بتجاوز الإدراج المحجوز فيُفشل تأكيدات الانتظار وعدد الاستعلامات. تبقى مسارات الصوت المختبرة حالات رفض أو تعذر أو مهلة، كما يوضح التقرير.

فحص المشاركة: `exported=false` و`grantUriPermissions=true` في [AndroidManifest.xml:28](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/AndroidManifest.xml:28)، والمسار محصور في `cache/probe-shares/` في [probe_share_paths.xml:2](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/res/xml/probe_share_paths.xml:2). النية تمنح قراءة للملفين عبر `ClipData` دون كتابة أو بادئة أو منحة دائمة، وتفحص الاختبارات هذه الأعلام. يتوافق ذلك مع [توثيق FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)، ومعالجة المعلّق مع [توثيق MediaStore](https://developer.android.com/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING).

بتاريخ التحقق **10 نوفمبر 2026**: `INTERNET` غائب عن المصدر وعن الـmanifest المدمج الموجود لنسختي debug وrelease. صلاحيات القراءة الفعلية بين التطبيقات واستعادة التخزين على الأجهزة **يُثبت ميدانياً**.

لم أجد عيباً جديداً مؤكداً يستوجب منع الدمج.

قابل للدمج