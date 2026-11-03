package org.ncssar.rid2caltopo.app

import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient

class ExceptionToStringSafetyTest {
    @Test fun nullAndErrorAreSafe() {
        assertEquals("(null throwable)", CaltopoClient.ExceptionToString(null as Throwable?))
        val text = CaltopoClient.ExceptionToString(UnsatisfiedLinkError("No implementation found for buildOrthoFill"))
        assertTrue(text.contains("UnsatisfiedLinkError"))
        assertTrue(text.contains("buildOrthoFill"))
        val caused = CaltopoClient.ExceptionToString(RuntimeException("wrapper", UnsatisfiedLinkError("native")))
        assertTrue(caused.contains("Caused by:"))
        assertTrue(caused.contains("native"))
    }

    /** Guards MappingProcessingManager catching Throwable, not only Exception. */
    @Test fun unsatisfiedLinkErrorIsNotException() {
        val error: Throwable = UnsatisfiedLinkError("missing JNI")
        assertFalse(error is Exception)
        assertTrue(error is Error)
    }
}
