package org.matrix.vector.manager.ui.screens.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a WebView subresource through the shared client, or refuses it.
 *
 * Handed to `StoreHtmlPane` as its `fetchSubresource` so the shared renderer's images get the app's
 * DoH resolver and disk cache, and so anything that is not an image over http(s) is refused. It does
 * not stop a README from reporting the reader's IP to a host it points at — only proxying would, and
 * there is nothing to proxy through — but it does bound what can be loaded.
 *
 * Runs on a WebView background thread, so the blocking call is correct here. The returned stream is
 * closed by the WebView, which is what releases the connection; closing the response here instead
 * would truncate the image.
 */
fun fetchStoreSubresource(
    client: OkHttpClient,
    request: WebResourceRequest,
): WebResourceResponse? {
    val url = request.url ?: return blocked()
    when (url.scheme?.lowercase()) {
        // Inline images and the document itself. Inert, and nothing to fetch.
        "data", "about", null -> return null
        "http", "https" -> Unit
        else -> return blocked()
    }
    if (!request.method.equals("GET", ignoreCase = true)) return blocked()

    return try {
        val httpRequest = Request.Builder().url(url.toString()).build()
        if (isLocalAddress(httpRequest.url.host)) return blocked()
        val response = localHopsRefused(client).newCall(httpRequest).execute()
        val type = response.body.contentType()
        if (!response.isSuccessful || type?.type != "image") {
            response.close()
            blocked()
        } else {
            WebResourceResponse("${type.type}/${type.subtype}", "UTF-8", response.body.byteStream())
        }
    } catch (_: Exception) {
        blocked()
    }
}

/**
 * [client] with the local-address checks on every hop: a redirect must not reach an address the
 * first URL could not, and a public name must not lead to a local address either. A network
 * interceptor runs once the connection is open, so it sees the address actually connected to; a
 * refused hop costs a TCP connect but never sends the request. Through a proxy the connected
 * address is the proxy's, so only the host check applies there.
 */
internal fun localHopsRefused(
    client: OkHttpClient,
    isLocal: (java.net.InetAddress) -> Boolean = ::isLocalInetAddress,
): OkHttpClient =
    client
        .newBuilder()
        .addNetworkInterceptor { chain ->
            if (isLocalAddress(chain.request().url.host)) throw java.io.IOException("local address refused")
            val route = chain.connection()?.route()
            val connected = route?.socketAddress?.address
            if (route?.proxy?.type() == java.net.Proxy.Type.DIRECT && connected != null && isLocal(connected)) {
                throw java.io.IOException("local address refused")
            }
            chain.proceed(chain.request())
        }
        .build()

/** An IPv4 address in the one form every resolver reads the same way. */
private val DOTTED_QUAD = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

/**
 * Whether [host] (as OkHttp normalised it) is an address on this device or its local network, which
 * a README has no business making the manager request. Covers literal addresses and `localhost`;
 * a public name that resolves to a private address is caught by [localHopsRefused] on the address
 * it connected to.
 */
internal fun isLocalAddress(host: String): Boolean {
    val h = host.lowercase().removeSuffix(".") // a fully qualified "127.0.0.1." is the same host
    if (h == "localhost" || h.endsWith(".localhost")) return true
    if (!h.contains(':')) {
        // A host name ends in a label that starts with a letter. Anything else is an address
        // literal, and resolvers read more forms than the dotted quad (hex, octal, a single number,
        // fewer than four parts): those are refused rather than interpreted differently here.
        val last = h.substringAfterLast('.')
        if (last.isEmpty() || (!last[0].isDigit() && !last.startsWith("0x"))) return false
        if (!DOTTED_QUAD.matches(h)) return true
    }
    val address = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return true
    return isLocalInetAddress(address)
}

/** Whether [address] is on this device or its local network: the one rule both checks apply. */
internal fun isLocalInetAddress(address: java.net.InetAddress): Boolean =
    address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        address.isAnyLocalAddress ||
        (address is java.net.Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)

/**
 * A 1×1 transparent GIF. A refused subresource should render as nothing, not as a broken image.
 *
 * It has to be a real, decodable image. An empty body, or one under a type no `<img>` can decode,
 * is a decode failure, and WebKit answers that with its broken-image glyph and the alt text beside
 * it — which is what a README linking a chart that has since started 404ing would show.
 */
private fun blocked() =
    WebResourceResponse("image/gif", null, ByteArrayInputStream(TRANSPARENT_GIF))

private val TRANSPARENT_GIF =
    byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
        0x01, 0x00, 0x01, 0x00, // 1 × 1
        0x80.toByte(), 0x00, 0x00, // global colour table present, background 0, square pixels
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // the table: two entries, both black
        0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, // colour 0 is transparent
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // image descriptor
        0x02, 0x02, 0x44, 0x01, 0x00, // one pixel of LZW
        0x3B, // trailer
    )
