package org.ncssar.rid2caltopo.mapping
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
class MappingSparseCheckpointTest {
    @Test fun restoresBinaryPointsAndRejectsTruncation() {
        val file=File.createTempFile("sparse-checkpoint", ".ply")
        try {
            val header="ply\nformat binary_little_endian 1.0\nelement vertex 500\nproperty float x\nproperty float y\nproperty float z\nend_header\n".toByteArray()
            val body=ByteBuffer.allocate(6000).order(ByteOrder.LITTLE_ENDIAN)
            repeat(1500){body.putFloat(it/10f)}
            file.writeBytes(header+body.array())
            val points=MappingSurfaceCheckpoint.readSparsePoints(file)
            assertEquals(1500,points.size);assertEquals(149.9,points.last(),.00001)
            file.writeBytes(header+body.array().dropLast(1))
            assertThrows(IllegalArgumentException::class.java){MappingSurfaceCheckpoint.readSparsePoints(file)}
        } finally {file.delete()}
    }
}
