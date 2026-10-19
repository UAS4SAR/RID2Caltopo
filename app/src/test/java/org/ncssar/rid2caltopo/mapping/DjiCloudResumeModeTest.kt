package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DjiCloudResumeModeTest {
    @Test fun resumedFlightRetainsTheExplicitlySelectedTransport() {
        val root=Files.createTempDirectory("r2c-resume-mode-").toFile()
        try {
            val mode=File(root,"cloud/session-transport.json").apply{parentFile!!.mkdirs()}
            mode.writeText("{\"httpDiagnostic\":true}")
            assertTrue(DjiCloudManager.savedHttpDiagnostic(root))
            mode.writeText("{\"httpDiagnostic\":false}")
            assertFalse(DjiCloudManager.savedHttpDiagnostic(root))
        }finally{root.deleteRecursively()}
    }
    @Test fun missingCorruptOrUnrecognizedSavedTransportDefaultsToHttps() {
        val root=Files.createTempDirectory("r2c-resume-mode-").toFile()
        try {
            assertFalse(DjiCloudManager.savedHttpDiagnostic(root))
            val mode=File(root,"cloud/session-transport.json").apply{parentFile!!.mkdirs()}
            for(value in listOf("broken","{}","{\"httpDiagnostic\":\"true\"}","{\"httpDiagnostic\":1}")) {
                mode.writeText(value);assertFalse(DjiCloudManager.savedHttpDiagnostic(root))
            }
        }finally{root.deleteRecursively()}
    }
}
