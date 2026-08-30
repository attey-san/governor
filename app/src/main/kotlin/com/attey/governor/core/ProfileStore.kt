package com.attey.governor.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Profiles and triggers on disk, as JSON in the app's own files directory.
 *
 * No database and no serialization library: two flat lists that a person can
 * read with `cat` when something goes wrong on a phone they cannot debug.
 */
class ProfileStore(context: Context) {

    private val profileFile = File(context.filesDir, "profiles.json")
    private val triggerFile = File(context.filesDir, "triggers.json")

    /**
     * Which profile the quick-settings tile last applied.
     *
     * A plain file rather than SharedPreferences: the tile service and the app run
     * in the same process here, but a one-line file is readable with `cat` on a
     * phone that is misbehaving, and the rest of this app's state already is.
     */
    private val activeFile = File(context.filesDir, "active-profile")

    fun loadProfiles(): List<Profile> = read(profileFile) { toProfile(it) }

    fun saveProfiles(profiles: List<Profile>) =
        write(profileFile, profiles.map { it.toJson() })

    fun loadTriggers(): List<Trigger> = read(triggerFile) { toTrigger(it) }

    fun loadActiveProfile(): String? =
        runCatching { activeFile.readText().trim().ifEmpty { null } }.getOrNull()

    fun saveActiveProfile(name: String) {
        runCatching { activeFile.writeText(name) }
    }

    fun saveTriggers(triggers: List<Trigger>) =
        write(triggerFile, triggers.map { it.toJson() })

    private fun <T> read(file: File, parse: (JSONObject) -> T?): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { parse(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /**
     * Written to a sibling and renamed over the target.
     *
     * `writeText` truncates before it writes, so a process death in that window
     * leaves an empty file -- and [read] treats an unparseable file as an empty
     * list, which means every saved profile disappears without a word. A rename
     * inside one directory is atomic, so the file is either the old one or the
     * new one.
     */
    private fun write(file: File, objects: List<JSONObject>) {
        runCatching {
            val array = JSONArray()
            objects.forEach { array.put(it) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(array.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    // --- Mapping

    private fun Profile.toJson() = JSONObject().apply {
        put("name", name)
        put("policyMin", JSONObject(policyMin.mapKeys { it.key.toString() }))
        put("policyMax", JSONObject(policyMax.mapKeys { it.key.toString() }))
        put("governors", JSONObject(governors.mapKeys { it.key.toString() }))
        gpuMin?.let { put("gpuMin", it) }
        gpuMax?.let { put("gpuMax", it) }
        gpuGovernor?.let { put("gpuGovernor", it) }
        put("tunables", JSONObject(tunables))
        put("vm", JSONObject(vm))
        put("io", JSONObject(io))
    }

    private fun toProfile(o: JSONObject): Profile? {
        val name = o.optString("name").ifEmpty { return null }
        return Profile(
            name = name,
            policyMin = o.optJSONObject("policyMin").toLongMap(),
            policyMax = o.optJSONObject("policyMax").toLongMap(),
            governors = o.optJSONObject("governors").toStringMap()
                .mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it >= 0 },
            gpuMin = if (o.has("gpuMin")) o.optLong("gpuMin") else null,
            gpuMax = if (o.has("gpuMax")) o.optLong("gpuMax") else null,
            gpuGovernor = if (o.has("gpuGovernor")) o.optString("gpuGovernor") else null,
            tunables = o.optJSONObject("tunables").toStringMap(),
            vm = o.optJSONObject("vm").toStringMap(),
            io = o.optJSONObject("io").toStringMap(),
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
        // An unknown type means a profile written by a newer build. Drop the row
        // rather than crashing the whole list on one bad entry.
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
        return keys().asSequence().associateWith { optString(it) }
    }

    private fun JSONObject?.toLongMap(): Map<Int, Long> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { k ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            id to optLong(k)
        }.toMap()
    }
}
