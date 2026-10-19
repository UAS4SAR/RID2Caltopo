package org.ncssar.rid2caltopo.mapping

import java.net.URI
internal object MappingStationURL {
    fun parse(text: String): URI {
        val uri = URI(text.trim())
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Enter the mapping station's LAN address."
        }
        val host = uri.host.lowercase()
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val privateAddress = octets.size == 4 && octets.all { it in 0..255 } &&
            (octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31))
        require(host == "localhost" || host.endsWith(".local") || privateAddress) {
            "Use a local network address for the mapping station."
        }
        return URI(uri.scheme, null, host, uri.port, null, null, null)
    }
}
