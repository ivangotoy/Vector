package org.matrix.vector.manager.ui.screens.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Address literals in the forms resolvers accept besides the dotted quad. The host goes through
 * OkHttp's canonicalisation first, as in [fetchStoreSubresource]; each form below reaches loopback
 * when a resolver with inet_aton rules interprets it, so each must count as local.
 */
class LocalAddressLiteralTest {
    private fun hostOf(literal: String) = "http://$literal/a.png".toHttpUrl().host

    @Test
    fun loopbackInEveryLiteralFormIsLocal() {
        val literals =
            listOf(
                "127.0.0.1",
                "127.0.0.1.",
                "0x7f000001",
                "0x7f.0.0.1",
                "127.1",
                "2130706433",
                "0177.0.0.1",
                "017700000001",
                "[::1]",
                "[::ffff:127.0.0.1]",
                "localhost",
            )
        assertEquals(
            "not counted as local",
            emptyList<String>(),
            literals.filterNot { isLocalAddress(hostOf(it)) },
        )
    }

    @Test
    fun privateRangesAreLocal() {
        val literals = listOf("10.0.0.1", "192.168.1.1", "172.16.0.1", "169.254.1.1", "[fd00::1]")
        assertEquals(
            "not counted as local",
            emptyList<String>(),
            literals.filterNot { isLocalAddress(hostOf(it)) },
        )
    }

    @Test
    fun publicNamesAndAddressesAreNotLocal() {
        val hosts = listOf("example.com", "img.example.org", "192.0.2.1", "[2001:db8::1]")
        assertEquals(
            "counted as local",
            emptyList<String>(),
            hosts.filter { isLocalAddress(hostOf(it)) },
        )
    }
}
