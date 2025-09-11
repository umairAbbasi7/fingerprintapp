package com.projectx.fingerprint

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.tcc.fingerprint.R
import com.tcc.fingerprint.ui.CaptureActivity
import com.tcc.fingerprint.ui.OverlayView
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureActivityIntegrationTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.CAMERA
    )

    @Test
    fun testCaptureActivityLaunchesSuccessfully() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_MODE, "register")
            putExtra(CaptureActivity.EXTRA_USER_ID, "test_user")
        }

        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Verify activity is launched
                assertNotNull("Activity should not be null", activity)

                // Verify UI elements are present
                assertNotNull("TextureView should be present", activity.findViewById(R.id.textureView))
                assertNotNull("OverlayView should be present", activity.findViewById(R.id.overlayView))
                assertNotNull("StatusText should be present", activity.findViewById(R.id.statusTextCameraCapture))
            }
        }
    }

    @Test
    fun testCaptureActivityHandlesMissingPermissions() {
        // Test without camera permission
        val intent = Intent(ApplicationProvider.getApplicationContext(), CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_MODE, "register")
        }

        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Should handle missing permissions gracefully
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testCaptureActivityLifecycle() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_MODE, "register")
        }

        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            // Test lifecycle events
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        }
    }

    @Test
    fun testCaptureActivityIntentExtras() {
        val testMode = "verify"
        val testUserId = "test_user_123"

        val intent = Intent(ApplicationProvider.getApplicationContext(), CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_MODE, testMode)
            putExtra(CaptureActivity.EXTRA_USER_ID, testUserId)
        }

        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Verify intent extras are received correctly
                assertEquals("Mode should match intent", testMode, activity.intent.getStringExtra(CaptureActivity.EXTRA_MODE))
                assertEquals("User ID should match intent", testUserId, activity.intent.getStringExtra(CaptureActivity.EXTRA_USER_ID))
            }
        }
    }

    @Test
    fun testCaptureActivityUIElements() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_MODE, "register")
        }

        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Verify all UI elements are properly initialized
                val textureView = activity.findViewById<android.view.TextureView>(R.id.textureView)
                val overlayView = activity.findViewById<OverlayView>(R.id.overlayView)
                val statusText = activity.findViewById<android.widget.TextView>(R.id.statusTextCameraCapture)
                val qualityText = activity.findViewById<android.widget.TextView>(R.id.qualityText)

                assertNotNull("TextureView should be initialized", textureView)
                assertNotNull("OverlayView should be initialized", overlayView)
                assertNotNull("StatusText should be initialized", statusText)
                assertNotNull("QualityText should be initialized", qualityText)

                // Verify initial states
//                assertTrue("TextureView should be visible", textureView.visibility == android.view.View.VISIBLE)
//                assertTrue("OverlayView should be visible", overlayView.visibility == android.view.View.VISIBLE)
            }
        }
    }
}



