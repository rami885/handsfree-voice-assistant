قرأت `AGENTS.md` وراجعت `caa8d62`، والفرق `6e337ed..caa8d62`، والفرق الكامل `origin/main..caa8d62`.

- **P2 — فشل الطلب الأحدث قد يترك الجلسة المرفوضة ظاهرة وخاملة:** [ProbeSession.kt:74](probes/src/main/kotlin/com/handsfree/probes/ProbeSession.kt:74). ينشئ `showSession()` طلباً معلّقاً قبل فحص جاهزية الخدمة والدور، ولا يبطله عند `assistant_not_ready` أو `show_failed`. إذا وصل عرض T2 القديم بعد فشل طلب T10، يمتنع `rejectShow()` عن الإخفاء رغم عدم وجود عرض أحدث سيصل. يمسح تسجيل الجلسة وأفعالها، فيصبح `Stop` بلا أثر. ويُكمل إطار Android إظهار النافذة بعد عودة `onShow`، وفق [مصدر AOSP](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/core/java/android/service/voice/VoiceInteractionSession.java). يلزم إنهاء حالة الطلب عند الفشل ومنع بقاء واجهة الرفض ظاهرة. الاختبار الجديد يسلّم الطلب الأحدث دائماً ولا يفحص ظهور النافذة؛ لذلك لا يكشف هذا المسار.

- **P2 — المقاطعة تُبطل جلسة ظاهرة وتُعطّل زر إغلاقها:** [ProbeController.kt:509](probes/src/main/kotlin/com/handsfree/probes/ProbeController.kt:509)، و[ProbeSession.kt:40](probes/src/main/kotlin/com/handsfree/probes/ProbeSession.kt:40). بعد قبول جلسة، تستدعي مقاطعة مثل `audio_mode` الدالة `invalidateSessionRequest()` دون إخفاء النافذة. تصبح `sessionMatches()` كاذبة، فتتوقف الأفعال ويصبح الضغط على `Stop` بلا أثر، حتى بعد زوال سبب المقاطعة. يلزم إغلاق الواجهة أو إبقاء وسيلة إغلاق فعّالة بعد إبطال الجلسة.

حالة **P2 السابق**: مسار إلغاء العرض الأحدث عولج عندما يبقى الطلب صالحاً ويصل؛ والاختبار الجديد يمارس حذف الطابور فعلاً. لم أجد في مسار الرفض نفسه خللاً إضافياً في تحرير التركيز الصوتي أو إسناد الأحداث.

المراجعة مصدرية؛ لم أشغّل الاختبارات ولم أعدّل أو ألتزم أو أدفع شيئاً. السلوك على الجهاز **يُثبت ميدانياً**، بتاريخ المراجعة 2026-11-12.

**الحكم: غير قابل للدمج (not mergeable).**
