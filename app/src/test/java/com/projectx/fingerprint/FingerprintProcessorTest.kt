package com.projectx.fingerprint

import android.graphics.Bitmap
import android.graphics.Color
import com.tcc.fingerprint.processing.FingerprintProcessor
//import com.projectx.fingerprint.processing.FingerprintProcessor
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FingerprintProcessorTest {

    private lateinit var testBitmap: Bitmap
    private lateinit var processor: FingerprintProcessor

    @Before
    fun setUp() {
        // Create a test bitmap with known properties
        testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        // Fill with test pattern
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val color = if (x < 50 && y < 50) Color.WHITE else Color.BLACK
                testBitmap.setPixel(x, y, color)
            }
        }
    }

    @Test
    fun `test processFingerprint with null bitmap should return null`() {
        val result = FingerprintProcessor.processFingerprint(
            inputBitmap = null!!,
            options = FingerprintProcessor.ProcessingOptions()
        )
        assertNull("Should return null for null input", result)
    }

    @Test
    fun `test processFingerprint with basic options should return valid result`() {
        val options = FingerprintProcessor.ProcessingOptions(
            enableMetrics = false,
            enableEnhancement = false
        )

        val result = FingerprintProcessor.processFingerprint(testBitmap, options = options)

        assertNotNull("Should return valid result", result)
        assertEquals("Should return original bitmap as processed", testBitmap, result!!.processedBitmap)
        assertEquals("Should return original bitmap as original", testBitmap, result!!.originalBitmap)
        assertTrue("Quality score should be between 0 and 1", result!!.qualityScore in 0.0..1.0)
        assertNotNull("Recommendation should not be null", result!!.recommendation)
        assertNull("Metrics should be null when disabled", result!!.metrics)
    }

    @Test
    fun `test processFingerprint with metrics enabled should return metrics`() {
        val options = FingerprintProcessor.ProcessingOptions(
            enableMetrics = true,
            enableEnhancement = false
        )

        val result = FingerprintProcessor.processFingerprint(testBitmap, options = options)

        assertNotNull("Should return valid result", result)
        assertNotNull("Metrics should be available when enabled", result!!.metrics)
        assertTrue("Laplacian variance should be positive", result!!.metrics!!.laplacianVariance >= 0)
        assertTrue("Local contrast should be positive", result!!.metrics!!.localContrastStdDev >= 0)
        assertTrue("Glare ratio should be between 0 and 1", result!!.metrics!!.glareRatio in 0.0..1.0)
    }

    @Test
    fun `test ROI cropping with valid bounds`() {
        val roiBounds = android.graphics.RectF(25f, 25f, 75f, 75f)
        val options = FingerprintProcessor.ProcessingOptions()

        val result = FingerprintProcessor.processFingerprint(testBitmap, roiBounds, options)

        assertNotNull("Should return valid result", result)
        assertEquals("Cropped bitmap should be 50x50", 50, result!!.processedBitmap.width)
        assertEquals("Cropped bitmap should be 50x50", 50, result!!.processedBitmap.height)
    }

    @Test
    fun `test ROI cropping with invalid bounds should return original`() {
        val roiBounds = android.graphics.RectF(-10f, -10f, 110f, 110f)
        val options = FingerprintProcessor.ProcessingOptions()

        val result = FingerprintProcessor.processFingerprint(testBitmap, roiBounds, options)

        assertNotNull("Should return valid result", result)
        assertEquals("Should return original bitmap for invalid ROI", testBitmap, result!!.processedBitmap)
    }

    @Test
    fun `test quality assessment with high contrast image`() {
        // Create high contrast test image
        val highContrastBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val color = if (x % 2 == 0) Color.WHITE else Color.BLACK
                highContrastBitmap.setPixel(x, y, color)
            }
        }
        
        val options = FingerprintProcessor.ProcessingOptions(enableMetrics = true)
        val result = FingerprintProcessor.processFingerprint(highContrastBitmap, options = options)
        
        assertNotNull("Should return valid result", result)
        assertTrue("High contrast image should have good quality", result!!.qualityScore > 0.5)
    }

    @Test
    fun `test processing options validation`() {
        val options = FingerprintProcessor.ProcessingOptions(
            claheClipLimit = 5.0,
            claheTileGrid = 15,
            unsharpSigma = 2.0,
            unsharpAmount = 0.8
        )
        
        assertEquals("CLAHE clip limit should be set correctly", 5.0, options.claheClipLimit, 0.001)
        assertEquals("CLAHE tile grid should be set correctly", 15, options.claheTileGrid)
        assertEquals("Unsharp sigma should be set correctly", 2.0, options.unsharpSigma, 0.001)
        assertEquals("Unsharp amount should be set correctly", 0.8, options.unsharpAmount, 0.001)
    }

    @Test
    fun `test error handling with corrupted bitmap`() {
        // Create a bitmap that might cause processing errors
        val corruptedBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        corruptedBitmap.recycle() // This will cause errors when accessed
        
        val options = FingerprintProcessor.ProcessingOptions(enableMetrics = true)
        val result = FingerprintProcessor.processFingerprint(corruptedBitmap, options = options)
        
        // Should handle errors gracefully
        assertNull("Should return null for corrupted bitmap", result)
    }
}
