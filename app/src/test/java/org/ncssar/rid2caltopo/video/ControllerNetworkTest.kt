package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test

class ControllerNetworkTest {
    @Test fun cableConnectionAddsAddressWithoutReplacingWifi() {
        val wifi = ControllerEndpoint("192.168.50.12", false)
        val wired = ControllerEndpoint("169.254.10.2", true)
        assertEquals(listOf(wifi, wired), controllerEndpoints(listOf(wired, wifi)))
        assertEquals(listOf(wifi), controllerEndpoints(listOf(wifi)))
        assertTrue(controllerEndpoints(emptyList()).isEmpty())
        assertEquals("Wi-Fi: rtmp://192.168.50.12/<droneDesig>\nEthernet: rtmp://169.254.10.2/<droneDesig>",
            controllerEndpointInstructions(controllerEndpoints(listOf(wired, wifi))))
        assertEquals("Wi-Fi: Not connected", controllerEndpointInstructions(emptyList()))
        assertEquals("Ethernet", wired.label)
    }

    @Test fun invalidWiredAddressDoesNotHideUsableWifi() {
        val wifi = ControllerEndpoint("192.168.50.12", false)
        for (invalid in listOf("0.0.0.0", "127.0.0.1", "224.0.0.1", "255.255.255.255", "::1")) {
            assertEquals(listOf(wifi), controllerEndpoints(listOf(ControllerEndpoint(invalid, true), wifi)))
        }
    }
}
