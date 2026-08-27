package com.attey.governor.core

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
    fun apply(shell: RootShell, model: DeviceModel, profile: Profile): List<String> {
        val rejections = mutableListOf<String>()

        fun put(path: String, value: String) {
            when (val r = Writer.write(shell, path, value)) {
                WriteResult.Ok -> Unit
                is WriteResult.Refused -> rejections += "${path.substringAfterLast('/')}: ${r.reason}"
                is WriteResult.Rejected ->
                    rejections += "${path.substringAfterLast('/')}: wanted ${r.wanted}, kept ${r.actual}"
            }
        }

        for (policy in model.policies) {
            profile.governors[policy.id]?.let { put(policy.govNode, it) }

            val min = profile.policyMin[policy.id]
            val max = profile.policyMax[policy.id]
            if (max != null && max >= policy.scalingMax) {
                put(policy.maxNode, max.toString())
                min?.let { put(policy.minNode, it.toString()) }
            } else {
                min?.let { put(policy.minNode, it.toString()) }
                max?.let { put(policy.maxNode, it.toString()) }
            }
        }

        model.gpus.firstOrNull()?.let { gpu ->
            profile.gpuGovernor?.let { put("${gpu.path}/governor", it) }
            val max = profile.gpuMax
            val min = profile.gpuMin
            if (max != null && max >= gpu.maxFreq) {
                put("${gpu.path}/max_freq", max.toString())
                min?.let { put("${gpu.path}/min_freq", it.toString()) }
            } else {
                min?.let { put("${gpu.path}/min_freq", it.toString()) }
                max?.let { put("${gpu.path}/max_freq", it.toString()) }
            }
        }

        profile.tunables.forEach { (path, value) -> put(path, value) }
        profile.vm.forEach { (name, value) -> put("/proc/sys/vm/$name", value) }
        profile.io.forEach { (key, value) ->
            val device = key.substringBefore('/')
            val knob = key.substringAfter('/')
            put("/sys/block/$device/queue/$knob", value)
        }
        return rejections
    }

    /**
     * The current state as a profile.
     *
     * Only captures what a profile is allowed to restore. vm and I/O are left out
     * on purpose: snapshotting all 43 vm knobs would make every profile a
     * whole-system image, and applying one would then stamp over settings the user
     * never chose to include.
     */
    fun snapshot(model: DeviceModel, name: String) = Profile(
        name = name,
        policyMin = model.policies.associate { it.id to it.scalingMin },
        policyMax = model.policies.associate { it.id to it.scalingMax },
        governors = model.policies.associate { it.id to it.governor },
        gpuMin = model.gpus.firstOrNull()?.minFreq,
        gpuMax = model.gpus.firstOrNull()?.maxFreq,
        gpuGovernor = model.gpus.firstOrNull()?.governor,
        tunables = model.policies.flatMap { p ->
            p.governorTunables.values.filter { it.isUsable }.map { it.path to it.value }
        }.toMap(),
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
            profile.policyMax[policy.id]?.let { writes += policy.maxNode to it.toString() }
            profile.policyMin[policy.id]?.let { writes += policy.minNode to it.toString() }
        }
        model.gpus.firstOrNull()?.let { gpu ->
            profile.gpuGovernor?.let { writes += "${gpu.path}/governor" to it }
            profile.gpuMax?.let { writes += "${gpu.path}/max_freq" to it.toString() }
            profile.gpuMin?.let { writes += "${gpu.path}/min_freq" to it.toString() }
        }
        profile.tunables.forEach { (p, v) -> writes += p to v }
        profile.vm.forEach { (n, v) -> writes += "/proc/sys/vm/$n" to v }
        profile.io.forEach { (k, v) ->
            writes += "/sys/block/${k.substringBefore('/')}/queue/${k.substringAfter('/')}" to v
        }
        for ((path, value) in writes) {
            appendLine("[ -e $path ] && echo '$value' > $path")
        }
    }
}
