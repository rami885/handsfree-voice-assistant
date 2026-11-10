# وسيط التخطيط — M5

هيكل TypeScript لـ Cloudflare Workers، بلا نشر، وبلا عميل شبكة في Android. تاريخ التحقق من الكود والمراجع: 2026-11-10. اختبارات Vitest تستبدل Google ومزود النموذج وDurable Object storage؛ لا تثبت اتصالاً حياً بهذه الخدمات ولا تسعيراً فعلياً. تكامل Play Integrity على جهاز حقيقي «يُثبت ميدانياً».

## التشغيل والتحقق

Node 22.23.4 (LTS) وnpm 10.9.9، والإصدارات الدقيقة في package.json وpackage-lock.json.

```bash
cd backend-proxy
npm ci
npm test
npm run lint
```

للتطوير المحلي فقط: انسخ `.dev.vars.example` إلى `.dev.vars` المتجاهل في Git، واضبط أسراراً محلية من البيئة، وحصصاً وأسعاراً غير صفرية في إعداد محلي. `npm run dev` يشغّل Wrangler محلياً؛ طلب خطة سيصل إلى المزود إذا أُعطي مفتاح صالح، لذلك الاختبارات أعلاه هي المسار الآلي دون أسرار أو استدعاء حي. لم يُشغّل Wrangler أو يُنشر شيء في هذه الدفعة. لا تلتزم ملف الأسرار. `wrangler.toml` يضم حساباً وهمياً، workers_dev=false، وحصصاً صفرية ترفض الطلبات.

## البروتوكول

كل المسارات POST وJSON فقط، بلا query string؛ أقصى جسم التسجيل 32 KiB والخطة 16 KiB. الأخطاء JSON من `code` ثابت وrequestId عشوائي، ولا تعيد تفاصيل الاستثناء أو نص المزود.

`POST /v1/register`:

```json
{
  "registration": { "nonce": "RANDOM_BASE64URL_AT_LEAST_16_CHARS" },
  "integrityToken": "PLAY_INTEGRITY_TOKEN_PLACEHOLDER"
}
```

تُحسب `requestHash` كـ base64url بلا padding لـ SHA-256 على UTF-8 لـ JSON مضغوط لكائن `registration` فقط، بمفاتيح مرتبة تصاعدياً، دون مسافات. مثال الجسم الداخل للتجزئة: `{"nonce":"RANDOM_BASE64URL_AT_LEAST_16_CHARS"}`. عند التجديد يضاف `previousToken` إلى الكائن نفسه **قبل التجزئة**. لا يدخل integrityToken في التجزئة لأنه يُنشأ بعدها؛ الغلاف ليس جزءاً من البيانات المثبتة. الحقول الوحيدة nonce (16–128 محرف base64url) وpreviousToken الاختياري غير الفارغ. هذا قرار بروتوكول صريح لتجنب تجزئة الرمز داخل نفسه، ويجب على العميل المستقبلي مطابقته.

في production تُفك نتيجة الطلب القياسي عبر Google بخدمة OAuth موقعة RS256 من أسرار البيئة. تُطابق requestHash والحزمة وشهادة التطبيق وPLAY_RECOGNIZED وMEETS_DEVICE_INTEGRITY، مع عمر أقصى خمس دقائق وسماح ساعة الخادم للمستقبل 30 ثانية. الناجح يعيد `{token, expiresAt}`؛ HMAC-SHA256 وصلاحية 30 يوماً. التجديد بالمسار نفسه وبـ previousToken يحتفظ بمعرّف التثبيت والحصة؛ يجوز أن تكون صلاحيته منتهية بشرط صحة توقيعه وحكم Integrity جديد. حماية إعادة استخدام رمز الطلب القياسي تعتمد أيضاً على خدمة Google؛ «يُثبت ميدانياً».

`POST /v1/plan` يتطلب `Authorization: Bearer <installation-token>`، ولا يقبل Integrity token بديلاً عنه، حتى في التطوير. مثال الجسم:

```json
{
  "text": "Pause",
  "tools": [{ "name": "pause", "params": {}, "permission": "NONE", "sensitivity": "NONE" }]
}
```

يُرسل النص ووصف الأدوات كبيانات JSON في رسالة مستخدم، مع system prompt ثابت. يعيد SSE من Anthropic Messages API، ولا ينفذ أدوات. لا تُستبدل بوابة الحارس وتأكيد المستخدم بهذه التعليمات النصية. الإلغاء يوقف upstream؛ مهلة المزود 30 ثانية. 401 للهوية، 429 للحصة أو السقف قبل أي استدعاء نموذج، و502/503 لأعطال عامة دون محتوى خاص.

## البيئة المطلوبة مستقبلاً

| المتغير | المعنى |
|---|---|
| ENVIRONMENT | development أو production صراحةً |
| INTEGRITY_BYPASS | true أو false صراحةً؛ true مسموح في development فقط، ويُرفض مع production |
| INSTALL_TOKEN_SECRET | سر منصة لا يقل عن 32 محرفاً؛ ليس مفتاحاً داخل Android |
| GOOGLE_SERVICE_ACCOUNT_EMAIL / GOOGLE_PRIVATE_KEY | حساب الخدمة ومفتاح PKCS8 في أسرار المنصة، مع إتاحة فك Integrity للمشروع |
| EXPECTED_PACKAGE / EXPECTED_CERTIFICATE_SHA256 | الحزمة وشهادة SHA-256 بصيغة base64url التي تعيدها Google |
| MODEL_API_KEY / MODEL_ID | مفتاح من env ومعرّف نموذج يحدده المشغل؛ قيم وهمية في المستودع |
| INSTALL_DAILY_BUDGET_MICROS / INSTALL_DAILY_REQUESTS | حصة يوم UTC للتثبيت: ميكرو دولار وعدد الطلبات |
| GLOBAL_DAILY_BUDGET_MICROS | سقف يوم UTC العام |
| REGISTRATIONS_PER_VERDICT_DAILY | عدد التسجيلات والتجديدات لكل فئة حكم في اليوم |
| INPUT_MICROS_PER_TOKEN / OUTPUT_MICROS_PER_TOKEN | أسعار المشغل بالميكرو دولار للتوكن؛ تسمح بكسور موجبة، ليست أسعاراً معتمدة هنا |
| MAX_OUTPUT_TOKENS | حد الخرج من 1 إلى 4096 |
| INSTALL_BUDGETS / GLOBAL_BUDGETS | ربط Durable Objects في Wrangler، مع هجرة SQLite v1 |

## الذرية وحساب الإنفاق

Durable Object لكل معرّف تثبيت ثابت عبر التجديد، وآخر باسم global. لا Workers KV. كل reserve/settle/register يقرأ ويكتب داخل `storage.transaction`؛ إيصالات عشوائية مستقلة تجعل إعادة المحاولة ثلاث مرات آمنة عند فقدان إقرار الحفظ. يُحجز التثبيت ثم العام قبل اتصال المزود. إن فشل العام تُفك حجوزات الطلب دون احتساب طلب مستهلك؛ لا يُدعى وجود معاملة موزعة واحدة بين الكائنين.

حد كلفة متحفظ: عدد بايتات UTF-8 للرسالة الفعلية وsystem prompt، مع 1024 توكن هامش تغليف، مضروباً بسعر الإدخال، إضافة أقصى خرج مضروباً بسعره، ثم التقريب إلى أعلى. لا أدوات خادم ولا صور ولا caching في الطلب. يلزم اعتماد هذا الحد والأسعار للنموذج المختار قبل التشغيل الحقيقي؛ لا ضمان فاتورة لنموذج مجهول أو تسعير خاطئ. بعد SSE كامل وmessage_stop تُسوّى usage التراكمية المعلنة مرة واحدة. عند خطأ أو إلغاء أو usage ناقصة/غير مفهومة/متجاوزة للحد تُحتسب قيمة الحجز كاملة؛ لا استدعاء تلقائي جديد. الاختبارات تشمل منافسة 20 طلباً وسقفاً عاماً عبر تثبيتين وفقدان الإقرار وحدود اليوم.

إذا تعذر الحفظ بعد ثلاث محاولات تُسجل settlement_pending بلا محتوى ويظل الحجز محافظاً على السقف؛ لا تحرير تلقائي قد يسمح بتجاوز الإنفاق. توقف العامل بين المرحلتين قد يترك حجزاً حتى نهاية يومه. تنظيف الأيام القديمة واستعادة الحجوزات بعد الأعطال عمل تشغيلي للمراجعة قبل النشر؛ البيانات الحالية لا تُحذف تلقائياً. مخزن الاختبار يثبت المنطق الذري بالتزامن، ولا يحل محل تجربة runtime/حساب Cloudflare فعلية.

حكم Integrity لا يوفر هنا هوية عتاد ثابتة. مفتاح حد التسجيل تجزئة الحزمة والشهادات وعلامات الجهاز وحالة اعتراف التطبيق، مستثنياً timestamp/requestHash/nonce كي لا يُنشئ كل طلب فئة جديدة. لذلك قد يشترك مستخدمون شرعيون في الفئة نفسها، وقد تتغير الفئة عند تغير الحكم. هذا حد متحفظ مع سقف الإنفاق العام، وليس ادعاء منع إعادة التثبيت لكل جهاز. يلزم قرار مراجعة قبل توسيع الخدمة؛ إضافة حسابات أو Device Recall خارج هذه الدفعة.

## الخصوصية والفحوص

المسجل يقبل مخططاً مغلقاً: code وrequestId وstatus وcostMicros فقط. لا نصوص طلب/رد/أجزاء بث ولا استثناءات خام ولا رموز تثبيت/Integrity/OAuth أو مفاتيح. اختبارات النجاح وخطأ المزود وانقطاع البث والإلغاء تفحص المسجل؛ وCI يفحص أنماط الأسرار في المستودع. لا observability إضافية أو اعتماد يسجل HTTP تلقائياً في الكود.

أعاد npm audit بتاريخ 2026-11-10 عشر إشارات moderate متسلسلة أصلها minimatch 10.2.6 (تضخم glob/حجب خدمة) ضمن ESLint وtypescript-eslint التطويريين؛ لا اعتمادات npm إنتاجية. الإصلاح المقترح آلياً يخفض إصدارات رئيسية، ولم نطبق تغييراً رئيسياً أو override غير مثبت للتوافق. globs الفحص من ملفات المستودع، وليست مدخلات مستخدم الوسيط. تُراجع ترقية الأدوات قبل التشغيل؛ audit ليس بوابة مطلوبة في الملف ولا أخفينا نتيجته.

المراجع الرسمية التي روجعت في 2026-11-10: [Play Integrity standard](https://developer.android.com/google/play/integrity/standard)، [معاملات Durable Objects SQLite](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)، [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming). سلوك Integrity الفعلي على Android «يُثبت ميدانياً».
