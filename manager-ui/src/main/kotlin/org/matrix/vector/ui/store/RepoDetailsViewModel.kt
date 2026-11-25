package org.matrix.vector.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the second, richer fetch is going. The page is readable in all three states. */
enum class DetailFetch {
    Loading,
    Loaded,
    Unavailable,
}

/**
 * Everything the detail page shows.
 *
 * [module] is never null once the catalogue is in memory, because the list entry seeds it. That is
 * the whole design of this screen: the catalogue already carries the description, the summary, the
 * scope, the collaborators and the newest release *with its APK*, so the page can paint — and be
 * installed from — before any request is made, and a failed request costs the README rather than
 * the page. The exception is an entry the site has corrected since the mirror's snapshot: it names
 * its version and carries no release, so that page installs once its record has arrived.
 */
data class RepoDetailsState(
    val module: OnlineModule? = null,
    val releases: List<Release> = emptyList(),
    val installed: RepoVersion? = null,
    val latest: RepoVersion? = null,
    val fetch: DetailFetch = DetailFetch.Loading,
    val channel: StoreChannel = StoreChannel.Stable,
    /** What the Store last installed for this module, if the Store is what installed it. */
    val storeInstall: StoreInstall? = null,
) {
    /**
     * The release the install bar installs: the newest one in this channel that carries an APK.
     *
     * Not necessarily [latest]. A release published without an APK is still the newest, and the
     * bar can only install the one before it.
     */
    val installable: Release?
        get() = releases.firstOrNull { it.apks.isNotEmpty() }

    /** The version the bar's install would bring, which is what its label has to name. */
    val offered: RepoVersion?
        get() = installable?.version

    /**
     * As `StoreEntry.upgradable`, minus the mute, and judged on [offered]: this page is a module the
     * reader went looking for, and the bar's button installs [installable], not whatever [latest]
     * names.
     *
     * The note is honoured here as well, and has to be — see [StoreInstall].
     */
    val upgradable: Boolean
        get() {
            val offered = offered ?: return false
            val installed = installed ?: return false
            return storeInstall?.satisfies(offered, installed) != true &&
                offered.upgradableOver(installed.versionCode, installed.versionName)
        }

    /** As `StoreEntry.sameVersion`, for [offered]: what the bar may call the offer. */
    val sameVersion: Boolean
        get() = offered?.sameVersionAs(installed) == true
}

/**
 * One module's detail page, shared between apps.
 *
 * The install capability is the one thing that differs, so it is injected as [host] and may be null:
 * an app that cannot install from the store (LSPatch) passes null and the page collapses to
 * browse-and-open. Everything else — the catalogue seed, the detail fetch, the channel, the mute —
 * comes from the app-agnostic [dataSource] and [settings].
 */
class RepoDetailsViewModel(
    private val packageName: String,
    private val dataSource: StoreDataSource,
    private val settings: StoreSettings,
    private val host: StoreInstallHost?,
    /** Installs outlive this screen; see [install]. */
    private val backgroundScope: CoroutineScope,
) : ViewModel() {

    /**
     * What the installed copy of this module says it hooks, read from its own APK by the host.
     *
     * The catalogue's `scope` is optional metadata and most authors omit it, so the information
     * panel says "not declared" for the majority of modules. For a module that is *installed*,
     * though, the authoritative list is in its APK, and the host reads it. Empty — and legacy false
     * — when there is no host, or nothing installed to inspect.
     */
    val installedScope: StateFlow<List<String>> =
        host?.installedScope ?: MutableStateFlow<List<String>>(emptyList()).asStateFlow()

    /**
     * Whether the copy on this device is a legacy module, which decides how to read the
     * *catalogue's* scope. Host-supplied; false when absent, which is the honest answer rather than
     * a safe one.
     */
    val installedIsLegacy: StateFlow<Boolean> =
        host?.installedIsLegacy ?: MutableStateFlow(false).asStateFlow()

    private val _detail = MutableStateFlow<OnlineModule?>(null)
    private val _fetch = MutableStateFlow(DetailFetch.Loading)

    val installState: StateFlow<InstallStep> =
        host?.installState ?: MutableStateFlow<InstallStep>(InstallStep.Idle).asStateFlow()

    /** Whether this module has been told to stop reporting updates. */
    val updatesMuted: StateFlow<Boolean> =
        settings.mutedUpdates
            .map { packageName in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUpdatesMuted(muted: Boolean) = settings.setUpdatesMuted(packageName, muted)

    /**
     * The two preferences this page reads, as one value.
     *
     * Paired rather than passed separately because `combine` takes five flows and this page already
     * watches five things of its own.
     */
    private data class Preferences(val channel: StoreChannel, val storeInstall: StoreInstall?)

    private fun preferences(): Flow<Preferences> =
        combine(settings.updateChannel, settings.storeInstalls) { channelPreference, installs ->
            Preferences(StoreChannel.of(channelPreference), installs[packageName])
        }

    val state: StateFlow<RepoDetailsState> =
        combine(
                dataSource.catalog,
                _detail,
                _fetch,
                dataSource.installedVersions,
                preferences(),
            ) { catalog, detail, fetch, installed, preferences ->
                val seed = catalog.modules.firstOrNull { it.name == packageName }
                val module = pageModule(seed, detail)
                val channel = preferences.channel
                RepoDetailsState(
                    module = module,
                    releases = releasesFor(module, channel),
                    installed = installed[packageName],
                    latest = latestFor(module, channel),
                    // A record set aside for trailing the entry (it came from the mirror or the
                    // cache, the site did not answer) is reported as the partial fetch it is, so
                    // the page says it shows what the list knew rather than that nothing exists.
                    fetch =
                        if (detail != null && module !== detail && fetch == DetailFetch.Loaded)
                            DetailFetch.Unavailable
                        else fetch,
                    channel = channel,
                    storeInstall = preferences.storeInstall,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoDetailsState())

    init {
        fetchDetails()
    }

    fun fetchDetails() {
        viewModelScope.launch {
            _fetch.value = DetailFetch.Loading
            val fetched = dataSource.details(packageName)
            // A failure is not an error screen. The seeded entry is still on display; all that is
            // missing is the README and the older releases, and the page says so quietly.
            _detail.value = fetched ?: _detail.value
            _fetch.value = if (fetched != null) DetailFetch.Loaded else DetailFetch.Unavailable
        }
    }

    /**
     * Downloads and installs [asset], through the host.
     *
     * A no-op without a host: the screen never offers install UI in that case. The host runs the
     * transfer on its own scope so navigating back does not cancel a consented install;
     * [backgroundScope] is kept for the same guarantee should the host ever hand the work back.
     */
    fun install(asset: ReleaseAsset, release: RepoVersion?) {
        host?.install(asset, release)
    }

    fun acknowledgeInstall() {
        host?.acknowledge()
    }

    /**
     * Which releases belong to the current channel.
     *
     * Resolved by [releasesOn] rather than here, so that what this tab lists, what the update badge
     * in the Store list compares against, and what the install bar downloads are one rule with one
     * implementation rather than three that can disagree on the prerelease channel.
     */
    private fun releasesFor(module: OnlineModule?, channel: StoreChannel): List<Release> =
        module?.releasesOn(channel).orEmpty()

    private fun latestFor(module: OnlineModule?, channel: StoreChannel): RepoVersion? =
        module?.latestOn(channel)
}

/**
 * The record this page shows: the fetched detail when it describes the releases the catalogue entry
 * names, otherwise the entry itself.
 *
 * The detail can come from the mirror or the cache, which trail the site; the Store list may have
 * corrected the entry to a newer release since. Shown anyway, the page offered the older APK while
 * the list offered the newer one, and the row kept asking. The rule is the one
 * StoreRepository.complete applies before laying a record over an entry: the same latestRelease and
 * the same latestBetaReleaseTime. A record without a summary of its own is compared through its
 * newest published release, the stand-in [latestOn] uses, and a beta time only when both name one;
 * an entry that names no release cannot be contradicted. Without an entry the detail is all there
 * is.
 */
internal fun pageModule(seed: OnlineModule?, detail: OnlineModule?): OnlineModule? {
    if (detail == null) return seed
    if (seed?.latestRelease == null) return detail
    val detailLatest =
        detail.latestRelease
            ?: detail.releases.orEmpty()
                .firstOrNull { it.isDraft != true && it.isPrerelease != true }
                ?.tagName
    if (detailLatest != seed.latestRelease) return seed
    val betaTimes = listOf(detail.latestBetaReleaseTime, seed.latestBetaReleaseTime)
    if (betaTimes.all { it != null } && betaTimes[0] != betaTimes[1]) return seed
    return detail
}
