package com.attey.governor.core

import kotlinx.coroutines.sync.withLock

/**
 * Turns a [Profile] into writes, and the current device state back into a Profile.
 */
object ProfileEngine {

    /**
     * Applies [profile], returning the settings the kernel declined.
     *
     * Ordering matters twice. Frequency windows are widened before they are
     * narrowed, because the kernel silently clamps a min written above the
     * current max. And the governor is set *before* its tunables, since the
     * tunable directory only exists while that governor is loaded -- writing
     * them in the other order puts the values into the previous governor's
     * directory, where they sit looking correct and doing nothing.
     */
    suspend fun apply(shell: RootShell, model: DeviceModel, profile: Profile): List<String> =
        kernelWriteMutex.withLock {
            val rejections = LinkedHashMap<String, String>()

            fun put(path: String, value: String) {
                when (val r = Writer.write(shell, path, value)) {
                    WriteResult.Ok -> rejections.remove(path)
                    is WriteResult.Refused ->
                        rejections[path] = "${path.substringAfterLast('/')}: ${r.reason}"
                    is WriteResult.Rejected ->
                        rejections[path] =
                            "${path.substringAfterLast('/')}: wanted ${r.wanted}, kept ${r.actual}"
                }
            }

            for (policy in model.policies) {
                profile.governors[policy.id]?.let { put(policy.govNode, it) }
                frequencyWindowWrites(
                    policy.minNode,
                    policy.maxNode,
                    profile.policyMin[policy.id],
                    profile.policyMax[policy.id],
                ).forEach { (path, value) -> put(path, value) }
            }

            model.gpus.firstOrNull()?.let { gpu ->
                profile.gpuGovernor?.let { put("${gpu.path}/governor", it) }
                frequencyWindowWrites(
                    "${gpu.path}/min_freq",
                    "${gpu.path}/max_freq",
                    profile.gpuMin,
                    profile.gpuMax,
                ).forEach { (path, value) -> put(path, value) }
            }

            profile.tunables.forEach { (path, value) -> put(path, value) }
            rejections.values.toList()
        }

    /**
     * The current state as a profile.
     *
     * Only captures what a profile is allowed to restore. vm and I/O are left out:
     * snapshotting every visible knob would make every profile a whole-system
     * image and stamp over settings the user never chose to include.
     */
    fun snapshot(model: DeviceModel, name: String) = Profile(
        name = name,
        policyMin = model.policies.associate { it.id to it.scalingMin },
        policyMax = model.policies.associate { it.id to it.scalingMax },
        governors = model.policies.associate { it.id to it.governor },
        gpuMin = model.gpus.firstOrNull()?.minFreq,
        gpuMax = model.gpus.firstOrNull()?.maxFreq,
        gpuGovernor = model.gpus.firstOrNull()?.governor,
        tunables = (
            model.policies.flatMap { p -> p.governorTunables.values } + model.boost.values
        ).filter { it.isUsable }.associate { it.path to it.value },
    )

    /**
     * The shell lines this profile becomes, for a boot script. Same ordering as
     * [apply], and every write is guarded by the node existing, because a module
     * runs on a phone that may have been reflashed since.
     */
    fun toShellScript(model: DeviceModel, profile: Profile): String = buildString {
        val writes = mutableListOf<Pair<String, String>>()
        for (policy in model.policies) {
            profile.governors[policy.id]?.let { writes += policy.govNode to it }
            writes += frequencyWindowWrites(
                policy.minNode,
                policy.maxNode,
                profile.policyMin[policy.id],
                profile.policyMax[policy.id],
            )
        }
        model.gpus.firstOrNull()?.let { gpu ->
            profile.gpuGovernor?.let { writes += "${gpu.path}/governor" to it }
            writes += frequencyWindowWrites(
                "${gpu.path}/min_freq",
                "${gpu.path}/max_freq",
                profile.gpuMin,
                profile.gpuMax,
            )
        }
        profile.tunables.forEach { (p, v) -> writes += p to v }
        // This script runs as uid 0 at every boot. Treat a profile loaded from
        // disk as untrusted and use the same allowlist and quoting as live writes.
        for ((path, value) in writes) appendLine(
            requireNotNull(Writer.shellWriteLine(path, value)) {
                "unsafe profile setting: $path"
            }
        )
    }

    /**
     * A max/min/max sequence reaches any valid window regardless of the current
     * one. The first max widens an upper bound when needed; if it is below the
     * current minimum it may be clamped, then succeeds after the minimum moves.
     */
    internal fun frequencyWindowWrites(
        minPath: String,
        maxPath: String,
        min: Long?,
        max: Long?,
    ): List<Pair<String, String>> = when {
        min != null && max != null -> listOf(
            maxPath to max.toString(),
            minPath to min.toString(),
            maxPath to max.toString(),
        )
        min != null -> listOf(minPath to min.toString())
        max != null -> listOf(maxPath to max.toString())
        else -> emptyList()
    }

    /** Orders saved bounds so a rollback works from either side of the old window. */
    internal fun restorationWrites(restore: Map<String, String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val handled = HashSet<String>()
        for ((path, value) in restore) {
            if (!handled.add(path)) continue
            val pair = when {
                path.endsWith("/scaling_min_freq") ->
                    path.removeSuffix("/scaling_min_freq") + "/scaling_max_freq"
                path.endsWith("/scaling_max_freq") ->
                    path.removeSuffix("/scaling_max_freq") + "/scaling_min_freq"
                path.endsWith("/min_freq") -> path.removeSuffix("/min_freq") + "/max_freq"
                path.endsWith("/max_freq") -> path.removeSuffix("/max_freq") + "/min_freq"
                else -> null
            }
            if (pair == null || pair !in restore) {
                out += path to value
                continue
            }
            handled += pair
            val pathIsMin = path.endsWith("/scaling_min_freq") || path.endsWith("/min_freq")
            val minPath = if (pathIsMin) path else pair
            val maxPath = if (pathIsMin) pair else path
            out += listOf(
                maxPath to restore.getValue(maxPath),
                minPath to restore.getValue(minPath),
                maxPath to restore.getValue(maxPath),
            )
        }
        return out
    }
}
