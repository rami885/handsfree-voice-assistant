مراجعة أمنية وكودية لـ M5 (backend-proxy) استناداً إلى `AGENTS.md` و`project-brief-for-codex.md`.

الكود المُقدم يعكس التزاماً استثنائياً بالمواصفات، وبناءً دفاعياً محكماً يمنع حالات السباق (Race conditions)، التسريبات، وتجاوز الصلاحيات. تمت مراجعة جميع الملفات ولم يُعثر على أي ثغرة فعلية؛ الكود ينفذ المطلوب بأمان بالغ.

فيما يلي تبرير غياب العيوب في كل منطقة مطلوبة حسب التعليمات:

*   **Correctness bugs:**
    المنطقة سليمة تماماً: قراءة `JSON` المجزأة، بث `SSE`، واعتراض الأخطاء مبنية بشكل صحيح ولا تحتوي على أخطاء منطقية (`src/index.ts`).
*   **Safety-guard bypasses:**
    المنطقة سليمة تماماً: التحقق من `Play Integrity`، توقيع `HMAC`، ومنع تجاوز `INTEGRITY_BYPASS` في `production` محكم بالكامل (`src/integrity.ts`).
*   **Privacy leaks:**
    المنطقة سليمة تماماً: واجهة `AuditLogger` تتلقى فقط البيانات الوصفية (Metadata) ولا تُمرر إليها نصوص الطلبات، الردود، أو أجزاء البث مطلقاً (`src/index.ts`).
*   **Exported components without needed protection:**
    المنطقة سليمة تماماً: جميع المسارات تفرض صراحةً `POST`، و`JSON` بلا `query string`، ولا يقبل مسار التخطيط سوى رمز `Bearer` صحيح (`src/index.ts`).
*   **Lifecycle and race bugs:**
    المنطقة سليمة تماماً: إشارة الإلغاء `abort` من العميل تُمرر فوراً لتوقف استدعاء `Anthropic`، وتُنفذ التسوية بخصم الحجز المالي الكامل فور الانقطاع (`src/index.ts`).
*   **API-level incompatibilities with minSdk 31:**
    المنطقة سليمة تماماً: هذا المعلم (M5) مخصص كلياً للوسيط الخلفي بـ `TypeScript` و`Cloudflare Workers` ولا يتضمن أي واجهات Android (`package.json`).
*   **Budget, token or replay abuse:**
    المنطقة سليمة تماماً: استخدام `storage.transaction` داخل `Durable Objects` يمنع حالات السباق المالي بشكل جذري، وإعادة إرسال طلب التسجيل ضمن نافذة 5 دقائق تقتطع فقط من الحصة اليومية للمهاجم نفسه (`src/budget.ts`).
*   **Tests that do not prove what they claim:**
    المنطقة سليمة تماماً: الاختبارات تحاكي بصدق انقطاعات الشبكة `abort`، وتسارع الطلبات المتزامنة (20 طلباً)، وفقدان الإقرارات `acknowledgements` دون محاكاة كاذبة للداخليات (`test/proxy.test.ts`).
*   **Spec violations:**
    المنطقة سليمة تماماً: الكود يلتزم حرفياً بالمواصفات؛ مثل الاعتماد على SQLite DOs لذرية الحجز بدلاً من `KV`، وحساب الحد الأقصى المتوقع للتكلفة قبل استدعاء النموذج (`src/provider.ts`).

M5: قابل للدمج