package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class MutualAidPackageTransferTokenTest {
    private val config = MutualAidPackageTransferToken.Config(
        host = "192.168.68.67", port = 43363, sessionId = "test-session",
        packageName = "Old_Airport_op1", sizeBytes = 123456789L,
        sha256 = "a".repeat(64), tlsPublicKeySha256 = "b".repeat(64),
        expiresAtEpochMs = 1900000000000L
    )

    @Test fun `shared Apple fixture decodes with Android`() {
        assertEquals(config, MutualAidPackageTransferToken.decode("R2CMAPKG1:UGeeORwNHE0nHw5yKyj/p2j0Fv5IDA2XK1JsoyS2LmWpFwFCQlAsTxA3FlrC2Be2EvXlZR2lJymh2DW02qSpZtrxPtfcMvXrFsFCFG2qIH0j2RBJFFsXPO0dMNWzTFWtrrmFNsOYWNWlUcFDSqrXPA4BNurlWkqwEkSrNBEYOA4iWlWzTFWtrrmFNsOYWNWlUcFDSqrXPA4BNu2ipxehORwNNsIXOqmlWNrdTwrsri4GNBSXWlriUkJASAWYPqmCNDWiWcexEcONNsIXOqmlWNrdTwrsri4GNBSXWlriUkJASEXrFqaCFG2dIywmryXBHOBpAP99McBdpqW+"))
    }

    @Test fun `QR URI and pasted token recover identical transfer endpoint and pin`() {
        val token = MutualAidPackageTransferToken.encode(config)
        val qr = "r2cmapkg1://" + token.removePrefix(MutualAidPackageTransferToken.MAGIC_PREFIX)
        assertEquals(config, MutualAidPackageTransferToken.decode(token))
        assertEquals(config, MutualAidPackageTransferToken.decode("  $qr\n"))
        assertTrue(MutualAidPackageTransferToken.isValidToken(qr))
    }

    @Test fun `invalid or unrelated payload cannot start package import`() {
        assertNull(MutualAidPackageTransferToken.decode("r2cmapkg1://broken"))
        assertNull(MutualAidPackageTransferToken.decode("R2C2:broken"))
        assertNull(MutualAidPackageTransferToken.decode(MutualAidPackageTransferToken.encode(config.copy(tlsPublicKeySha256 = ""))))
    }
}
