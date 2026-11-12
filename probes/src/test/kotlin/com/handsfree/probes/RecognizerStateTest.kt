package com.handsfree.probes

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.RecognitionService
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33])
class RecognizerStateTest : ProbeTest() {
    @Test fun startReadsSecureSettingAndRecognizesBothComponentFormats() {
        val proxy = ComponentName(app, ProxyRecognitionService::class.java)
        for (value in listOf(proxy.flattenToString(), proxy.flattenToShortString())) {
            Settings.Secure.putString(app.contentResolver, "voice_recognition_service", value)
            controller.start("T1a", ProbeConfig())
            assertTrue(controller.repo.lines.value.any { "recognition / voice_recognition_service: $value " in it })
            assertTrue(controller.repo.lines.value.any { "recognition / default_is_proxy: true " in it })
        }
        assertTrue(controller.repo.lines.value.any { "recognition / interpretation: manual_validation_required [MANUAL]" in it })
    }

    @Test fun otherDefaultDoesNotCountAsProxyAndVisibleServicesContainOnlyComponents() = runOnTestDispatcher {
        Settings.Secure.putString(app.contentResolver, "voice_recognition_service", "other.engine/.Recognizer")
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "other.engine"; name = "other.engine.Recognizer"; nonLocalizedLabel = "Must not be recorded" }
        })
        RecognizerState.record(app, controller.repo)
        val rows = controller.repo.snapshot().filter { it.step == "recognition" }
        assertTrue(rows.all { it.experimentId == "T1a" })
        assertEquals("false", rows.single { it.event == "default_is_proxy" }.value)
        val visible = JSONArray(rows.single { it.event == "visible_services" }.value)
        assertTrue((0 until visible.length()).any { visible.getString(it) == "other.engine/other.engine.Recognizer" })
        assertFalse(rows.any { "Must not be recorded" in it.value })
    }

    @Test fun absentSettingAndSecurityExceptionAreRecordedWithoutCrashing() = runOnTestDispatcher {
        RecognizerState.record(app, controller.repo) { null }
        RecognizerState.record(app, controller.repo) { throw SecurityException("denied") }
        val rows = controller.repo.snapshot()
        assertEquals(listOf("unset", "unreadable"), rows.filter { it.event == "voice_recognition_service" }.map { it.value })
        assertEquals(listOf("unknown", "unknown"), rows.filter { it.event == "default_is_proxy" }.map { it.value })
    }

    @Test fun manifestExportsDefaultSelectableRecognitionServiceWithMetadata() {
        val proxy = ComponentName(app, ProxyRecognitionService::class.java)
        val info = app.packageManager.getServiceInfo(proxy, PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertTrue(app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).addCategory(Intent.CATEGORY_DEFAULT).setPackage(app.packageName), 0)
            .any { it.serviceInfo.name == proxy.className })
        info.loadXmlMetaData(app.packageManager, RecognitionService.SERVICE_META_DATA).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals("recognition-service", xml.name)
            assertTrue(xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "selectableAsDefault", false))
            assertEquals(ProbeActivity::class.java.name, xml.getAttributeValue("http://schemas.android.com/apk/res/android", "settingsActivity"))
        }
    }
}
