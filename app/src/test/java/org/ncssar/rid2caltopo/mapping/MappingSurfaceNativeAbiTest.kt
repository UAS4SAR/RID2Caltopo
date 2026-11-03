package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards against shipping MappingSurfaceNative.external funs without matching JNI
 * exports in libr2c_mapping_surface.so (A5 Pro UnsatisfiedLinkError on buildOrthoFill).
 */
class MappingSurfaceNativeAbiTest {
    @Test
    fun everyExternalFunHasExportedJniSymbol() {
        val root = File("").absoluteFile.let { cwd ->
            generateSequence(cwd) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        }
        val kt = File(root, "app/src/main/java/org/ncssar/rid2caltopo/mapping/MappingSurfaceNative.kt").readText()
        val external = Regex("""(?:private\s+)?external\s+fun\s+(\w+)\s*\(""").findAll(kt).map { it.groupValues[1] }.toList()
        assertTrue("MappingSurfaceNative must declare external funs", external.isNotEmpty())
        val so = File(root, "tools/mapping/dist/native/android/arm64-v8a/libr2c_mapping_surface.so")
        assumeTrue("Bundled surface .so present for ABI check", so.isFile)
        val nm = listOf("nm", "llvm-nm", "/usr/bin/nm").firstNotNullOfOrNull { name ->
            runCatching {
                val p = ProcessBuilder(name, "-D", so.absolutePath).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                if (p.waitFor() == 0 && out.isNotBlank()) out else null
            }.getOrNull()
        }
        assumeTrue("nm available for ABI check", nm != null)
        val missing = external.filter { name ->
            "Java_org_ncssar_rid2caltopo_mapping_MappingSurfaceNative_$name" !in nm!!
        }
        assertTrue(
            "Missing JNI exports in ${so.name}: $missing (rebuild with tools/mapping/native/build_surface_engine.py)",
            missing.isEmpty(),
        )
    }
}
