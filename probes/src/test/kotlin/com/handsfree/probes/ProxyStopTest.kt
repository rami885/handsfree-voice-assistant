package com.handsfree.probes

import android.Manifest.permission.*
import android.content.AttributionSource
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.Process
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@Implements(RecognitionService.Callback::class)
class CapturedRecognitionCallback {
    val errors = mutableListOf<Int>()
    @Implementation fun getCallingAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid()).setPackageName("fixture.caller").build()
    @Implementation fun error(error: Int) { errors += error }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [CapturedRecognitionCallback::class])
class ProxyStopTest : ProbeTest() {
    @Test fun stoppingReleasesTheDelegateAndAllowsTheNextStart() {
        shadowOf(app).grantPermissions(RECORD_AUDIO, READ_PHONE_STATE)
        controller.setConsent(RECORD_AUDIO, true)
        controller.phones.start("T1a")
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = "com.google.android.googlequicksearchbox"; name = "FixtureRecognitionService"
            } })
        val service = Robolectric.buildService(ProxyRecognitionService::class.java).create()
        val callback = ReflectionHelpers.newInstance(RecognitionService.Callback::class.java)
        val captured = Shadow.extract<CapturedRecognitionCallback>(callback)
        val delegate = ProxyRecognitionService::class.java.getDeclaredField("delegate").apply { isAccessible = true }
        val active = ProxyRecognitionService::class.java.getDeclaredField("activeCallback").apply { isAccessible = true }
        fun invoke(method: String, vararg args: Any) {
            val types = if (method == "onStartListening") arrayOf(Intent::class.java, RecognitionService.Callback::class.java)
                else arrayOf(RecognitionService.Callback::class.java)
            ProxyRecognitionService::class.java.getDeclaredMethod(method, *types).apply { isAccessible = true }.invoke(service.get(), *args)
        }
        try {
            invoke("onStartListening", Intent(), callback)
            val first = delegate.get(service.get()) as SpeechRecognizer
            invoke("onStopListening", callback)
            assertNull(delegate.get(service.get()))
            assertNull(active.get(service.get()))
            assertTrue(shadowOf(first).isDestroyed)
            assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), captured.errors)
            invoke("onStartListening", Intent(), callback)
            assertNotNull(delegate.get(service.get()))
            assertNotSame(first, delegate.get(service.get()))
            assertFalse(captured.errors.contains(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
            shadowOf(Looper.getMainLooper()).idle()
        } finally { service.destroy() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ProxyStopFrameworkTest : ProbeTest() {
    @Test fun stopAlsoReleasesTheFrameworkCallerSession() {
        val service = Robolectric.buildService(ProxyRecognitionService::class.java).create()
        try {
            val listenerType = Class.forName("android.speech.IRecognitionListener")
            val binder = android.os.Binder()
            val errors = mutableListOf<Int>()
            val remote = java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "onError" -> { errors += args[0] as Int; null }
                    else -> null
                }
            }
            val callback = RecognitionService.Callback::class.java.getDeclaredConstructor(
                RecognitionService::class.java, listenerType, AttributionSource::class.java
            ).apply { isAccessible = true }.newInstance(service.get(), remote,
                AttributionSource.Builder(Process.myUid()).setPackageName(app.packageName).build())
            val framework = RecognitionService::class.java.getDeclaredField("mCurrentCallback").apply { isAccessible = true }
            framework.set(service.get(), callback)
            val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
            ProxyRecognitionService::class.java.getDeclaredField("delegate").apply { isAccessible = true }.set(service.get(), recognizer)
            ProxyRecognitionService::class.java.getDeclaredField("activeCallback").apply { isAccessible = true }.set(service.get(), callback)
            ProxyRecognitionService::class.java.getDeclaredMethod("onStopListening", RecognitionService.Callback::class.java)
                .apply { isAccessible = true }.invoke(service.get(), callback)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(recognizer).isDestroyed)
            assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), errors)
            assertNull(framework.get(service.get()))
        } finally { service.destroy() }
    }
}
