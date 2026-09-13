package com.jugaad.agent.fl

import com.jugaad.agent.core.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON-Lines-backed store for the samples this phone has captured for
 * federated training: `<dir>/samples.jsonl`, one JSON object per line.
 *
 * The constructor reads the file once into an in-memory index. [addPending]
 * appends a line (cheap, crash-safe). [label] changes a field on an existing
 * row, which a line-oriented format can't do in place, so it rewrites the
 * whole file. All access is synchronized since diagnosis, training and sync
 * can touch this from different coroutines.
 */
class SampleStore(dir: File) {

    private val file = File(dir, "samples.jsonl")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val index = LinkedHashMap<String, FlSample>()

    private val _revision = MutableStateFlow(0)

    /** Bumps on every [addPending]/[label] so observers (e.g. [AutoTrainer]) can react. */
    val revision: StateFlow<Int> = _revision

    @Serializable
    private data class Line(
        val id: String,
        val assetId: String,
        val x: FloatArray,
        val label: Int? = null,
        val source: String,
        val ts: Long,
        val score: Double? = null,
        val abs: FloatArray? = null,
        val absSensors: FloatArray? = null,
        val origin: String? = null,
        val machineTypeId: String? = null,
    )

    init {
        dir.mkdirs()
        if (file.exists()) {
            file.forEachLine { raw ->
                if (raw.isBlank()) return@forEachLine
                runCatching { json.decodeFromString<Line>(raw) }
                    .onSuccess { l -> index[l.id] = l.toSample() }
                    .onFailure { Logx.w("SampleStore: bad line skipped", it) }
            }
        }
    }

    fun addPending(id: String, assetId: String, x: FloatArray) = addPending(id, assetId, x, null)

    fun addPending(id: String, assetId: String, x: FloatArray, score: Double?) =
        addPending(id, assetId, x, score, null, null, null)

    fun addPending(
        id: String,
        assetId: String,
        x: FloatArray,
        score: Double?,
        abs: FloatArray?,
        absSensors: FloatArray?,
        machineTypeId: String?,
    ) {
        synchronized(lock) {
            if (index.containsKey(id)) return
            val sample = FlSample(
                id = id,
                assetId = assetId,
                x = x,
                label = null,
                source = SampleSource.PENDING,
                ts = System.currentTimeMillis(),
                score = score,
                abs = abs,
                absSensors = absSensors,
                origin = null,
                machineTypeId = machineTypeId,
            )
            index[id] = sample
            appendLine(sample)
            _revision.value++
        }
    }

    fun label(id: String, label: Int, source: SampleSource = SampleSource.HUMAN): Boolean {
        synchronized(lock) {
            val existing = index[id] ?: return false
            index[id] = existing.copy(label = label, source = source)
            rewriteFile()
            _revision.value++
            return true
        }
    }

    fun labelled(): List<FlSample> = synchronized(lock) { index.values.filter { it.label != null } }

    /** The 75% of [labelled] samples not held out for validation (see [isValidation]). */
    fun labelledTrain(): List<FlSample> = labelled().filterNot { isValidation(it.id) }

    /** The 25% of [labelled] samples held out for network-wide scoring (see [isValidation]). */
    fun labelledVal(): List<FlSample> = labelled().filter { isValidation(it.id) }

    fun counts(): IntArray = synchronized(lock) {
        val c = IntArray(FlConstants.N_CLASSES)
        for (s in index.values) {
            val l = s.label ?: continue
            if (l in c.indices) c[l]++
        }
        c
    }

    fun pendingCount(): Int = synchronized(lock) { index.values.count { it.label == null } }

    fun pending(): List<FlSample> = synchronized(lock) { index.values.filter { it.label == null } }

    /** All samples (any label state) for one asset. */
    fun forAsset(assetId: String): List<FlSample> = synchronized(lock) { index.values.filter { it.assetId == assetId } }

    /**
     * Removes every sample carrying [assetId] from the in-memory index and persists the
     * result (same rewrite-and-atomic-rename path as [label]). Called when a piece of
     * equipment is deleted so its training data does not linger and keep training the
     * fleet after the asset itself is gone. @return how many rows were removed; 0 (and
     * no write) for an unknown [assetId].
     */
    fun removeForAsset(assetId: String): Int {
        synchronized(lock) {
            val toRemove = index.values.filter { it.assetId == assetId }.map { it.id }
            if (toRemove.isEmpty()) return 0
            toRemove.forEach { index.remove(it) }
            rewriteFile()
            _revision.value++
            return toRemove.size
        }
    }

    /** Healthy-labelled (class 0) samples for one asset — the pool [RefreshBaselineUseCase] draws from. */
    fun healthyForAsset(assetId: String): List<FlSample> = forAsset(assetId).filter { it.label == 0 }

    private fun appendLine(sample: FlSample) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(sample.toLine()) + "\n")
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

    private fun FlSample.toLine() = Line(id, assetId, x, label, source.name, ts, score, abs, absSensors, origin, machineTypeId)

    /**
     * Legacy samples persisted before the gyro/magnetometer sensors joined the feature
     * vector (v2: 257-d) are padded with zero deltas on load so they remain usable by
     * the current 260-d heads. A missing sensor contributes 0 to both the reading and
     * the baseline, so its delta is 0 — the same semantics [FeatureDelta] documents.
     */
    private fun Line.toSample() = FlSample(
        id = id,
        assetId = assetId,
        x = x.normalizeToInputDim(id),
        label = label,
        source = runCatching { SampleSource.valueOf(source) }.getOrDefault(SampleSource.PENDING),
        ts = ts,
        score = score,
        abs = abs,
        absSensors = absSensors,
        origin = origin,
        machineTypeId = machineTypeId,
    )

    private fun FloatArray.normalizeToInputDim(id: String): FloatArray {
        val expected = FlConstants.INPUT_DIM
        if (size == expected) return this
        Logx.w("SampleStore: sample $id is ${size}-d, normalizing to $expected-d (missing sensors -> 0)")
        return copyOf(expected)
    }

    companion object {
        /** Deterministic 25% held-out split shared with the network protocol (Model spec v2). */
        fun isValidation(id: String): Boolean = (id.hashCode() and 0x7fffffff) % 4 == 0
    }
}
