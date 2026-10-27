package dev.proprio.mobile

import dev.proprio.mobile.data.WatchLink
import dev.proprio.mobile.domain.LiveModel
import dev.proprio.protocol.LiveSubscribe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone side of the live sensor preview: feeds decoded watch batches into a [LiveModel] and holds the lease while
 * the live view is visible. Display-only: nothing here writes to the session store or the upload queue.
 *
 * Lease: [runVisible] is called from a lifecycle-aware effect and sends `live/subscribe` immediately and then every
 * [LiveSubscribe.RENEW_INTERVAL_MS]; when it is cancelled (view hidden, app backgrounded, screen left) it sends
 * `live/unsubscribe`. If that message is lost the watch's lease expires on its own within [LiveSubscribe.DEFAULT_LEASE_MS].
 */
class LivePreviewController(
    scope: CoroutineScope,
    private val link: WatchLink,
    private val nowMs: () -> Long = System::currentTimeMillis,
    collectOn: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    val model = LiveModel()

    init {
        scope.launch(collectOn) { link.liveBatches.collect { model.onBatch(it, nowMs()) } }
    }

    /** Suspends until cancelled, keeping the watch's lease alive. */
    suspend fun runVisible() {
        try {
            while (true) {
                link.liveSubscribe(LiveSubscribe.DEFAULT_LEASE_MS)
                delay(LiveSubscribe.RENEW_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable) { link.liveUnsubscribe() }
        }
    }

    fun now(): Long = nowMs()
}
