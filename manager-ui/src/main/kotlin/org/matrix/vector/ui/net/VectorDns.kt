package org.matrix.vector.ui.net

import android.util.Log
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

private const val TAG = "VectorDns"

/**
 * The DoH preference, read by the resolver and written by the settings UI.
 *
 * Each host (Vector's SettingsRepository, LSPatch's LSPSettings) implements this over its own
 * preference store, so the one resolver and the one status section work against either without
 * depending on a concrete settings class.
 */
interface NetworkSettings {
    /** Whether name lookups should try DNS over HTTPS before the system resolver. */
    val dohEnabled: StateFlow<Boolean>

    /** Turn DoH on or off. Takes effect on the next lookup — the client is never rebuilt. */
    fun setDohEnabled(enabled: Boolean)
}

/**
 * What the last name lookup of this session actually did.
 *
 * Deliberately a record of the real resolver rather than something a probe could produce. A "test
 * DoH" button would be a second code path — another host, another moment — and it can pass while
 * the client that fetches the module list is failing. Only the shared resolver knows the truth, so
 * only the shared resolver reports it.
 */
sealed interface DohStatus {
    /** Nothing has been resolved yet, so there is nothing to report. */
    data object Untested : DohStatus

    /** The setting is off; names went to the system resolver. */
    data object Disabled : DohStatus

    /** A proxy is configured, so resolving is its job and DoH was skipped. */
    data object Bypassed : DohStatus

    /** The last lookup went through the DoH endpoint. */
    data class Working(val host: String) : DohStatus

    /**
     * DoH failed and the session has fallen back to the system resolver.
     *
     * No hostname here on purpose. What failed is reaching the DoH endpoint; whichever name was
     * being looked up at that moment is incidental — it is simply whatever the app asked for first
     * — and naming it reads as though that host were the subject of a test. The log line keeps it
     * for anyone diagnosing; the sheet does not need it.
     */
    data class FellBack(val reason: String) : DohStatus
}

/**
 * Name resolution: DNS over HTTPS when it helps, the system resolver when it does not.
 *
 * DoH exists here for users whose network will not resolve the module repository or GitHub over
 * plain DNS. It is deliberately **best-effort** rather than all-or-nothing, because the networks
 * that make the setting worth having are also the ones that may block `cloudflare-dns.com` itself,
 * and a lookup path with no fallback would then take the module list, the activity feed and every
 * avatar down together — leaving the switch that caused it as the only way out. So:
 * - a failed DoH lookup falls through to the system resolver rather than failing the request;
 * - the first failure to *reach* DoH latches for the session, so the timeout is paid once and not
 *   per lookup; an answer about the name itself (no such host, no address) does not;
 * - the DoH client's own timeouts are short, so that one payment is a few seconds, not fifteen;
 * - a configured HTTP proxy disables DoH entirely, because the proxy is doing the resolving and
 *   bootstrap IPs are meaningless to it.
 *
 * Every one of those branches used to be invisible: off, bypassed and latched all looked like the
 * same working switch, and the fallback existed only as a log line. [status] is what each lookup
 * did, so the sheet that owns the switch can say which of them is happening.
 *
 * The setting and the proxy are both read **per lookup** rather than baked into the client at
 * construction. OkHttp cannot have its DNS swapped on a live client, and rebuilding the shared
 * client would drop the connection pool and orphan the disk cache, so reading them here is what
 * lets a switch — or joining a VPN — take effect before the next process start.
 */
class VectorDns
internal constructor(
    private val settings: NetworkSettings,
    private val system: Dns,
    newDoh: () -> Dns,
) : Dns {

    constructor(
        settings: NetworkSettings,
        bootstrapClient: OkHttpClient,
    ) : this(settings, Dns.SYSTEM, { dohOver(bootstrapClient) })

    private val _status = MutableStateFlow<DohStatus>(DohStatus.Untested)

    /** What the last lookup did. See [DohStatus] for why this is observed and never probed. */
    val status: StateFlow<DohStatus> = _status.asStateFlow()

    /**
     * Latched once the DoH endpoint proves unreachable.
     *
     * Volatile rather than synchronised: two threads racing to set it to true is harmless, and
     * lookups happen on every OkHttp dispatcher thread.
     */
    @Volatile private var dohUnavailable = false

    private val doh: Dns by lazy(newDoh)

    /**
     * True when nothing is proxying our traffic, which is the only case where DoH is ours to do.
     *
     * Asked on every lookup rather than cached. A proxy can appear mid-session — joining a VPN or a
     * work profile does exactly that — and a value read once at startup would keep sending queries
     * to Cloudflare long after the answer changed, while [status] claimed a state that was no
     * longer true. The call is local and a lookup is about to do network I/O anyway.
     */
    private fun direct(): Boolean =
        runCatching {
                ProxySelector.getDefault().select(endpoint.toUri()).firstOrNull() == Proxy.NO_PROXY
            }
            .getOrDefault(true)

    /**
     * Clears the session latch so the next lookup tries DoH again.
     *
     * The latch is what keeps a blocked endpoint from costing five seconds per name, but "the
     * session" here is `com.android.shell`, a process nobody can restart on purpose — so without
     * this a single bad lookup on a captive portal disables DoH until something else happens to
     * kill the host. This is the way back, and it is offered only once the fallback has happened.
     */
    fun retry() {
        dohUnavailable = false
        _status.value = DohStatus.Untested
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!settings.dohEnabled.value) {
            _status.value = DohStatus.Disabled
        } else if (!direct()) {
            _status.value = DohStatus.Bypassed
        } else if (!dohUnavailable) {
            try {
                val resolved = doh.lookup(hostname)
                _status.value = DohStatus.Working(hostname)
                return resolved
            } catch (e: InterruptedException) {
                // The calling thread was interrupted while it waited for the answer: nothing is
                // known about DoH. The flag is put back for whoever interrupted it.
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // Exception and not Throwable: an OutOfMemoryError is not a DNS outcome. There is
                // no CancellationException to preserve either — this is a plain blocking call on an
                // OkHttp dispatcher thread, with no coroutine in the stack.
                if (e.isAnswerAboutTheName()) {
                    // The endpoint was reached and answered. That answer is no reason to stop
                    // asking it about every other name; this one name goes to the system resolver.
                    return system.lookup(hostname)
                }
                dohUnavailable = true
                _status.value = DohStatus.FellBack(e.describe(hostname))
                Log.w(
                    TAG,
                    "dns: DoH lookup of $hostname failed, using the system resolver for this session",
                    e,
                )
            }
        }
        // Latched: the status already says why, and repeating it on every name would only replace
        // the host that actually failed with whichever one asked next.
        return system.lookup(hostname)
    }

    /**
     * Whether DoH reached its endpoint and the failure is the answer about this one name.
     *
     * OkHttp's DoH reports every DNS answer without an address as [UnknownHostException]: no such
     * name (NXDOMAIN), a server failure, an empty answer, and a private name it declines to send.
     * Failing to reach the endpoint arrives as another [java.io.IOException] (connect, timeout,
     * TLS, an HTTP error status, a body that is not a DNS message), and a public suffix list that
     * will not load as an [IllegalStateException]. The A and AAAA queries fail separately and the
     * second failure is attached as suppressed, so both have to be answers. The DoH client follows
     * no redirects, which closes the one transport path that could raise [UnknownHostException]:
     * the bootstrap resolver refusing a redirect target.
     */
    private fun Exception.isAnswerAboutTheName(): Boolean =
        this is UnknownHostException && suppressed.all { it is UnknownHostException }

    /**
     * A line short enough to sit under a switch, and worth the room it takes.
     *
     * The class name whenever the message would not add anything. `UnknownHostException` carries
     * the hostname as its entire message, so using it verbatim printed the name twice — "could not
     * resolve example.org (example.org)" — while the one thing a reader wants, *which way* it
     * failed, went missing. The class name is jargon, but it is jargon that distinguishes a blocked
     * endpoint from a timeout, and it is what a bug report needs to carry anyway.
     */
    private fun Throwable.describe(host: String): String =
        message?.takeIf { it.isNotBlank() && !it.equals(host, ignoreCase = true) }
            ?: javaClass.simpleName

    private companion object {
        val endpoint = "https://cloudflare-dns.com/dns-query".toHttpUrl()

        fun dohOver(bootstrapClient: OkHttpClient): Dns =
            DnsOverHttps.Builder()
                .client(
                    bootstrapClient
                        .newBuilder()
                        // Fail fast. The default connect timeout is long enough that a blocked
                        // endpoint reads as a hung app rather than as a fallback about to happen.
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .callTimeout(5, TimeUnit.SECONDS)
                        // A DoH endpoint answers in place. A redirect would send the query to a
                        // host the bootstrap resolver refuses with UnknownHostException, which
                        // would read as an answer about the name; unfollowed, it is an HTTP error.
                        .followRedirects(false)
                        .build()
                )
                .url(endpoint)
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                    InetAddress.getByName("2606:4700:4700::1111"),
                    InetAddress.getByName("2606:4700:4700::1001"),
                )
                .includeIPv6(true)
                .build()
    }
}
