package com.techwin.statushd

data class BatchPhotoSummary(
    val total: Int,
    val waiting: Int,
    val processing: Int,
    val done: Int,
    val failed: Int,
    val skipped: Int,
    val handled: Int,
    val isTerminal: Boolean
) {
    companion object {
        fun fromItems(items: List<BatchPhotoItem>): BatchPhotoSummary {
            val total = items.size
            val waiting = items.count { it.state == ItemState.Waiting }
            val processing = items.count { it.state == ItemState.Processing }
            val done = items.count { it.state == ItemState.Done }
            val failed = items.count { it.state == ItemState.Failed }
            val skipped = items.count { it.state == ItemState.Skipped }
            val handled = done + failed + skipped
            val isTerminal = waiting == 0 && processing == 0

            return BatchPhotoSummary(
                total = total,
                waiting = waiting,
                processing = processing,
                done = done,
                failed = failed,
                skipped = skipped,
                handled = handled,
                isTerminal = isTerminal
            )
        }
    }
}
