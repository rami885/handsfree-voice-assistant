فحص ساكن دون تعديل ملفات أو تشغيل بناء. تعذّر بدء `codex exec --ephemeral` بسبب نظام الملفات للقراءة فقط؛ النتائج أدناه من قراءة الفروق والمصادر مباشرةً. إحالات الكود تخص `origin/codex/m2-intent`.

١) الأخطاء والسلوك الخاطئ

- [MAJOR] `core/guard/src/main/kotlin/com/handsfree/guard/Audit.kt:36` — يستخدم `FileAuditSink` الدالة `Files.writeString` المتاحة في Android منذ 36.1، دون إعداد لتعويض غيابها؛ عند استدعاء `record()` على أجهزة API 31–35 يفشل تسجيل التدقيق بـ`NoSuchMethodError`. اختبار JDK 17 لا يكشف ذلك. تحقق توثيقي: 2026-11-10؛ النتيجة على الجهاز «يُثبت ميدانياً». يلزم استخدام كتابة متوافقة مع الحد الأدنى. [مرجع Android](https://developer.android.com/reference/java/nio/file/Files#writeString(java.nio.file.Path,%20java.lang.CharSequence,%20java.nio.file.OpenOption...)).

- [MINOR] `core/intent/src/main/kotlin/com/handsfree/intent/LocalClassifier.kt:33` — فحص السالب يتعرف على الشرطة ASCII فقط، بينما التطبيع في السطر 66 يحذف علامة السالب `−`؛ لذلك يتحول `Volume −10` إلى مستوى موجب `10`، وكذلك `Volume -one` بعد تحويل العدد المنطوق، خلافاً لقرار إعادة الصيغ السالبة إلى المخطّط.

٢) الأمان والخصوصية

- [MAJOR] `core/guard/src/main/kotlin/com/handsfree/guard/Guard.kt:35` — سياسة القفل تعتمد على `ActionSnapshot.kind` القادم ضمن نتيجة المخطّط، دون ربط النوع بتعريف موثوق للأداة؛ فلقطة `read_call_log` المصنفة خطأً `SYSTEM_NO_DATA` تُسمح فوق القفل، مع الإذن والموافقة، رغم إطفاء النتائج الشخصية. يلزم تثبيت تصنيف الأداة والتحقق من توافق اللقطة معه.

- [MAJOR] `core/guard/src/main/kotlin/com/handsfree/guard/Guard.kt:17` — غياب مصدر `content` لا يُرفض؛ الشرط يمنع فقط القيمة الصريحة `UNTRUSTED_CONTENT`. لقطة إرسال تحمل نصاً مقتبساً بلا وسم مصدر تصل إلى `NeedConfirm` ثم `Allow`، مع أن المواصفة تشترط تتبع مصدر كل معامل وحصر الاقتباس غير الموثوق في القراءة. اختبار `GuardTest.kt:53` يقبل بالفعل محتوى إرسال دون مصدر له.

- [MAJOR] `core/guard/src/main/kotlin/com/handsfree/guard/Guard.kt:11` — فحص مصدر المستلم يستثني `notificationKey` و`remoteInputActionId`، رغم أنهما يحددان مسار الرد الفعلي. يمكن تمرير اسم مستلم موثوق مع مفتاح إشعار وفعل رد موسومين `UNTRUSTED_CONTENT`، والحصول على تأكيد ثم سماح؛ ثبات اللقطة يمنع تبديلهما لاحقاً، لكنه لا يرفض الوجهة غير الموثوقة ابتداءً.

٣) معايير القبول والاختبارات

- [MINOR] `build.gradle.kts:43` — قاعدة منع اعتماديات الشبكة تفحص اعتماديات Maven ومعرّفات وحداتها فقط؛ إضافة `implementation(files("libs/okhttp.jar"))` تتجاوز الفحص لأن اعتماديات الملفات لا تمر بالفرعين المستخدمين. اختبار حقن gRPC الوارد في تقرير M2 يثبت رفض إحداثية معروفة، ولا يثبت منع مكتبات الشبكة بجميع طرق إضافتها؛ يلزم رفض اعتماديات الملفات غير المراجعة أو فحصها أيضاً.

٤) قرارات التقارير التي أختلف معها

- [MAJOR] `docs/phase-3-implementation/m1-delivery-report.md:39` — لا يكفي ذكر «المستلم المتاح» في التأكيد: `Guard.kt:41` يختار الاسم **بدلاً من** الرقم. عند وجود `recipientLabel="Nikos"` و`recipientId` يحمل رقماً خاطئاً، يسمع المستخدم الاسم وحده ولا يستطيع اكتشاف الخطأ؛ وثيقة v12 تشترط نطق الاسم والرقم قبل الاتصال أو الإرسال. يلزم تمثيل الرقم الفعلي ونطقه، واختبار وجود الاسم والرقم معاً.

- [MINOR] `docs/phase-3-implementation/m0-delivery-report.md:48` — أختلف مع تأجيل تثبيت checksum للتوزيعة اعتماداً على تنزيلها وتشغيلها؛ `gradle/wrapper/gradle-wrapper.properties:3` لا يحتوي `distributionSha256Sum`، ولذلك لا يتحقق Wrapper من تطابق التنزيل مع البصمة المعتمدة. تعذر الوصول وقت التنفيذ يفسر النقص، لكنه لا يعوض التحقق من مصدر مستقل. [توثيق Gradle](https://docs.gradle.org/current/userguide/best_practices_security.html#validate_gradle_distribution_sha256_checksum).

M0: قابل للدمج  
M1: يحتاج إصلاحات قبل الدمج  
M2: يحتاج إصلاحات قبل الدمج