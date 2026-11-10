package com.handsfree.probes

import android.Manifest.permission.*
import android.content.AttributionSource
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@Implements(RecognitionService.Callback::class)
class CapturedRecognitionCallback {
    val errors = mutableListOf<Int>()
    val results = mutableListOf<Bundle>()
    @Implementation fun results(results: Bundle) { this.results += results }
    @Implementation fun getCallingAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid()).setPackageName("fixture.caller").build()
    @Implementation fun error(error: Int) { errors += error }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [CapturedRecognitionCallback::class])
class ProxyStopTest : ProbeTest() {
    private lateinit var service: ServiceController<ProxyRecognitionService>
    private lateinit var callback: RecognitionService.Callback
    private lateinit var captured: CapturedRecognitionCallback
    private val delegate = ProxyRecognitionService::class.java.getDeclaredField("delegate").apply { isAccessible = true }
    private val active = ProxyRecognitionService::class.java.getDeclaredField("activeCallback").apply { isAccessible = true }

    @Before fun startSession() {
        shadowOf(app).grantPermissions(RECORD_AUDIO, READ_PHONE_STATE)
        controller.setConsent(RECORD_AUDIO, true)
        controller.phones.start("T1a")
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = "com.google.android.googlequicksearchbox"; name = "FixtureRecognitionService"
            } })
        service = Robolectric.buildService(ProxyRecognitionService::class.java).create()
        callback = ReflectionHelpers.newInstance(RecognitionService.Callback::class.java)
        captured = Shadow.extract(callback)
        invoke("onStartListening", Intent(), callback)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun destroyService() { service.destroy() }

    private fun invoke(method: String, vararg args: Any) {
        val types = if (method == "onStartListening") arrayOf(Intent::class.java, RecognitionService.Callback::class.java)
            else arrayOf(RecognitionService.Callback::class.java)
        ProxyRecognitionService::class.java.getDeclaredMethod(method, *types).apply { isAccessible = true }.invoke(service.get(), *args)
    }

    @Test fun stoppingForwardsLateResultAndKeepsTheSessionBusyUntilItArrives() {
        val first = delegate.get(service.get()) as SpeechRecognizer
        invoke("onStopListening", callback)
        assertSame(first, delegate.get(service.get()))
        assertSame(callback, active.get(service.get()))
        assertFalse(shadowOf(first).isDestroyed)
        assertTrue(captured.errors.isEmpty())
        val nextCallback = ReflectionHelpers.newInstance(RecognitionService.Callback::class.java)
        val nextCaptured = Shadow.extract<CapturedRecognitionCallback>(nextCallback)
        invoke("onStartListening", Intent(), nextCallback)
        assertEquals(listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), nextCaptured.errors)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val result = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late result")) }
        shadowOf(first).triggerOnResults(result)
        assertEquals(listOf(result), captured.results)
        assertTrue(captured.errors.isEmpty())
        assertNull(delegate.get(service.get()))
        assertNull(active.get(service.get()))
        assertTrue(shadowOf(first).isDestroyed)
        invoke("onStartListening", Intent(), nextCallback)
        val next = delegate.get(service.get()) as SpeechRecognizer
        assertNotSame(first, next)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertSame(next, delegate.get(service.get()))
        assertTrue(captured.errors.isEmpty())
        assertEquals(listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), nextCaptured.errors)
    }

    @Test fun stopTimeoutReleasesTheSessionAndAllowsTheNextStart() {
        val first = delegate.get(service.get()) as SpeechRecognizer
        invoke("onStopListening", callback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_999))
        assertSame(first, delegate.get(service.get()))
        assertFalse(shadowOf(first).isDestroyed)
        assertTrue(captured.errors.isEmpty())
        // Repeated stop requests must not extend the bounded wait.
        invoke("onStopListening", callback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertNull(delegate.get(service.get()))
        assertNull(active.get(service.get()))
        assertTrue(shadowOf(first).isDestroyed)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), captured.errors)
        invoke("onStartListening", Intent(), callback)
        val next = delegate.get(service.get()) as SpeechRecognizer
        assertNotSame(first, next)
        shadowOf(first).triggerOnResults(Bundle())
        assertTrue(captured.results.isEmpty())
        assertSame(next, delegate.get(service.get()))
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), captured.errors)
    }

    @Test fun finalErrorAfterStopReleasesTheSessionAndCancelsTheTimeout() {
        val first = delegate.get(service.get()) as SpeechRecognizer
        invoke("onStopListening", callback)
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertNull(delegate.get(service.get()))
        assertNull(active.get(service.get()))
        assertTrue(shadowOf(first).isDestroyed)
        assertEquals(listOf(SpeechRecognizer.ERROR_NETWORK), captured.errors)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(SpeechRecognizer.ERROR_NETWORK), captured.errors)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ProxyStopFrameworkTest : ProbeTest() {
    @Test fun stopTimeoutAlsoReleasesTheFrameworkCallerSession() {
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
            assertFalse(shadowOf(recognizer).isDestroyed)
            assertSame(callback, framework.get(service.get()))
            assertTrue(errors.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(shadowOf(recognizer).isDestroyed)
            assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), errors)
            assertNull(framework.get(service.get()))
        } finally { service.destroy() }
    }
}
