package com.memfault.bort.metrics

import android.content.Context
import android.os.Environment
import com.memfault.bort.reporting.NumericAgg
import com.memfault.bort.reporting.Reporting
import com.memfault.bort.shared.Logger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects internal storage usage stats, and records them as metrics.
 */
class StorageStatsCollector @Inject constructor(
    private val context: Context,
) {
    private val freeBytesMetric =
        Reporting.report().distribution("storage.data.bytes_free", listOf(NumericAgg.LATEST_VALUE))
    private val totalBytesMetric =
        Reporting.report().distribution("storage.data.bytes_total", listOf(NumericAgg.LATEST_VALUE))
    private val usedBytesMetric =
        Reporting.report().distribution("storage.data.bytes_used", listOf(NumericAgg.LATEST_VALUE))
    private val percentageUsedMetric =
        Reporting.report().distribution("storage.data.percentage_used", listOf(NumericAgg.LATEST_VALUE))

    suspend fun collectStorageStats() = withContext(Dispatchers.IO) {
        Logger.v("collectStorageStats")
        val freeBytes = Environment.getDataDirectory().freeSpace
        val totalBytes = Environment.getDataDirectory().totalSpace
        val usedBytes = totalBytes - freeBytes
        val percentageUsed = usedBytes.toDouble() / totalBytes.toDouble()
        Logger.v(
            "collectStorageStats: freeBytes=$freeBytes / totalBytes=$totalBytes / " +
                "usedBytes=$usedBytes / percentageUsed=$percentageUsed"
        )
        freeBytesMetric.record(freeBytes)
        totalBytesMetric.record(totalBytes)
        usedBytesMetric.record(usedBytes)
        percentageUsedMetric.record(percentageUsed)
    }
}
