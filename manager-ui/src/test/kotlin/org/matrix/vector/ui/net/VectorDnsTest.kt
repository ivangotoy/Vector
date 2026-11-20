package org.matrix.vector.ui.net

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorDnsTest {

    private val settings =
        object : NetworkSettings {
            override val dohEnabled: StateFlow<Boolean> = MutableStateFlow(true)

            override fun setDohEnabled(enabled: Boolean) = Unit
        }

    private val systemAddress = InetAddress.getByName("192.0.2.1")
    private val dohAddress = InetAddress.getByName("198.51.100.1")
    private val systemCalls = AtomicInteger()
    private val system = Dns {
        systemCalls.incrementAndGet()
        listOf(systemAddress)
    }

    /** A DoH resolver that fails every lookup with what [failure] returns, counting the calls. */
    private class FailingDoh(private val failure: () -> Exception) : Dns {
        val calls = AtomicInteger()

        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            throw failure()
        }
    }

    private fun dnsOver(doh: Dns) = VectorDns(settings, system) { doh }

    @Test
    fun answersAboutTheNameDoNotLatch() {
        val answers =
            listOf<() -> Exception>(
                { UnknownHostException() },
                { UnknownHostException("DNS server failure") },
                { UnknownHostException("DNS returned no addresses for missing.example.com") },
                { UnknownHostException("private hosts not resolved") },
                {
                    UnknownHostException().apply {
                        addSuppressed(UnknownHostException("DNS server failure"))
                    }
                },
            )
        for (answer in answers) {
            val doh = FailingDoh(answer)
            val dns = dnsOver(doh)

            assertEquals(listOf(systemAddress), dns.lookup("missing.example.com"))
            dns.lookup("other.example.com")

            assertEquals(2, doh.calls.get())
            assertEquals(DohStatus.Untested, dns.status.value)
        }
    }

    @Test
    fun unreachableEndpointLatches() {
        val failures =
            listOf<() -> Exception>(
                { ConnectException("Failed to connect") },
                { SocketTimeoutException("timeout") },
                { IOException("response: 403 Forbidden") },
                { IllegalStateException("Unable to load PublicSuffixDatabase.list resource.") },
                {
                    UnknownHostException().apply {
                        addSuppressed(SocketTimeoutException("timeout"))
                    }
                },
            )
        for (failure in failures) {
            val doh = FailingDoh(failure)
            val dns = dnsOver(doh)

            assertEquals(listOf(systemAddress), dns.lookup("a.example.com"))
            assertEquals(listOf(systemAddress), dns.lookup("b.example.com"))

            assertEquals(1, doh.calls.get())
            assertTrue(dns.status.value is DohStatus.FellBack)
        }
    }

    @Test
    fun interruptDoesNotLatch() {
        val doh = FailingDoh { InterruptedException() }
        val dns = dnsOver(doh)

        dns.lookup("a.example.com")
        assertTrue(Thread.interrupted())
        dns.lookup("b.example.com")
        Thread.interrupted()

        assertEquals(2, doh.calls.get())
    }

    @Test
    fun workingLookupIsReturned() {
        val dns = dnsOver { listOf(dohAddress) }

        assertEquals(listOf(dohAddress), dns.lookup("a.example.com"))
        assertEquals(DohStatus.Working("a.example.com"), dns.status.value)
        assertEquals(0, systemCalls.get())
    }

    /**
     * OkHttp's own DoH resolver, with its HTTP exchange answered by [respond].
     *
     * Private names are resolved so the lookup does not consult the public suffix list, which needs
     * an Android context on the host. Each lookup below uses its own name: OkHttp caches failures.
     */
    private fun okHttpDoh(calls: AtomicInteger, respond: (Interceptor.Chain) -> Response): Dns =
        DnsOverHttps.Builder()
            .client(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        calls.incrementAndGet()
                        respond(chain)
                    }
                    .build()
            )
            .url("https://doh.example.com/dns-query".toHttpUrl())
            .resolvePrivateAddresses(true)
            .build()

    @Test
    fun okHttpNxdomainDoesNotLatch() {
        val calls = AtomicInteger()
        // Header only: id 0, a response with recursion, rcode 3 (NXDOMAIN), no records.
        val nxdomain = byteArrayOf(0, 0, 0x81.toByte(), 0x83.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val doh =
            okHttpDoh(calls) { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(nxdomain.toResponseBody("application/dns-message".toMediaType()))
                    .build()
            }
        val dns = dnsOver(doh)

        assertEquals(listOf(systemAddress), dns.lookup("missing.example.com"))
        val afterFirst = calls.get()
        dns.lookup("other.example.com")

        assertTrue(calls.get() > afterFirst)
        assertEquals(2, systemCalls.get())
    }

    @Test
    fun okHttpConnectFailureLatches() {
        val calls = AtomicInteger()
        val doh = okHttpDoh(calls) { throw ConnectException("Failed to connect") }
        val dns = dnsOver(doh)

        dns.lookup("a.example.com")
        val afterFirst = calls.get()
        dns.lookup("b.example.com")

        assertEquals(afterFirst, calls.get())
        assertTrue(dns.status.value is DohStatus.FellBack)
    }
}
