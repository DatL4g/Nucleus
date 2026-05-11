package io.github.kdroidfilter.nucleus.updater.provider

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import java.net.http.HttpClient

interface UpdateProvider {
    fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String

    fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String

    fun authHeaders(): Map<String, String> = emptyMap()

    /**
     * Returns the URL of the metadata (YAML) file for the given [channel] and [platform],
     * resolving it dynamically when the provider needs to consult a remote service first.
     *
     * Called by [io.github.kdroidfilter.nucleus.updater.NucleusUpdater] before every update
     * check. The default implementation delegates to [getUpdateMetadataUrl], which is
     * sufficient for providers whose URLs can be computed without a network round-trip.
     *
     * Override this method when locating the metadata requires an HTTP request. For example,
     * [GitHubProvider] overrides it to query the GitHub Releases API and select the most
     * recent pre-release whose tag matches the requested channel — the stable channel keeps
     * the static `releases/latest/download/...` redirect, while `beta` and `alpha` channels
     * resolve to `releases/download/<tag>/...` URLs that the GitHub `latest` shortcut would
     * otherwise skip.
     *
     * The [httpClient] is the same client that
     * [io.github.kdroidfilter.nucleus.updater.UpdaterConfig.httpClient] configures (or the
     * default one if none was supplied), so overrides should reuse it instead of constructing
     * their own — this keeps redirect, proxy, and trust-store settings consistent across all
     * traffic the updater generates. Implementations may call [httpClient] synchronously;
     * the updater invokes this method from an IO dispatcher.
     *
     * Implementations may throw any exception to signal failure; [NoSuchElementException] is
     * the conventional choice for "no release matches this channel". The updater surfaces
     * such failures as [io.github.kdroidfilter.nucleus.updater.UpdateResult.Error].
     */
    fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String = getUpdateMetadataUrl(channel, platform)
}
