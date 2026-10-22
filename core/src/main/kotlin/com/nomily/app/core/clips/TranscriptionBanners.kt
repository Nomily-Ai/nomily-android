package com.nomily.app.core.clips

/**
 * The "transcript ready" toasts, one at a time.
 *
 * A batch (card transfer, import) finishes several clips in a row; a single "latest" slot would show one toast for
 * five clips, so completions queue here and present in order. Titles are resolved by the caller at enqueue time.
 * Toasts that waited longer than [staleAfterMs] (finished while the app was in the background) are dropped rather
 * than popping up long after the fact.
 */
class TranscriptionBanners(private val staleAfterMs: Long = 10_000) {

    data class Banner(val id: Long, val clip: String, val title: String, val createdAt: Long)

    /** The toast on screen, `null` = none. */
    var current: Banner? = null
        private set

    private val queue = ArrayDeque<Banner>()
    private var nextId = 0L

    fun enqueue(clip: String, title: String, now: Long) {
        queue.addLast(Banner(nextId++, clip, title, now))
        presentNext()
    }

    /** Tapped or timed out: show the next one, if any. */
    fun dismissCurrent() {
        current = null
        presentNext()
    }

    /** Drop anything older than the window, including the toast on screen. Called when the app comes to the front. */
    fun dropStale(now: Long) {
        val cutoff = now - staleAfterMs
        if ((current?.createdAt ?: Long.MAX_VALUE) < cutoff) current = null
        queue.removeAll { it.createdAt < cutoff }
        presentNext()
    }

    private fun presentNext() {
        if (current == null) current = queue.removeFirstOrNull()
    }
}
