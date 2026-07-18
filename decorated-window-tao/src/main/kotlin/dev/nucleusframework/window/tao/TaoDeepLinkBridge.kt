package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.net.URI
import java.net.URISyntaxException

/**
 * Receives macOS deep-link URLs from the native Tao runtime and routes them to
 * a Kotlin sink registered by `nucleus-application`'s `TaoLauncher`.
 *
 * URLs arrive via Tao's `application:openURLs:` delegate (re-emitted as the
 * `Event::Opened` event the native loop forwards to [NativeTaoBridge.dispatchDeepLink]).
 * The user's `onDeepLink { … }` block is registered later, from inside the
 * application scope. Any URI received before the sink is wired is buffered
 * (one slot) and replayed.
 */
object TaoDeepLinkBridge {
    @Volatile
    private var sink: ((URI) -> Unit)? = null

    @Volatile
    private var pending: URI? = null

    /**
     * Registers [onUri] as the deep-link sink and replays any URI that
     * arrived before the sink was wired.
     */
    fun setSink(onUri: (URI) -> Unit) {
        sink = onUri
        pending?.let { uri ->
            pending = null
            onUri(uri)
        }
    }

    internal fun onUrlFromNative(raw: String) {
        val uri =
            try {
                URI(raw)
            } catch (_: URISyntaxException) {
                return
            }
        val current = sink
        if (current != null) {
            current(uri)
        } else {
            pending = uri
        }
    }
}
