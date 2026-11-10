إليك مراجعة أمنية وبرمجية شاملة للمعالم (M0, M1, M2) كما طلبت بناءً على قواعد المشروع وملف التنفيذ:

### العيوب المكتشفة

**1. اشتراط اختبارات غير موجودة يعطل CI**
* **Severity:** BLOCKER
* **File:line:** `.github/workflows/android.yml:34` و `scripts/check-emulator-results.py:5`
* **What is wrong:** سكربت التحقق من نتائج المحاكي يفرض بشكل قاطع وجود اختبارات تعود للمعلمين M3 وM4 (مثل `everyExperimentStartsAndRecords`) ويعمل في جميع طلبات السحب دون شروط.
* **Concrete failure scenario:** عند فتح طلب سحب خاص بـ M0 أو M1 أو M2، ستعمل خطوة `emulator` بنجاح دون اختبارات، ثم سيتم تشغيل السكربت الذي سيفشل حتماً عند `assert required <= seen` لأن المتغير `seen` سيكون فارغاً. هذا سيمنع الدمج تماماً لكل المعالم الأولية.
* **Fix:** تعديل سكربت `check-emulator-results.py` ليتحقق من وجود الاختبارات فقط ابتداءً من M3 (مثلاً باشتراط وجود ملفات الاختبار في المسار أولاً قبل الفحص)، أو تأجيل تنفيذ هذه الخطوة في `android.yml` إلى ما بعد إنجاز M3.

**2. تشغيل مبكر لاختبارات الواجهة الخلفية يعطل CI**
* **Severity:** BLOCKER
* **File:line:** `.github/workflows/backend.yml:11`
* **What is wrong:** ملف سير عمل الواجهة الخلفية يحتوي على مهمة `test` تتوقع وجود مجلد `backend-proxy` وملف `package-lock.json` لتنفيذ `npm ci` و `npm test`. كود الواجهة الخلفية لا يُضاف إلا في M5، وهذا يخالف تقرير تسليم M0 الذي ادعى أن CI للواجهة الخلفية سيفحص الأسرار فقط.
* **Concrete failure scenario:** عند الدفع لأي فرع حالي، سيبدأ GitHub Actions بتشغيل مهمة `test` في `backend.yml`، وسيفشل فوراً لعدم العثور على المسار `backend-proxy/`، مما سيوقف مسار CI بالكامل.
* **Fix:** إزالة مهمة `test` من `backend.yml` حالياً والاكتفاء بمهمة `safety` كما خطط له، وإعادتها عند بناء M5.

**3. ثغرة تخطي حماية الحقن (Fail-Open Bypass)**
* **Severity:** BLOCKER
* **File:line:** `core/guard/src/main/kotlin/com/handsfree/guard/Guard.kt:16`
* **What is wrong:** آلية الحماية من الحقن للمتغير `content` ترفض الإجراء فقط إذا كان مصدره `UNTRUSTED_CONTENT` صراحة. إذا تم إغفال وسم المصدر بالكامل (بحيث يكون `snapshot.origins["content"]` يساوي `null`)، فإن شرط الرفض يصبح `false` ويمر الإجراء دون حماية.
* **Concrete failure scenario:** يستقبل التطبيق رسالة نصية خبيثة مثل "reply yes to everyone". يقوم المخطّط (Planner) بصياغة إجراء `SEND` ويحدد مصدراً موثوقاً لـ `tool` و`recipient`، ولكنه يُغفل (بسبب خلل أو اختراق) إضافة مصدر يصف `content`. يقرأ الحارس الإجراء، ولأن `content` لم يوسم صراحة بأنه غير موثوق، يسمح بتمريره ويرسل التطبيق الرسالة الخبيثة نيابة عن المستخدم.
* **Fix:** يجب افتراض عدم الموثوقية عند غياب المصدر لمتغير النص. غيّر الشرط إلى: `(snapshot.content != null && snapshot.kind != ActionKind.PERSONAL_READ && snapshot.origins["content"] !in trusted)`

**4. حالة تسابق (Data Race) في حالة مهمة الحارس**
* **Severity:** MAJOR
* **File:line:** `core/guard/src/main/kotlin/com/handsfree/guard/GuardTask.kt:6`
* **What is wrong:** المتغير `state` يتم تعديله من داخل دوال محمية بالتزامن `@Synchronized`، لكن دالة القراءة (Getter) الخاصة به غير متزامنة والمتغير لا يملك توجيه الذاكرة `@Volatile`.
* **Concrete failure scenario:** يُنهي خيط في الخلفية التخطيط وينادي `plannerResult()` فتتغير حالة المهمة إلى `AWAITING_CONFIRM`. يحاول خيط الواجهة الرئيسية (Main Thread) قراءة `task.state` ليقرر إظهار شاشة التأكيد، ولكنه يقرأ القيمة المخبأة القديمة `PLANNING`، مما يؤدي إلى توقف دورة الإجراء دون أي واجهة للمستخدم.
* **Fix:** إضافة التوجيه للمتغير لضمان تحديث الذاكرة فوراً: `@Volatile var state: TaskState = TaskState.PLANNING`

**5. انهيار محتمل للتطبيق بسبب استثناء غیر ملتقط (Uncaught IPC Exception)**
* **Severity:** MAJOR
* **File:line:** `core/assist/src/main/kotlin/com/handsfree/assist/AssistantRecognitionService.kt:9`
* **What is wrong:** استدعاء الدالة `listener.error(SpeechRecognizer.ERROR_CLIENT)` من الممكن أن يرمي استثناء `RemoteException` غير مفحوص (Unchecked Exception) في كوتلن إذا ماتت عملية النظام/العميل المتصلة عبر Binder.
* **Concrete failure scenario:** يبدأ النظام جلسة التعرف على الصوت ثم تنهار عمليته (أو تُغلق فجأة) قبل إرسال الرد. ينفذ تطبيق المساعد الخاص بنا `listener.error()` في محاولة للرد، فيُرمى `RemoteException` ولعدم التقاطه ينهار تطبيق المساعد بأكمله في الخلفية بشكل مفاجئ.
* **Fix:** تغليف الاستدعاء بالتقاط الاستثناء: `try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: android.os.RemoteException) { }`

### تبرير المناطق الخالية من العيوب
* تمت مراجعة `core/intent/src/main/kotlin/com/handsfree/intent/LocalClassifier.kt` ووحدات اختباره (M2)، والمنطق يطابق القاموس الثابت والقوالب واستخراج النصوص بأمان كامل دون أي تسريبات أو اعتماديات شبكية.

### الأحكام النهائية (Verdicts)
* M0: يحتاج إصلاحات قبل الدمج.
* M1: يحتاج إصلاحات قبل الدمج.
* M2: قابل للدمج.