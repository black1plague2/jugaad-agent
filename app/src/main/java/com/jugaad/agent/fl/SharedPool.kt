package com.jugaad.agent.fl

import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Dataset enrichment from every node (v4 plan §5): peer-contributed labelled samples
 * this phone has received over a sync, persisted at `fl/shared_samples.jsonl`. Every
 * entry carries the contributing node's [FlSample.origin], never `null` — a sample
 * without one isn't something this pool holds. Capacity comes from [AppConfig.Sharing]
 * read from [cfg] at call time: once [com.jugaad.agent.core.config.Sharing.maxPoolSamples]
 * is exceeded the oldest entries (by [FlSample.ts]) are evicted first, then
 * [com.jugaad.agent.core.config.Sharing.maxPerOrigin] is enforced the same way within
 * each origin.
 */
class SharedPool(dir: File, private val cfg: () -> AppConfig) {

    private val file = File(dir, "shared_samples.jsonl")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val index = LinkedHashMap<String, FlSample>()

    private val _revision = MutableStateFlow(0)

    /** Bumps whenever [addAll] actually adds something, so [FlRuntime.dataRevision] can react. */
    val revision: StateFlow<Int> = _revision

    @Serializable
    private data class Line(
        val id: String,
        val assetId: String,
        val x: FloatArray,
        val label: Int,
        val origin: String,
        val machineTypeId: String? = null,
        val score: Double? = null,
        val ts: Long,
    )

    init {
        dir.mkdirs()
        if (file.exists()) {
            file.forEachLine { raw ->
                if (raw.isBlank()) return@forEachLine
                runCatching { json.decodeFromString<Line>(raw) }
                    .onSuccess { l -> index[l.id] = l.toSample() }
                    .onFailure { Logx.w("SharedPool: bad line skipped", it) }
            }
        }
    }

    fun ids(): Set<String> = synchronized(lock) { index.keys.toSet() }

    fun all(): List<FlSample> = synchronized(lock) { index.values.toList() }

    fun count(): Int = synchronized(lock) { index.size }

    fun countByOrigin(): Map<String, Int> = synchronized(lock) {
        index.values.groupingBy { it.origin ?: "unknown" }.eachCount()
    }

    /**
     * Adds every sample in [samples] not already held (by id), then evicts down to the
     * configured caps. A sample with no [FlSample.origin] is skipped — this pool only
     * ever holds peer-attributed rows. @return how many were newly added.
     */
    fun addAll(samples: List<FlSample>): Int = synchronized(lock) {
        var added = 0
        for (s in samples) {
            if (s.origin == null) continue
            if (index.containsKey(s.id)) continue
            // Normalized regardless of what the caller passed — a pool sample is always PEER.
            index[s.id] = s.copy(source = SampleSource.PEER)
            added++
        }
        if (added > 0) {
            evict()
            rewriteFile()
            _revision.value++
        }
        added
    }

    /**
     * Removes every pool sample carrying [assetId] — these are either this phone's own
     * shared samples for that asset coming back over a sync, or otherwise colliding by
     * assetId, so once the asset is deleted locally they should stop training this node
     * too. Cross-phone retraction (telling peers to drop their copies) is out of scope
     * here; see the call site in ServiceLocator for that note. @return how many were removed.
     */
    fun removeForAsset(assetId: String): Int = synchronized(lock) {
        val toRemove = index.values.filter { it.assetId == assetId }.map { it.id }
        if (toRemove.isEmpty()) return 0
        toRemove.forEach { index.remove(it) }
        rewriteFile()
        _revision.value++
        toRemove.size
    }

    private fun evict() {
        val sharing = cfg().sharing

        if (index.size > sharing.maxPoolSamples) {
            val excess = index.size - sharing.maxPoolSamples
            index.values.sortedBy { it.ts }.take(excess).forEach { index.remove(it.id) }
        }

        val byOrigin = index.values.groupBy { it.origin ?: "unknown" }
        for (group in byOrigin.values) {
            if (group.size <= sharing.maxPerOrigin) continue
            val excess = group.size - sharing.maxPerOrigin
            group.sortedBy { it.ts }.take(excess).forEach { index.remove(it.id) }
        }
    }

    private fun rewriteFile() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.bufferedWriter().use { w ->
            for (s in index.values) {
                w.write(json.encodeToString(s.toLine()))
                w.newLine()
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun FlSample.toLine() = Line(
        id = id, assetId = assetId, x = x, label = label ?: 0, origin = origin ?: "unknown",
        machineTypeId = machineTypeId, score = score, ts = ts,
    )

    private fun Line.toSample() = FlSample(
        id = id, assetId = assetId, x = x, label = label, source = SampleSource.PEER,
        ts = ts, score = score, abs = null, absSensors = null, origin = origin, machineTypeId = machineTypeId,
    )
}
