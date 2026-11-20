package org.matrix.vector.manager.ui.screens.web

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A README image on a public host may answer with a redirect to a local address; the local check
 * has to hold on that hop too. The "public" host is a made-up name that the test's resolver maps to
 * this machine's loopback server, so the first hop passes the literal-address check and the
 * redirect does not.
 */
class StoreSubresourceTest {
    private lateinit var server: ServerSocket
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                    val reply =
                        if (line.startsWith("GET /start "))
                            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/img\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        else
                            "HTTP/1.1 200 OK\r\nContent-Type: image/gif\r\nContent-Length: 3\r\n" +
                                "Connection: close\r\n\r\nGIF"
                    s.getOutputStream().apply {
                        write(reply.toByteArray())
                        flush()
                    }
                }
            }
        }
        val testDns = Dns { host ->
            if (host == "public.example") listOf(InetAddress.getLoopbackAddress())
            else throw java.net.UnknownHostException(host)
        }
        client = OkHttpClient.Builder().dns(testDns).build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String) =
        Request.Builder().url("http://public.example:${server.localPort}$path").build()

    @Test
    fun publicHostIsFetched() {
        localHopsRefused(client).newCall(get("/img")).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun redirectToLocalAddressIsRefused() {
        try {
            localHopsRefused(client).newCall(get("/start")).execute().use {
                fail("redirect to 127.0.0.1 was followed (HTTP ${it.code})")
            }
        } catch (e: IOException) {
            // refused on the second hop, not failed for another reason
            assertEquals("local address refused", e.message)
        }
    }
}
