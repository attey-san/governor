package com.attey.governor.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class AppOverrideState(
    val packageName: String,
    val restore: Profile,
    val applied: Boolean,
    val previousActiveProfile: String?,
)

class ProfileStore(context: Context) {

    private val profileFile = File(context.filesDir, "profiles.json")
    private val triggerFile = File(context.filesDir, "triggers.json")
    private val appOverrideFile = File(context.filesDir, "app-override.json")

    private val activeFile = File(context.filesDir, "active-profile")

    fun loadProfiles(): List<Profile> = read(profileFile) { toProfile(it) }

    fun saveProfiles(profiles: List<Profile>): Boolean =
        write(profileFile, profiles.map { it.toJson() })

    fun loadTriggers(): List<Trigger> = read(triggerFile) { toTrigger(it) }

    fun loadActiveProfile(): String? =
        readableFile(activeFile)?.let { source ->
            runCatching { source.readText().trim().ifEmpty { null } }.getOrNull()
        }

    fun saveActiveProfile(name: String): Boolean = writeTextAtomically(activeFile, name)

    fun clearActiveProfile(): Boolean = clearAtomicallyWrittenFile(activeFile)

    internal fun loadAppOverride(): AppOverrideState? {
        val source = readableFile(appOverrideFile) ?: return null
        return runCatching {
            val root = JSONObject(source.readText())
            val packageName = root.optString("packageName").ifEmpty {
                return@runCatching null
            }
            val profile = root.optJSONObject("restore")?.let(::toProfile)
                ?: return@runCatching null
            AppOverrideState(
                packageName = packageName,
                restore = profile,
                applied = root.optBoolean("applied", true),
                previousActiveProfile = root.stringOrNull("previousActiveProfile"),
            )
        }.getOrNull()
    }

    internal fun saveAppOverride(
        packageName: String,
        restore: Profile,
        applied: Boolean,
        previousActiveProfile: String?,
    ): Boolean {
        val root = JSONObject().apply {
            put("packageName", packageName)
            put("restore", restore.toJson())
            put("applied", applied)
            previousActiveProfile?.let { put("previousActiveProfile", it) }
        }
        return writeTextAtomically(appOverrideFile, root.toString(2))
    }

    internal fun clearAppOverride(): Boolean = clearAtomicallyWrittenFile(appOverrideFile)

    fun saveTriggers(triggers: List<Trigger>): Boolean =
        write(triggerFile, triggers.map { it.toJson() })

    private fun <T> read(file: File, parse: (JSONObject) -> T?): List<T> {
        val source = readableFile(file) ?: return emptyList()
        return runCatching {
            val array = JSONArray(source.readText())
            (0 until array.length()).mapNotNull { parse(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    // State files are replaced by rename so a process death cannot leave a
    // truncated JSON document.
    private fun write(file: File, objects: List<JSONObject>): Boolean {
        val array = JSONArray()
        objects.forEach { array.put(it) }
        return writeTextAtomically(file, array.toString(2))
    }

    private fun writeTextAtomically(file: File, text: String): Boolean =
        synchronized(FILE_WRITE_LOCK) {
            runCatching {
                val tmp = pendingFile(file)
                tmp.writeText(text)
                check(tmp.renameTo(file)) { "could not replace ${file.name}" }
            }.isSuccess
        }

    // Recover a complete temp file left before rename.
    private fun readableFile(file: File): File? = synchronized(FILE_WRITE_LOCK) {
        val tmp = pendingFile(file)
        if (!file.exists() && tmp.exists()) runCatching { tmp.renameTo(file) }
        file.takeIf { it.exists() } ?: tmp.takeIf { it.exists() }
    }

    private fun clearAtomicallyWrittenFile(file: File): Boolean = synchronized(FILE_WRITE_LOCK) {
        val tmp = pendingFile(file)
        if (tmp.exists() && !tmp.delete()) return@synchronized false
        !file.exists() || file.delete()
    }

    private fun pendingFile(file: File) = File(file.parentFile, "${file.name}.tmp")

    // JSON mapping

    private fun Profile.toJson() = JSONObject().apply {
        put("name", name)
        put("policyMin", JSONObject(policyMin.mapKeys { it.key.toString() }))
        put("policyMax", JSONObject(policyMax.mapKeys { it.key.toString() }))
        put("governors", JSONObject(governors.mapKeys { it.key.toString() }))
        gpuMin?.let { put("gpuMin", it) }
        gpuMax?.let { put("gpuMax", it) }
        gpuGovernor?.let { put("gpuGovernor", it) }
        put("tunables", JSONObject(tunables))
    }

    private fun toProfile(o: JSONObject): Profile? {
        val name = o.optString("name").ifEmpty { return null }
        return Profile(
            name = name,
            policyMin = o.optJSONObject("policyMin").toLongMap(),
            policyMax = o.optJSONObject("policyMax").toLongMap(),
            governors = o.optJSONObject("governors").toStringMap()
                .mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it >= 0 },
            gpuMin = o.longOrNull("gpuMin"),
            gpuMax = o.longOrNull("gpuMax"),
            gpuGovernor = o.stringOrNull("gpuGovernor"),
            tunables = o.optJSONObject("tunables").toStringMap(),
        )
    }

    private fun Trigger.toJson() = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("threshold", threshold)
        put("profileName", profileName)
        put("enabled", enabled)
        put("packageName", packageName)
        put("appLabel", appLabel)
    }

    private fun toTrigger(o: JSONObject): Trigger? {
        // Ignore trigger types written by newer versions.
        val type = runCatching { TriggerType.valueOf(o.optString("type")) }.getOrNull()
            ?: return null
        return Trigger(
            id = o.optLong("id"),
            type = type,
            threshold = o.optInt("threshold"),
            profileName = o.optString("profileName"),
            enabled = o.optBoolean("enabled", true),
            packageName = o.optString("packageName"),
            appLabel = o.optString("appLabel"),
        )
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { key ->
            val value = opt(key).takeUnless { it == null || it == JSONObject.NULL }
                ?: return@mapNotNull null
            key to value.toString()
        }.toMap()
    }

    private fun JSONObject?.toLongMap(): Map<Int, Long> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { k ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            if (id < 0) return@mapNotNull null
            val value = opt(k).takeUnless { it == null || it == JSONObject.NULL }
                ?.toString()?.toLongOrNull() ?: return@mapNotNull null
            id to value
        }.toMap()
    }

    private fun JSONObject.longOrNull(key: String): Long? =
        opt(key).takeUnless { it == null || it == JSONObject.NULL }
            ?.toString()?.toLongOrNull()

    private fun JSONObject.stringOrNull(key: String): String? =
        opt(key).takeUnless { it == null || it == JSONObject.NULL }?.toString()

    private companion object {
        val FILE_WRITE_LOCK = Any()
    }
}
