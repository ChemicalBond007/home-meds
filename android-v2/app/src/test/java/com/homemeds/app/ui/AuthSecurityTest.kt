package com.homemeds.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class AuthSecurityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test fun secretIsSensitiveAndClearedAfterSixtySeconds() {
        copyTotpSecret(context, "test-secret")
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertEquals("test-secret", clipboard.primaryClip!!.getItemAt(0).text.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test fun delayedCleanupDoesNotEraseNewClipboardContents() {
        copyTotpSecret(context, "test-secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "keep"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals("keep", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun authenticatorAlwaysUsesSystemChooser() {
        val chooser = authenticatorChooser("otpauth://totp/test?secret=synthetic")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("otpauth", target.data!!.scheme)
        assertNull(target.component)
    }
}
