# مصفوفة البناء

تاريخ التحقق: 2026-11-09. تُثبت هذه المصفوفة في M0، ويستلزم تغييرها سبباً في تقرير المعلم.

| المكوّن | الإصدار |
|---|---|
| JDK | 17 |
| AGP | 9.5.1 |
| Gradle Wrapper | 9.6.0 |
| Kotlin | 2.4.21 |
| KSP | 2.3.13 |
| Hilt | 2.60.1 |
| Coroutines | 1.11.0 |
| Room | 2.8.5 |
| Kover | 0.9.11 |
| JUnit | 4.13.2 |
| AndroidX Test Runner | 1.7.0 |
| Espresso | 3.7.0 |
| Android SDK | compile/target 36، min 31 |
| Build Tools | 36.0.0 |

اختير AGP 9.5.1 مع Gradle 9.6.0 وJDK 17 وفق [جدول التوافق الرسمي](https://developer.android.com/build/releases/agp-9-5-0-release-notes)، واستخدم Kotlin المدمج في وحدات Android دون kotlin-android. إصدار Kotlin في الكتالوج يثبت إضافة JVM المشتركة مع اعتماد AGP على مترجم Kotlin. [دليل Kotlin المدمج](https://developer.android.com/build/migrate-to-built-in-kotlin). توافق المصفوفة عملياً تحسمه نتيجة البناء المسجلة في تقارير التسليم؛ لا تعني كتابة الإصدارات أن CI نجح.

لا يستخدم هذا الهيكل Compose لعدم وجود شاشة إعداد نهائية ضمن نطاق الدفعة. واجهات التجارب تشخيصية فقط. لا تحتوي وحدات الحارس والمصنّف على Android.

## التشغيل المحلي في الحاوية

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.cache/gradle"
export TMPDIR="$PWD/.cache/tmp"
export ANDROID_USER_HOME="$PWD/.cache/android"
mkdir -p "$TMPDIR"
./gradlew build test lint
```

لا تُشغّل أوامر تثبيت SDK على المسار المشترك في هذه الحاوية. الحزم المثبتة تُقرأ من `ANDROID_HOME`.
