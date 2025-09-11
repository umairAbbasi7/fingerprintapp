package com.projectx.fingerprint

import android.graphics.Bitmap
import android.graphics.Color
//import com.projectx.fingerprint.processing.FingerprintProcessor
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import kotlin.system.measureTimeMillis

class PerformanceTest {

    private lateinit var testBitmap: Bitmap
    private lateinit var largeBitmap: Bitmap

    @Before
    fun setUp() {
        // Create test bitmaps
        testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        largeBitmap = Bitmap.createBitmap(2448, 2448, Bitmap.Config.ARGB_8888)
        
        // Fill with test data
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                testBitmap.setPixel(x, y, Color.rgb(x * 2, y * 2, 128))
            }
        }
        
        for (x in 0 until 2448) {
            for (y in 0 until 2448) {
                largeBitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, 128))
            }
        }
    }

//    @Test
//    fun `test small image processing performance`() {
//        val options = FingerprintProcessor.ProcessingOptions(
//            enableMetrics = true,
//            enableEnhancement = true
//        )
//
//        val processingTime = measureTimeMillis {
//            val result = FingerprintProcessor.processFingerprint(testBitmap, options = options)
//            assertNotNull("Should return valid result", result)
//        }
//
//        // Small images should process in under 100ms
//        assertTrue("Small image processing should be fast: ${processingTime}ms", processingTime < 100)
//        println("Small image processing time: ${processingTime}ms")
//    }
//
//    @Test
//    fun `test large image processing performance`() {
//        val options = FingerprintProcessor.ProcessingOptions(
//            enableMetrics = true,
//            enableEnhancement = false // Disable enhancement for performance test
//        )
//
//        val processingTime = measureTimeMillis {
//            val result = FingerprintProcessor.processFingerprint(largeBitmap, options = options)
//            assertNotNull("Should return valid result", result)
//        }
//
//        // Large images should process in under 2000ms (2 seconds)
//        assertTrue("Large image processing should be reasonable: ${processingTime}ms", processingTime < 2000)
//        println("Large image processing time: ${processingTime}ms")
//    }
//
//    @Test
//    fun `test memory usage during processing`() {
//        val options = FingerprintProcessor.ProcessingOptions(
//            enableMetrics = true,
//            enableEnhancement = true
//        )
//
//        val runtime = Runtime.getRuntime()
//        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
//
//        // Process multiple images to test memory management
//        repeat(10) {
//            val result = FingerprintProcessor.processFingerprint(testBitmap, options = options)
//            assertNotNull("Should return valid result", result)
//        }
//
//        // Force garbage collection
//        System.gc()
//
//        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
//        val memoryIncrease = finalMemory - initialMemory
//
//        // Memory increase should be reasonable (less than 50MB)
//        assertTrue("Memory usage should be reasonable: ${memoryIncrease / 1024 / 1024}MB",
//                  memoryIncrease < 50 * 1024 * 1024)
//
//        println("Memory usage: ${memoryIncrease / 1024 / 1024}MB")
//    }
//
//    @Test
//    fun `test processing throughput`() {
//        val options = FingerprintProcessor.ProcessingOptions(
//            enableMetrics = false, // Disable metrics for throughput test
//            enableEnhancement = false
//        )
//
//        val startTime = System.currentTimeMillis()
//        val iterations = 100
//
//        repeat(iterations) {
//            val result = FingerprintProcessor.processFingerprint(testBitmap, options = options)
//            assertNotNull("Should return valid result", result)
//        }
        
//        val totalTime = System.currentTimeMillis() - startTime
//        val throughput = iterations.toDouble() / (totalTime / 1000.0) // images per second
//
//        // Should process at least 10 images per second
//        assertTrue("Throughput should be reasonable: ${throughput} images/sec", throughput >= 10.0)
//        println("Processing throughput: ${throughput} images/sec")
//    }
}



