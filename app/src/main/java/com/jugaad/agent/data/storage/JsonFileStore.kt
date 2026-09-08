package com.jugaad.agent.data.storage

import com.jugaad.agent.core.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Minimal JSON-on-filesystem persistence. No Room, no DataStore — the hackathon
 * spec asks for "JSON files in filesDir, one folder per asset", and this keeps the
 * whole data layer inspectable with `adb pull`.
 *
 * Writes are atomic (temp file + rename) so a killed process can't leave a
 * half-written asset.json.
 */
class JsonFileStore(private val root: File) {

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    init {
        if (!root.exists()) root.mkdirs()
    }

    fun dir(vararg parts: String): File =
        File(root, parts.joinToString(File.separator)).also { it.mkdirs() }

    fun file(vararg parts: String): File =
        File(root, parts.joinToString(File.separator))

    suspend inline fun <reified T> readOrNull(file: File): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<T>(file.readText()) }
            .onFailure { Logx.w("readOrNull ${file.name} failed", it) }
            .getOrNull()
    }

    suspend inline fun <reified T> write(file: File, value: T) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(value))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun writeBytes(file: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) {
        file.deleteRecursively()
    }

    fun listDirs(parent: File): List<File> =
        parent.listFiles { f -> f.isDirectory }?.toList().orEmpty()

    fun listFiles(parent: File, ext: String): List<File> =
        parent.listFiles { f -> f.isFile && f.name.endsWith(".$ext") }?.toList().orEmpty()
}
