package com.attey.governor.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Discovers kernel interfaces without device or SoC tables. */
object DeviceProbe {

    private const val CPU = "/sys/devices/system/cpu"
    private const val CPUFREQ = "$CPU/cpufreq"

    suspend fun probe(shell: RootShell): DeviceModel = withContext(Dispatchers.IO) {
        val kernel = shell.exec("uname -r").trim()
        val rootProvider = shell.exec("su -v 2>/dev/null").trim().ifEmpty { "unknown" }
        val policies = probePolicies(shell)
        val cores = probeCores(shell, policies)
        DeviceModel(
            policies = policies,
            gpus = probeGpus(shell),
            battery = probeBattery(shell),
            blockDevices = probeBlock(shell),
            thermalZones = probeThermal(shell),
            vmTunables = probeVm(shell),
            boost = probeBoost(shell),
            zram = probeZram(shell),
            coresOnline = cores.mapValues { it.value.value != "0" },
            hotpluggableCores = cores.filterValues { it.isUsable }.keys,
            rootProvider = rootProvider,
            kernel = kernel,
        )
    }

    // CPU

    private fun probePolicies(shell: RootShell): List<CpuPolicy> {
        val dirs = shell.lines("ls -d $CPUFREQ/policy* 2>/dev/null")
            .filter { it.startsWith(CPUFREQ) }
            .sortedBy { it.removePrefix("$CPUFREQ/policy").toIntOrNull() ?: 0 }
        if (dirs.isEmpty()) return emptyList()

        val fields = listOf(
            "related_cpus", "cpuinfo_min_freq", "cpuinfo_max_freq",
            "scaling_min_freq", "scaling_max_freq", "scaling_governor",
            "scaling_available_governors", "scaling_available_frequencies",
            "scaling_boost_frequencies", "stats/time_in_state",
        )
        val read = shell.readAll(dirs.flatMap { d -> fields.map { "$d/$it" } }) +
            shell.readMultiline(dirs.map { "$it/stats/time_in_state" })
        val controlModes = SysNode.modes(
            shell,
            dirs.flatMap { d ->
                listOf("$d/scaling_min_freq", "$d/scaling_max_freq", "$d/scaling_governor")
            },
        )

        // The active governor determines which tunable directory exists.
        val govDirs = dirs.mapNotNull { d ->
            read["$d/scaling_governor"]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$d/$it" }
        }
        val listings = listDirs(shell, govDirs)
        val tunablePaths = listings.flatMap { (dir, names) -> names.map { "$dir/$it" } }
        val nodes = SysNode.probe(shell, tunablePaths)

        return dirs.mapNotNull { d ->
            val id = d.removePrefix("$CPUFREQ/policy").toIntOrNull() ?: return@mapNotNull null
            val cpus = read["$d/related_cpus"]?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }.orEmpty()
            if (cpus.isEmpty()) return@mapNotNull null

            val gov = read["$d/scaling_governor"]?.trim().orEmpty()
            // Some kernels omit scaling_available_frequencies; time_in_state is
            // the best available frequency table in that case.
            val freqs = numbers(read["$d/scaling_available_frequencies"])
                .ifEmpty { firstColumn(read["$d/stats/time_in_state"]) }
                .plus(numbers(read["$d/scaling_boost_frequencies"]))
                .distinct().sorted()

            CpuPolicy(
                id = id,
                path = d,
                cpus = cpus.sorted(),
                availableFreqs = freqs,
                hwMin = read["$d/cpuinfo_min_freq"]?.trim()?.toLongOrNull() ?: freqs.firstOrNull() ?: 0,
                hwMax = read["$d/cpuinfo_max_freq"]?.trim()?.toLongOrNull() ?: freqs.lastOrNull() ?: 0,
                scalingMin = read["$d/scaling_min_freq"]?.trim()?.toLongOrNull() ?: 0,
                scalingMax = read["$d/scaling_max_freq"]?.trim()?.toLongOrNull() ?: 0,
                governor = gov,
                availableGovernors = read["$d/scaling_available_governors"]
                    ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty(),
                minWritable = SysNode.writable(controlModes["$d/scaling_min_freq"]),
                maxWritable = SysNode.writable(controlModes["$d/scaling_max_freq"]),
                governorWritable = SysNode.writable(controlModes["$d/scaling_governor"]),
                governorTunables = nodes.filterKeys { it.startsWith("$d/$gov/") }
                    .mapKeys { it.key.substringAfterLast('/') },
            )
        }
    }

    private fun probeCores(shell: RootShell, policies: List<CpuPolicy>): Map<Int, SysNode> {
        val cpus = policies.flatMap { it.cpus }.sorted()
        val nodes = SysNode.probe(shell, cpus.map { "$CPU/cpu$it/online" })
        return cpus.associateWith { c ->
            nodes["$CPU/cpu$c/online"] ?: SysNode("$CPU/cpu$c/online")
        }
    }

    // GPU

    // devfreq also contains bandwidth, latency, and NPU devices.
    private val GPU_HINT = Regex("kgsl-3d|mali|powervr|\\bgpu\\b|gpu\\d", RegexOption.IGNORE_CASE)
    private val NOT_GPU = Regex("bw|busmon|bwmon|llcc|ddr|l3|npu|cdsp|snoc|cnoc|lat", RegexOption.IGNORE_CASE)

    private fun probeGpus(shell: RootShell): List<GpuDevice> {
        val kgsl = "/sys/class/kgsl/kgsl-3d0/devfreq"
        val dirs = if (shell.exec("[ -d $kgsl ] && echo 1").trim() == "1") {
            listOf(kgsl)
        } else {
            shell.lines("ls -d /sys/class/devfreq/* 2>/dev/null")
                .filter { GPU_HINT.containsMatchIn(it) && !NOT_GPU.containsMatchIn(it) }
        }
        if (dirs.isEmpty()) return emptyList()

        val fields = listOf(
            "available_frequencies", "available_governors", "governor",
            "min_freq", "max_freq", "name",
        )
        val read = shell.readAll(
            dirs.flatMap { d -> fields.map { "$d/$it" } } + "/sys/class/kgsl/kgsl-3d0/gpu_model"
        )
        val controlModes = SysNode.modes(
            shell,
            dirs.flatMap { d -> listOf("$d/min_freq", "$d/max_freq", "$d/governor") },
        )
        return dirs.map { d ->
            val freqs = numbers(read["$d/available_frequencies"])
            GpuDevice(
                name = read["/sys/class/kgsl/kgsl-3d0/gpu_model"]?.trim().orEmpty()
                    .ifEmpty { read["$d/name"]?.trim().orEmpty() }
                    .ifEmpty { d.substringAfterLast('/') },
                path = d,
                availableFreqs = freqs,
                minFreq = read["$d/min_freq"]?.trim()?.toLongOrNull() ?: freqs.firstOrNull() ?: 0,
                maxFreq = read["$d/max_freq"]?.trim()?.toLongOrNull() ?: freqs.lastOrNull() ?: 0,
                governor = read["$d/governor"]?.trim().orEmpty(),
                availableGovernors = read["$d/available_governors"]
                    ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty(),
                minWritable = SysNode.writable(controlModes["$d/min_freq"]),
                maxWritable = SysNode.writable(controlModes["$d/max_freq"]),
                governorWritable = SysNode.writable(controlModes["$d/governor"]),
            )
        }
    }

    // Battery

    private fun probeBattery(shell: RootShell): BatteryNodes? {
        val supplies = shell.lines("ls -d /sys/class/power_supply/* 2>/dev/null")
        if (supplies.isEmpty()) return null
        val types = shell.readAll(supplies.map { "$it/type" })
        val batteries = supplies.filter { types["$it/type"]?.trim().equals("Battery", true) }
        // Some devices expose duplicate battery and bms supplies.
        val path = batteries.firstOrNull { it.endsWith("/battery") } ?: batteries.firstOrNull()
            ?: return null

        val n = listOf("charge_full", "charge_full_design", "cycle_count")
        val read = shell.readAll(n.map { "$path/$it" })
        val full = read["$path/charge_full"]?.trim()?.toLongOrNull() ?: 0
        val design = read["$path/charge_full_design"]?.trim()?.toLongOrNull() ?: 0
        val cycles = read["$path/cycle_count"]?.trim()?.toLongOrNull()

        // A worn pack reporting 0 or 1 cycles is an unimplemented gauge.
        val healthPct = if (design > 0) full * 100 / design else 100
        val cyclesUsable = cycles != null && !(cycles <= 1 && healthPct < 95)

        return BatteryNodes(
            path = path,
            chargeFullUah = full,
            chargeFullDesignUah = design,
            cycleCount = cycles.takeIf { cyclesUsable },
        )
    }

    // Block devices

    private val VIRTUAL_BLOCK = Regex("^(dm-|loop|ram|zram|md|sr)")

    private fun probeBlock(shell: RootShell): List<BlockDevice> {
        val names = shell.lines("ls /sys/block 2>/dev/null")
        if (names.isEmpty()) return emptyList()
        val disks = names.toSet()

        val fields = listOf(
            "queue/scheduler", "queue/read_ahead_kb", "queue/nr_requests",
            "queue/rotational", "size",
        )
        val read = shell.readAll(names.flatMap { n -> fields.map { "/sys/block/$n/$it" } })
        // Virtual queues are read-only; skip their mode checks too.
        val editable = names.filterNot { VIRTUAL_BLOCK.containsMatchIn(it) }
        val controlModes = SysNode.modes(
            shell,
            editable.flatMap { n ->
                listOf(
                    "/sys/block/$n/queue/scheduler",
                    "/sys/block/$n/queue/read_ahead_kb",
                    "/sys/block/$n/queue/nr_requests",
                )
            },
        )
        val mounts = mountPoints(shell, disks)

        return names.map { n ->
            val sched = read["/sys/block/$n/queue/scheduler"].orEmpty()
            BlockDevice(
                name = n,
                path = "/sys/block/$n",
                scheduler = currentScheduler(sched),
                availableSchedulers = sched.split(Regex("\\s+"))
                    .map { it.trim('[', ']') }.filter { it.isNotBlank() },
                schedulerWritable = SysNode.writable(controlModes["/sys/block/$n/queue/scheduler"]),
                readAheadKb = read["/sys/block/$n/queue/read_ahead_kb"]?.trim()?.toLongOrNull() ?: 0,
                readAheadWritable = SysNode.writable(
                    controlModes["/sys/block/$n/queue/read_ahead_kb"]
                ),
                nrRequests = read["/sys/block/$n/queue/nr_requests"]?.trim()?.toLongOrNull() ?: 0,
                nrRequestsWritable = SysNode.writable(
                    controlModes["/sys/block/$n/queue/nr_requests"]
                ),
                rotational = read["/sys/block/$n/queue/rotational"]?.trim() == "1",
                isVirtual = VIRTUAL_BLOCK.containsMatchIn(n),
                mountedAt = mounts[n],
                // sysfs reports size in fixed 512-byte sectors.
                sizeBytes = (read["/sys/block/$n/size"]?.trim()?.toLongOrNull() ?: 0) * 512,
            )
        // Put the main physical disk first.
        }.sortedWith(compareBy<BlockDevice> { it.isVirtual }.thenByDescending { it.sizeBytes })
    }

    /** Parses both bracketed scheduler menus and a single unbracketed value. */
    private fun currentScheduler(raw: String): String {
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        tokens.firstOrNull { it.startsWith("[") }?.let { return it.trim('[', ']') }
        return tokens.singleOrNull().orEmpty()
    }

    /** Resolves device-mapper mounts through each physical partition's holders. */
    private fun mountPoints(shell: RootShell, disks: Set<String>): Map<String, String> {
        val holders = HashMap<String, String>()
        for (line in shell.lines("ls -d /sys/block/*/*/holders/* /sys/block/*/holders/* 2>/dev/null")) {
            val parts = line.split('/')
            val idx = parts.indexOf("holders")
            if (idx < 0 || idx + 1 >= parts.size) continue
            holders[parts[idx + 1]] = parts.getOrNull(3) ?: continue
        }

        val result = HashMap<String, String>()
        for (line in shell.lines("cat /proc/mounts 2>/dev/null")) {
            val f = line.split(' ')
            if (f.size < 2 || !f[0].startsWith("/dev/block/")) continue
            val dev = f[0].removePrefix("/dev/block/").substringAfterLast('/')
            val mount = f[1]
            val disk = holders[dev] ?: diskOf(dev, disks) ?: continue
            val existing = result[disk]
            if (existing == null || rank(mount) < rank(existing)) result[disk] = mount
            if (dev in disks) result[dev] = mount
        }
        return result
    }

    private fun rank(mount: String) = when (mount) {
        "/data" -> 0; "/" -> 1; "/vendor" -> 2; else -> 3
    }

    private fun diskOf(name: String, disks: Set<String>): String? {
        if (name in disks) return name
        Regex("^(mmcblk\\d+|nvme\\d+n\\d+)p\\d+$").find(name)?.groupValues?.get(1)
            ?.let { if (it in disks) return it }
        val base = name.trimEnd { it.isDigit() }
        return if (base.isNotEmpty() && base in disks) base else null
    }

    // Thermal and VM

    private fun probeThermal(shell: RootShell): List<ThermalZone> {
        val dirs = shell.lines("ls -d /sys/class/thermal/thermal_zone* 2>/dev/null")
        if (dirs.isEmpty()) return emptyList()
        val read = shell.readAll(dirs.flatMap { listOf("$it/type", "$it/temp") })
        return dirs.mapNotNull { d ->
            val id = d.substringAfterLast("thermal_zone").toIntOrNull() ?: return@mapNotNull null
            ThermalZone(
                id = id,
                type = read["$d/type"]?.trim().orEmpty(),
                // Keep unreadable zones in the model as unpopulated.
                tempMilliC = read["$d/temp"]?.trim()?.toIntOrNull() ?: Int.MIN_VALUE,
            )
        }
    }

    private fun probeVm(shell: RootShell): Map<String, SysNode> {
        val names = shell.lines("ls /proc/sys/vm 2>/dev/null")
        if (names.isEmpty()) return emptyMap()
        return SysNode.probe(shell, names.map { "/proc/sys/vm/$it" })
            .mapKeys { it.key.substringAfterLast('/') }
    }

    /** Handles both module-parameter and device-attribute input boost drivers. */
    private fun probeBoost(shell: RootShell): Map<String, SysNode> {
        val dirs = listOf("/sys/module/cpu_boost/parameters", "$CPU/cpu_boost")
        val listings = listDirs(shell, dirs)
        val paths = listings.flatMap { (d, names) -> names.map { "$d/$it" } }
        return SysNode.probe(shell, paths).mapKeys { it.key.substringAfterLast('/') }
    }

    private val ZRAM_KNOBS = listOf(
        "disksize", "comp_algorithm", "max_comp_streams", "mem_limit",
        "mem_used_max", "mm_stat", "io_stat",
    )

    private fun probeZram(shell: RootShell): Map<String, SysNode> =
        SysNode.probe(shell, ZRAM_KNOBS.map { "/sys/block/zram0/$it" })
            .filterValues { it.exists }
            .mapKeys { it.key.substringAfterLast('/') }

    // Live values

    // Latch the current_now unit guess to avoid a 1000x step mid-session.
    @Volatile private var currentInMilliamps: Boolean? = null

    fun sampleLive(
        shell: RootShell,
        model: DeviceModel,
        includeThermal: Boolean = true,
        previousHottest: Pair<String, Float>? = null,
    ): LiveStats {
        val bat = model.battery?.path
        val paths = buildList {
            model.policies.forEach { add("${it.path}/scaling_cur_freq") }
            model.gpus.forEach { add("${it.path}/cur_freq") }
            add("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            if (bat != null) {
                addAll(listOf("current_now", "voltage_now", "capacity", "temp", "status").map { "$bat/$it" })
            }
            if (includeThermal) {
                model.thermalZones.forEach { add("/sys/class/thermal/thermal_zone${it.id}/temp") }
            }
        }
        val read = shell.readAll(paths)

        val uV = read["$bat/voltage_now"]?.trim()?.toLongOrNull()
        val rawCurrent = read["$bat/current_now"]?.trim()?.toLongOrNull()
        val charging = read["$bat/status"]?.trim()?.let { status ->
            status.equals("Charging", true) || status.equals("Full", true) ||
                status.equals("Not charging", true)
        } == true
        val mW = milliwatts(rawCurrent, uV, inferUnits = !charging)

        val temps: Map<Int, Float> = if (!includeThermal) emptyMap() else
            model.thermalZones.mapNotNull { z ->
                val t = read["/sys/class/thermal/thermal_zone${z.id}/temp"]?.trim()?.toIntOrNull()
                    ?: return@mapNotNull null
                if (t <= -30_000) null else z.id to t / 1000f
            }.toMap()
        val hottest = if (!includeThermal) previousHottest else temps.maxByOrNull { it.value }
            ?.let { e -> model.thermalZones.first { it.id == e.key }.type to e.value }

        return LiveStats(
            policyCurFreq = model.policies.mapNotNull { p ->
                read["${p.path}/scaling_cur_freq"]?.trim()?.toLongOrNull()?.let { p.id to it }
            }.toMap(),
            gpuCurFreq = model.gpus.mapNotNull { gpu ->
                read["${gpu.path}/cur_freq"]?.trim()?.toLongOrNull()?.let { gpu.path to it }
            }.toMap(),
            // Qualcomm reports values such as "3 %".
            gpuBusyPercent = model.gpus.firstOrNull { it.path == "/sys/class/kgsl/kgsl-3d0/devfreq" }
                ?.let { gpu ->
                    read["/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"]
                        ?.filter { it.isDigit() }?.toIntOrNull()?.let { mapOf(gpu.path to it) }
                }.orEmpty(),
            batteryMilliwatts = mW,
            batteryPercent = read["$bat/capacity"]?.trim()?.toIntOrNull(),
            batteryTempC = read["$bat/temp"]?.trim()?.toIntOrNull()?.let { it / 10f },
            charging = charging,
            hottestZone = hottest,
            zoneTemps = temps,
        )
    }

    private fun milliwatts(rawCurrent: Long?, uV: Long?, inferUnits: Boolean): Int? {
        if (rawCurrent == null || uV == null || uV <= 0) return null
        if (rawCurrent == 0L) return 0
        // Compute as microamps first; sub-5 mW foreground readings imply mA units.
        val mWIfMicroamps = abs(rawCurrent).toDouble() * uV / 1_000_000_000.0
        val milliamps = currentInMilliamps ?: if (inferUnits) {
            (mWIfMicroamps < 5.0).also { currentInMilliamps = it }
        } else {
            false
        }
        return (if (milliamps) mWIfMicroamps * 1000 else mWIfMicroamps).roundToInt()
    }

    fun timeInState(shell: RootShell, policies: List<CpuPolicy>): Map<Int, Map<Long, Long>> {
        val read = shell.readMultiline(policies.map { "${it.path}/stats/time_in_state" })
        return policies.associate { p ->
            p.id to read["${p.path}/stats/time_in_state"].orEmpty().lineSequence()
                .mapNotNull { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size < 2) null else (f[0].toLongOrNull() ?: return@mapNotNull null) to
                        (f[1].toLongOrNull() ?: return@mapNotNull null)
                }.toMap()
        }
    }

    // Helpers

    private fun RootShell.lines(cmd: String): List<String> =
        exec(cmd).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun numbers(s: String?): List<Long> =
        s?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.distinct()?.sorted().orEmpty()

    private fun firstColumn(s: String?): List<Long> =
        s?.lineSequence()?.mapNotNull { it.trim().substringBefore(' ').toLongOrNull() }
            ?.distinct()?.sorted()?.toList().orEmpty()

    private fun listDirs(shell: RootShell, dirs: List<String>): Map<String, List<String>> {
        if (dirs.isEmpty()) return emptyMap()
        val script = dirs.joinToString("\n") { d ->
            val q = shellQuote(d)
            // echo is a builtin on Android's mksh; printf is not.
            "for f in $q/*; do [ -e \"\$f\" ] && echo $q'%%GOV%%'\"\${f##*/}\"; done"
        }
        val map = HashMap<String, MutableList<String>>()
        for (line in shell.exec(script).lineSequence()) {
            val i = line.indexOf("%%GOV%%")
            if (i <= 0) continue
            map.getOrPut(line.substring(0, i)) { mutableListOf() }.add(line.substring(i + 7))
        }
        return map
    }
}
