راجعت `2e3f393` المطابق لـ`origin/codex/probes-measurement-fixes` بتاريخ **2026-11-10**. لم أعدّل ملفات أو ألتزم أو أدفع شيئاً.

| البند السابق | نتيجة التحقق |
|---|---|
| بقاء المحاولة بعد استثناء الرد | أُصلح في [AnswerMeasurement.kt:43](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/AnswerMeasurement.kt:43): يسجّل `platform_failure`، ويمحو المحاولة ويلغي المهلة. callback المتزامن يبقى مؤقتاً حتى يعود الإجراء دون استثناء. |
| غياب التركيز الصوتي عن T1b | أُصلح في [ProbeSession.kt:59](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/probes/src/main/kotlin/com/handsfree/probes/ProbeSession.kt:59): تطلب جلسة T1b التركيز بعد اكتساب النافذة للتركيز، وتسجّل النتيجة تحت T1b، مستقلة عن حكم الرد. |
| إمكان إكمال البوابة بشاشة مضاءة | أُصلحت [البطاقة:185](/tmp/claude-0/-home-user-handsfree-voice-assistant/6f798e5b-4235-52d6-beef-2272caaa9ce5/scratchpad/fix/wt/docs/phase-3-implementation/probes-step-by-step.html:185): خطوة صريحة لإطفاء الشاشة قبل الاتصال، وفحص `interactive` عند الوصول وعند بدء محاولة الرد، وإعادة المحاولة إن كانت الشاشة مضاءة عند الوصول. |

البطاقة تطابق أسماء الأزرار، والانتقال على الاشتراك نفسه خلال `≤1000 ms`، ونتائج الرد، وحقول القياس. وتوضح أن **18 نجاحاً على الأقل من 20** تُحصى من التصدير؛ الشيفرة تحكم على كل محاولة منفردة.

نفّذت دوال اختبارات `AnswerMeasurement` الاثنتي عشرة مباشرة من نواتج البناء الموجودة؛ نجحت جميعها. ثم عطّلت الإصلاحات في الشيفرة المحمّلة **داخل الذاكرة فقط**:

| التغيير العكسي | الاختبار | النتيجة |
|---|---|---|
| إزالة تنظيف مسار الاستثناء | `throwingActionTerminatesAttemptAndIgnoresLaterOffhook` | فشل |
| إزالة تأجيل نجاح callback حتى عودة الإجراء | `synchronousCallbackPassesOnlyAfterActionReturnsSuccessfully` | فشل |
| حذف حقل حالة الشاشة | `everyResultRecordsScreenStateAtAttemptStart` | فشل |

**حدّ التحقق:** تعذّر تشغيل اختبارات التكامل عبر Robolectric؛ فشلت تهيئته عند إنشاء مجلد مؤقت بسبب نظام الملفات المخصص للقراءة فقط، قبل تنفيذ الاختبار. تتبعت الاختبارات مقابل الشيفرة السابقة: إزالة طلب التركيز تجعل `assertNotNull(lastAudioFocusRequest)` تفشل، وإزالة تسجيل شاشة الوصول تجعل البحث بـ`single` يفشل؛ **هذا تتبع للشيفرة، وليس تشغيلاً عكسياً منفّذاً لهما**.

لم أجد عيباً جديداً يستوجب الإصلاح. نجح `git diff --check` وبقيت شجرة العمل نظيفة. السلوك على الجهاز «يُثبت ميدانياً».

قابل للدمج