package com.attey.governor.core

/**
 * The capability report: what this kernel exposes, out of what is known to exist.
 *
 * The denominator is a fixed list of tunable *kinds*, not of paths. Counting
 * paths would make the number scale with the number of CPU clusters and block
 * devices, so two phones could not be compared -- which is the only reason to
 * show a number at all. One representative instance is probed per kind.
 *
 * Notes attached below are measurements, not opinions. Where a node exists but
 * did nothing on real hardware, it says so, because a kernel manager that
 * presents forty knobs as equally meaningful is how this app category earned its
 * reputation.
 */
object Capabilities {

    private class Kind(
        val area: String,
        val name: String,
        val template: String,
        val note: String? = null,
    )

    private val KINDS = listOf(
        // --- CPU frequency
        Kind("CPU", "scaling min", "{policy}/scaling_min_freq"),
        Kind("CPU", "scaling max", "{policy}/scaling_max_freq"),
        Kind("CPU", "governor", "{policy}/scaling_governor"),
        Kind("CPU", "userspace setspeed", "{policy}/scaling_setspeed"),
        Kind("CPU", "frequency table", "{policy}/scaling_available_frequencies"),
        Kind("CPU", "boost frequencies", "{policy}/scaling_boost_frequencies"),
        Kind("CPU", "current frequency", "{policy}/cpuinfo_cur_freq"),
        Kind("CPU", "time in state", "{policy}/stats/time_in_state"),
        Kind("CPU", "transition count", "{policy}/stats/total_trans"),
        Kind("CPU", "core hotplug", "/sys/devices/system/cpu/cpu1/online"),
        Kind("CPU", "core_ctl min cpus", "{policy}/core_ctl/min_cpus"),
        Kind("CPU", "core_ctl max cpus", "{policy}/core_ctl/max_cpus"),
        Kind("CPU", "core_ctl busy up", "{policy}/core_ctl/busy_up_thres"),
        Kind("CPU", "core_ctl busy down", "{policy}/core_ctl/busy_down_thres"),
        Kind("CPU", "core_ctl offline delay", "{policy}/core_ctl/offline_delay_ms"),

        // --- Governor tunables. schedutil and interactive are the two that ship.
        Kind("Governor", "up rate limit", "{gov}/up_rate_limit_us"),
        Kind("Governor", "down rate limit", "{gov}/down_rate_limit_us"),
        Kind("Governor", "hispeed freq", "{gov}/hispeed_freq"),
        Kind("Governor", "hispeed load", "{gov}/hispeed_load"),
        Kind("Governor", "rtg boost freq", "{gov}/rtg_boost_freq"),
        Kind("Governor", "predictive load", "{gov}/pl"),
        Kind("Governor", "target loads", "{gov}/target_loads"),
        Kind("Governor", "above hispeed delay", "{gov}/above_hispeed_delay"),
        Kind("Governor", "go hispeed load", "{gov}/go_hispeed_load"),
        Kind("Governor", "min sample time", "{gov}/min_sample_time"),
        Kind("Governor", "timer rate", "{gov}/timer_rate"),
        Kind("Governor", "boostpulse", "{gov}/boostpulse"),

        // --- Input boost
        Kind(
            "Boost", "input boost freq", "{boost}/input_boost_freq",
            "per-cpu pairs, not a scalar: \"0:1344000 1:0 ...\". Writing a bare number silently boosts cpu0 only.",
        ),
        Kind("Boost", "input boost duration", "{boost}/input_boost_ms"),
        Kind("Boost", "sched boost on input", "{boost}/sched_boost_on_input"),

        // --- GPU
        Kind("GPU", "min frequency", "{gpu}/min_freq"),
        Kind("GPU", "max frequency", "{gpu}/max_freq"),
        Kind("GPU", "governor", "{gpu}/governor"),
        Kind("GPU", "frequency table", "{gpu}/available_frequencies"),
        Kind("GPU", "polling interval", "{gpu}/polling_interval"),
        Kind("GPU", "busy percentage", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
        Kind("GPU", "throttling", "/sys/class/kgsl/kgsl-3d0/throttling"),
        Kind("GPU", "default power level", "/sys/class/kgsl/kgsl-3d0/default_pwrlevel"),
        Kind("GPU", "min power level", "/sys/class/kgsl/kgsl-3d0/min_pwrlevel"),
        Kind("GPU", "max power level", "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"),
        Kind("GPU", "force clock on", "/sys/class/kgsl/kgsl-3d0/force_clk_on"),
        Kind("GPU", "force bus on", "/sys/class/kgsl/kgsl-3d0/force_bus_on"),
        Kind("GPU", "idle timer", "/sys/class/kgsl/kgsl-3d0/idle_timer"),

        // --- Battery
        Kind("Battery", "current", "{bat}/current_now"),
        Kind("Battery", "voltage", "{bat}/voltage_now"),
        Kind("Battery", "capacity now", "{bat}/charge_full"),
        Kind("Battery", "design capacity", "{bat}/charge_full_design"),
        Kind("Battery", "cycle count", "{bat}/cycle_count"),
        Kind("Battery", "temperature", "{bat}/temp"),
        Kind("Battery", "health", "{bat}/health"),
        Kind("Battery", "charge limit", "{bat}/charge_control_limit"),
        Kind("Battery", "charging enable", "{bat}/battery_charging_enabled"),
        Kind("Battery", "input suspend", "{bat}/input_suspend"),
        Kind("Battery", "charge current limit", "{bat}/constant_charge_current_max"),

        // --- I/O
        Kind("I/O", "scheduler", "{blk}/queue/scheduler"),
        Kind(
            "I/O", "read ahead", "{blk}/queue/read_ahead_kb",
            "measured null on the development device: no setting moved sequential or random throughput.",
        ),
        Kind("I/O", "queue depth", "{blk}/queue/nr_requests"),
        Kind("I/O", "request affinity", "{blk}/queue/rq_affinity"),
        Kind("I/O", "entropy contribution", "{blk}/queue/add_random"),
        Kind("I/O", "iostats", "{blk}/queue/iostats"),
        Kind("I/O", "merge policy", "{blk}/queue/nomerges"),
        Kind("I/O", "rotational flag", "{blk}/queue/rotational"),

        // --- Memory
        Kind(
            "Memory", "swappiness", "/proc/sys/vm/swappiness",
            "measured null on the development device: no effect on launch times or page faults.",
        ),
        Kind(
            "Memory", "page cluster", "/proc/sys/vm/page-cluster",
            "measured null on the development device.",
        ),
        Kind("Memory", "cache pressure", "/proc/sys/vm/vfs_cache_pressure"),
        Kind("Memory", "dirty ratio", "/proc/sys/vm/dirty_ratio"),
        Kind("Memory", "dirty background ratio", "/proc/sys/vm/dirty_background_ratio"),
        Kind("Memory", "min free", "/proc/sys/vm/min_free_kbytes"),
        Kind("Memory", "extra free", "/proc/sys/vm/extra_free_kbytes"),
        Kind("Memory", "watermark scale", "/proc/sys/vm/watermark_scale_factor"),
        Kind("Memory", "watermark boost", "/proc/sys/vm/watermark_boost_factor"),
        Kind("Memory", "stat interval", "/proc/sys/vm/stat_interval"),
        Kind("Memory", "zram size", "/sys/block/zram0/disksize"),
        Kind("Memory", "zram algorithm", "/sys/block/zram0/comp_algorithm"),
        Kind("Memory", "zram streams", "/sys/block/zram0/max_comp_streams"),

        // --- Thermal, read-only on purpose
        Kind("Thermal", "zone temperature", "/sys/class/thermal/thermal_zone0/temp"),
        Kind(
            "Thermal", "cooling state", "/sys/class/thermal/cooling_device0/cur_state",
            "read-only in this app. A userspace thermal governor re-parks any change within seconds, and losing that argument means a hot phone.",
        ),
        Kind("Thermal", "vendor thermal profile", "/sys/class/thermal/thermal_message/sconfig"),
    )

    val total: Int get() = KINDS.size

    /**
     * Resolves every kind against this device and probes the ones whose path is
     * not already known. One round trip.
     */
    fun report(shell: RootShell, model: DeviceModel): List<Capability> {
        val policy = model.policies.firstOrNull()
        val gpu = model.gpus.firstOrNull()
        val blk = model.blockDevices.firstOrNull { !it.isVirtual }
        // The directory holding input_boost_freq, not the parent of whichever
        // node happened to sort first -- probeBoost merges two candidate
        // directories into one map keyed by bare filename.
        val boostDir = (model.boost["input_boost_freq"] ?: model.boost.values.firstOrNull())
            ?.path?.substringBeforeLast('/')

        val anchors = mapOf(
            "{policy}" to policy?.path,
            "{gov}" to policy?.takeIf { it.governor.isNotEmpty() }?.let { "${it.path}/${it.governor}" },
            "{gpu}" to gpu?.path,
            "{bat}" to model.battery?.path,
            "{blk}" to blk?.path,
            "{boost}" to boostDir,
        )
        // A kind whose anchor this device does not have resolves to nothing at
        // all, rather than to the tail of its own template. Substituting an empty
        // string would leave "{gpu}/min_freq" as "/min_freq" -- a path that gets
        // probed, comes back absent, and is then printed in the report as though
        // it were where the GPU lives.
        val resolved = KINDS.map { k ->
            val missing = anchors.any { (token, value) ->
                value == null && k.template.contains(token)
            }
            val path = if (missing) null else anchors.entries.fold(k.template) { acc, (token, value) ->
                if (value == null) acc else acc.replace(token, value)
            }
            k to path
        }
        val nodes = SysNode.probe(shell, resolved.mapNotNull { it.second }.distinct())

        return resolved.map { (k, p) ->
            val node = p?.let { nodes[it] }
            Capability(
                area = k.area,
                name = k.name,
                path = p ?: k.template,
                present = node?.exists == true,
                writable = node?.writable == true,
                note = k.note,
            )
        }
    }
}
