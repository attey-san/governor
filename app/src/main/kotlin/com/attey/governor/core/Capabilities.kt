package com.attey.governor.core

/** Reports one representative path for each known kernel interface. */
object Capabilities {

    private class Kind(
        val area: String,
        val name: String,
        val template: String,
        val note: String? = null,
    )

    private val KINDS = listOf(
        // CPU frequency
        Kind("CPU", "scaling min", "{policy}/scaling_min_freq"),
        Kind("CPU", "scaling max", "{policy}/scaling_max_freq"),
        Kind("CPU", "governor", "{policy}/scaling_governor"),
        Kind("CPU", "userspace setspeed", "{policy}/scaling_setspeed"),
        Kind("CPU", "frequency table", "{policy}/scaling_available_frequencies"),
        Kind("CPU", "boost frequencies", "{policy}/scaling_boost_frequencies"),
        Kind("CPU", "current frequency", "{policy}/cpuinfo_cur_freq"),
        Kind("CPU", "time in state", "{policy}/stats/time_in_state"),
        Kind("CPU", "transition count", "{policy}/stats/total_trans"),
        Kind("CPU", "core hotplug", "{cpu}/online"),
        Kind("CPU", "core_ctl min cpus", "{policy}/core_ctl/min_cpus"),
        Kind("CPU", "core_ctl max cpus", "{policy}/core_ctl/max_cpus"),
        Kind("CPU", "core_ctl busy up", "{policy}/core_ctl/busy_up_thres"),
        Kind("CPU", "core_ctl busy down", "{policy}/core_ctl/busy_down_thres"),
        Kind("CPU", "core_ctl offline delay", "{policy}/core_ctl/offline_delay_ms"),

        // Governor tunables
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

        // Input boost
        Kind(
            "Boost", "input boost freq", "{boost}/input_boost_freq",
            "Uses per-CPU pairs such as \"0:1344000 1:0 ...\"; a scalar updates CPU 0 only.",
        ),
        Kind("Boost", "input boost duration", "{boost}/input_boost_ms"),
        Kind("Boost", "sched boost on input", "{boost}/sched_boost_on_input"),

        // GPU
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

        // Battery
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

        // I/O
        Kind("I/O", "scheduler", "{blk}/queue/scheduler"),
        Kind(
            "I/O", "read ahead", "{blk}/queue/read_ahead_kb",
            "No measurable throughput change in development-device testing.",
        ),
        Kind("I/O", "queue depth", "{blk}/queue/nr_requests"),
        Kind("I/O", "request affinity", "{blk}/queue/rq_affinity"),
        Kind("I/O", "entropy contribution", "{blk}/queue/add_random"),
        Kind("I/O", "iostats", "{blk}/queue/iostats"),
        Kind("I/O", "merge policy", "{blk}/queue/nomerges"),
        Kind("I/O", "rotational flag", "{blk}/queue/rotational"),

        // Memory
        Kind(
            "Memory", "swappiness", "/proc/sys/vm/swappiness",
            "No measurable launch-time or page-fault change in development-device testing.",
        ),
        Kind(
            "Memory", "page cluster", "/proc/sys/vm/page-cluster",
            "No measurable change in development-device testing.",
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

        // Thermal (read-only)
        Kind("Thermal", "zone temperature", "/sys/class/thermal/thermal_zone0/temp"),
        Kind(
            "Thermal", "cooling state", "/sys/class/thermal/cooling_device0/cur_state",
            "Governor keeps thermal controls read-only; vendor services normally manage them.",
        ),
        Kind("Thermal", "vendor thermal profile", "/sys/class/thermal/thermal_message/sconfig"),
    )

    fun report(shell: RootShell, model: DeviceModel): List<Capability> {
        val candidates = KINDS.associateWith { pathsFor(it.template, model) }
        val nodes = SysNode.probe(shell, candidates.values.flatten().distinct())

        return candidates.map { (kind, paths) ->
            val path = paths.firstOrNull { nodes[it]?.exists == true } ?: paths.firstOrNull()
            val node = path?.let(nodes::get)
            Capability(
                area = kind.area,
                name = kind.name,
                path = path ?: kind.template,
                present = node?.exists == true,
                writable = node?.writable == true,
                note = kind.note,
            )
        }
    }

    internal fun pathsFor(template: String, model: DeviceModel): List<String> {
        val anchors = linkedMapOf(
            "{policy}" to model.policies.map { it.path },
            "{gov}" to model.policies.mapNotNull { policy ->
                policy.governor.takeIf(String::isNotEmpty)?.let { "${policy.path}/$it" }
            },
            "{cpu}" to model.policies.flatMap { it.cpus }.filter { it != 0 }
                .distinct().sorted().map { "/sys/devices/system/cpu/cpu$it" },
            "{gpu}" to model.gpus.map { it.path },
            "{bat}" to listOfNotNull(model.battery?.path),
            "{blk}" to model.blockDevices.filterNot { it.isVirtual }.map { it.path },
            "{boost}" to model.boost.values.map { it.path.substringBeforeLast('/') }.distinct(),
        )
        val entry = anchors.entries.firstOrNull { template.contains(it.key) }
            ?: return listOf(template)
        return entry.value.map { template.replace(entry.key, it) }
    }
}
