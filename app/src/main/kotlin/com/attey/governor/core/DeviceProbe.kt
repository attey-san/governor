package com.attey.governor.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Discovers what a kernel exposes.
 *
 * Every path here is either enumerated or probed. Nothing is keyed off a device
 * name, an SoC, or a core index. The parsing is the part of this app that can be
 * wrong without anyone noticing, so each non-obvious decision below carries the
 * observation that forced it.
 */
object DeviceProbe {

    private const val CPU = "/sys/devices/system/cpu"
    private const val CPUFREQ = "$CPU/cpufreq"

    suspend fun probe(shell: RootShell): DeviceModel = withContext(Dispatchers.IO) {
        val kernel = shell.exec("uname -r").trim()
        val rootProvider = shell.exec("su -v 2>/dev/null").trim().ifEmpty { "unknown" }
        DeviceModel(
            policies = probePolicies(shell),
            gpus = probeGpus(shell),
            battery = probeBattery(shell),
            blockDevices = probeBlock(shell),
            thermalZones = probeThermal(shell),
            vmTunables = probeVm(shell),
            boost = probeBoost(shell),
            rootProvider = rootProvider,
            kernel = kernel,
        )
    }

    // ---------------------------------------------------------------- CPU

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
        val read = shell.readAll(dirs.flatMap { d -> fields.map { "$d/$it" } })

        // Governor tunables live at policyN/<current governor>/, so the governor
        // has to be known before the directory can be listed. Two round trips,
        // not one per node.
        val govDirs = dirs.mapNotNull { d ->
            read["$d/scaling_governor"]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$d/$it" }
        }
        val listings = listDirs(shell, govDirs + dirs.map { "$it/core_ctl" })
        val tunablePaths = listings.flatMap { (dir, names) -> names.map { "$dir/$it" } }
        val nodes = SysNode.probe(shell, tunablePaths)

        return dirs.mapNotNull { d ->
            val id = d.removePrefix("$CPUFREQ/policy").toIntOrNull() ?: return@mapNotNull null
            val cpus = read["$d/related_cpus"]?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }.orEmpty()
            if (cpus.isEmpty()) return@mapNotNull null

            val gov = read["$d/scaling_governor"]?.trim().orEmpty()
            // scaling_available_frequencies is absent on kernels with a continuous
            // range. time_in_state carries the same ladder as its first column and
            // is present far more often, so it is the fallback rather than a
            // synthesised min..max, which would offer steps the kernel never had.
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
                governorTunables = nodes.filterKeys { it.startsWith("$d/$gov/") }
                    .mapKeys { it.key.substringAfterLast('/') },
                coreCtl = nodes.filterKeys { it.startsWith("$d/core_ctl/") }
                    .mapKeys { it.key.substringAfterLast('/') },
            )
        }
    }

    /** cpu -> online, for the per-core switches. cpu0 is always reported online. */
    fun probeCoreOnline(shell: RootShell, model: DeviceModel): Map<Int, Boolean> {
        val cpus = model.policies.flatMap { it.cpus }.sorted()
        val read = shell.readAll(cpus.map { "$CPU/cpu$it/online" })
        return cpus.associateWith { c ->
            // cpu0 frequently has no online node at all because it cannot be taken
            // down. Absent means present-and-running here, not unknown.
            read["$CPU/cpu$c/online"]?.trim()?.let { it != "0" } ?: true
        }
    }

    // ---------------------------------------------------------------- GPU

    /**
     * Bandwidth and latency monitors sit in the same devfreq class as the GPU and
     * outnumber it. On the development device /sys/class/devfreq held 20 entries,
     * of which exactly one was the GPU; the rest were llcc, ddr, l3 and npu paths
     * that look identical from a distance and are meaningless to tune here.
     */
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
        return dirs.map { d ->
            val freqs = numbers(read["$d/available_frequencies"])
            GpuDevice(
                name = read["/sys/class/kgsl/kgsl-3d0/gpu_model"]?.trim()
                    ?: read["$d/name"]?.trim().orEmpty().ifEmpty { d.substringAfterLast('/') },
                path = d,
                availableFreqs = freqs,
                minFreq = read["$d/min_freq"]?.trim()?.toLongOrNull() ?: freqs.firstOrNull() ?: 0,
                maxFreq = read["$d/max_freq"]?.trim()?.toLongOrNull() ?: freqs.lastOrNull() ?: 0,
                governor = read["$d/governor"]?.trim().orEmpty(),
                availableGovernors = read["$d/available_governors"]
                    ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty(),
            )
        }
    }

    // ------------------------------------------------------------ Battery

    private fun probeBattery(shell: RootShell): BatteryNodes? {
        val supplies = shell.lines("ls -d /sys/class/power_supply/* 2>/dev/null")
        if (supplies.isEmpty()) return null
        val types = shell.readAll(supplies.map { "$it/type" })
        val batteries = supplies.filter { types["$it/type"]?.trim().equals("Battery", true) }
        // Several supplies can claim to be the battery -- this device exposes both
        // `battery` and `bms` with identical readings. Prefer the conventional name.
        val path = batteries.firstOrNull { it.endsWith("/battery") } ?: batteries.firstOrNull()
            ?: return null

        val n = listOf("current_now", "voltage_now", "charge_full", "charge_full_design", "cycle_count")
        val read = shell.readAll(n.map { "$path/$it" })
        val full = read["$path/charge_full"]?.trim()?.toLongOrNull() ?: 0
        val design = read["$path/charge_full_design"]?.trim()?.toLongOrNull() ?: 0
        val cycles = read["$path/cycle_count"]?.trim()?.toLongOrNull()

        // A cycle count of 0 or 1 on a pack that has already lost capacity is the
        // fuel gauge not reporting, not a new battery. The development device
        // reads cycle_count=1 at 86% of design capacity. Printing that as fact is
        // worse than admitting the kernel does not tell us.
        val healthPct = if (design > 0) full * 100 / design else 100
        val cyclesUsable = cycles != null && !(cycles <= 1 && healthPct < 95)

        return BatteryNodes(
            path = path,
            hasCurrent = read["$path/current_now"]?.trim()?.toLongOrNull() != null,
            hasVoltage = read["$path/voltage_now"]?.trim()?.toLongOrNull() != null,
            hasChargeFull = full > 0 && design > 0,
            hasCycleCount = cyclesUsable,
        )
    }

    // -------------------------------------------------------------- Block

    private val VIRTUAL_BLOCK = Regex("^(dm-|loop|ram|zram|md|sr)")

    private fun probeBlock(shell: RootShell): List<BlockDevice> {
        val names = shell.lines("ls /sys/block 2>/dev/null")
        if (names.isEmpty()) return emptyList()
        val disks = names.toSet()

        val fields = listOf("queue/scheduler", "queue/read_ahead_kb", "queue/nr_requests", "queue/rotational")
        val read = shell.readAll(names.flatMap { n -> fields.map { "/sys/block/$n/$it" } })
        val mounts = mountPoints(shell, disks)

        return names.map { n ->
            val sched = read["/sys/block/$n/queue/scheduler"].orEmpty()
            BlockDevice(
                name = n,
                path = "/sys/block/$n",
                scheduler = currentScheduler(sched),
                availableSchedulers = sched.split(Regex("\\s+"))
                    .map { it.trim('[', ']') }.filter { it.isNotBlank() },
                readAheadKb = read["/sys/block/$n/queue/read_ahead_kb"]?.trim()?.toLongOrNull() ?: 0,
                nrRequests = read["/sys/block/$n/queue/nr_requests"]?.trim()?.toLongOrNull() ?: 0,
                rotational = read["/sys/block/$n/queue/rotational"]?.trim() == "1",
                isVirtual = VIRTUAL_BLOCK.containsMatchIn(n),
                mountedAt = mounts[n],
            )
        }.sortedWith(compareBy({ it.isVirtual }, { it.name }))
    }

    /**
     * `noop deadline [cfq]` -- the brackets mark the active one. A single
     * unbracketed token (`none` on this device's zram) is itself the answer.
     */
    private fun currentScheduler(raw: String): String {
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        tokens.firstOrNull { it.startsWith("[") }?.let { return it.trim('[', ']') }
        return tokens.singleOrNull().orEmpty()
    }

    /**
     * Which physical disk each mount actually lands on.
     *
     * /data is not mounted from a partition on this phone, it is dm-46 stacked on
     * sda35. dm's own `slaves` directory is empty here, so the link is found from
     * the other end: sda35/holders/ contains dm-46. Without this the I/O screen
     * shows a 236 GB disk with no mount point and a hidden dm entry that owns it.
     */
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
            // A disk carries many mounts. /data is the one that matters, then /.
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

    // ------------------------------------------------------- Thermal / vm

    private fun probeThermal(shell: RootShell): List<ThermalZone> {
        val dirs = shell.lines("ls -d /sys/class/thermal/thermal_zone* 2>/dev/null")
        if (dirs.isEmpty()) return emptyList()
        val read = shell.readAll(dirs.flatMap { listOf("$it/type", "$it/temp") })
        return dirs.mapNotNull { d ->
            val id = d.substringAfterLast("thermal_zone").toIntOrNull() ?: return@mapNotNull null
            ThermalZone(
                id = id,
                type = read["$d/type"]?.trim().orEmpty(),
                tempMilliC = read["$d/temp"]?.trim()?.toIntOrNull() ?: return@mapNotNull null,
            )
        }
    }

    private fun probeVm(shell: RootShell): Map<String, SysNode> {
        val names = shell.lines("ls /proc/sys/vm 2>/dev/null")
        if (names.isEmpty()) return emptyMap()
        return SysNode.probe(shell, names.map { "/proc/sys/vm/$it" })
            .mapKeys { it.key.substringAfterLast('/') }
    }

    /**
     * Input boost lives in one of two places depending on how the vendor built
     * the driver: a module parameter directory, or a device attribute group. The
     * development device has the second and not the first, which is precisely the
     * kind of thing a hardcoded path gets wrong on the next phone.
     */
    private fun probeBoost(shell: RootShell): Map<String, SysNode> {
        val dirs = listOf("/sys/module/cpu_boost/parameters", "$CPU/cpu_boost")
        val listings = listDirs(shell, dirs)
        val paths = listings.flatMap { (d, names) -> names.map { "$d/$it" } }
        return SysNode.probe(shell, paths).mapKeys { it.key.substringAfterLast('/') }
    }

    // --------------------------------------------------------------- Live

    /**
     * True when current_now is reported in milliamps rather than microamps.
     *
     * Latched on the first non-zero sample and never revisited: a heuristic that
     * can change its mind mid-session produces a graph with a 1000x step in it,
     * which is worse than being consistently wrong. The tell is a computed draw
     * below 5 mW while the screen is on and this app is in the foreground -- the
     * phone cannot actually be doing that.
     */
    @Volatile private var currentInMilliamps: Boolean? = null

    fun sampleLive(shell: RootShell, model: DeviceModel): LiveStats {
        val bat = model.battery?.path
        val paths = buildList {
            model.policies.forEach { add("${it.path}/scaling_cur_freq") }
            model.gpus.forEach { add("${it.path}/cur_freq") }
            add("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            add("/proc/uptime")
            if (bat != null) {
                addAll(listOf("current_now", "voltage_now", "capacity", "temp", "status").map { "$bat/$it" })
            }
            model.thermalZones.forEach { add("/sys/class/thermal/thermal_zone${it.id}/temp") }
        }
        val read = shell.readAll(paths)

        val uV = read["$bat/voltage_now"]?.trim()?.toLongOrNull()
        val rawCurrent = read["$bat/current_now"]?.trim()?.toLongOrNull()
        val mW = milliwatts(rawCurrent, uV)

        val zones = model.thermalZones.mapNotNull { z ->
            val t = read["/sys/class/thermal/thermal_zone${z.id}/temp"]?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            if (t <= -30_000) null else z.type to t / 1000f
        }

        return LiveStats(
            // Absent means absent. Defaulting an unreadable node to 0 renders as
            // "0.00 GHz" in the UI, which reads as a measurement rather than as a
            // gap, so the entry is simply left out of the map.
            policyCurFreq = model.policies.mapNotNull { p ->
                read["${p.path}/scaling_cur_freq"]?.trim()?.toLongOrNull()?.let { p.id to it }
            }.toMap(),
            gpuCurFreq = model.gpus.firstOrNull()
                ?.let { read["${it.path}/cur_freq"]?.trim()?.toLongOrNull() } ?: 0,
            // "3 %" -- the value arrives with a space and a unit attached.
            gpuBusyPercent = read["/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"]
                ?.filter { it.isDigit() }?.toIntOrNull(),
            batteryMilliwatts = mW,
            batteryPercent = read["$bat/capacity"]?.trim()?.toIntOrNull(),
            batteryTempC = read["$bat/temp"]?.trim()?.toIntOrNull()?.let { it / 10f },
            // Direction comes from `status`, never from the sign of current_now.
            // Vendors disagree on the sign convention -- this device reports
            // +423827 uA while charging, others report negative for the same thing
            // -- but every one of them fills in `status` correctly.
            charging = read["$bat/status"]?.trim()?.startsWith("Charging", true) == true,
            hottestZone = zones.maxByOrNull { it.second },
            uptimeSeconds = read["/proc/uptime"]?.trim()?.substringBefore('.')?.toLongOrNull() ?: 0,
        )
    }

    private fun milliwatts(rawCurrent: Long?, uV: Long?): Int? {
        if (rawCurrent == null || uV == null || uV <= 0) return null
        if (rawCurrent == 0L) return 0
        val asMicroamps = abs(rawCurrent).toDouble() * uV / 1_000_000_000.0
        val milliamps = currentInMilliamps ?: (asMicroamps < 5.0).also { currentInMilliamps = it }
        return (if (milliamps) asMicroamps * 1000 else asMicroamps).roundToInt()
    }

    /** policy id -> (kHz -> jiffies). Cumulative since boot; diff two of these. */
    fun timeInState(shell: RootShell, policies: List<CpuPolicy>): Map<Int, Map<Long, Long>> {
        val read = shell.readAll(policies.map { "${it.path}/stats/time_in_state" })
        return policies.associate { p ->
            p.id to read["${p.path}/stats/time_in_state"].orEmpty().lineSequence()
                .mapNotNull { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size < 2) null else (f[0].toLongOrNull() ?: return@mapNotNull null) to
                        (f[1].toLongOrNull() ?: return@mapNotNull null)
                }.toMap()
        }
    }

    // ------------------------------------------------------------ Helpers

    private fun RootShell.lines(cmd: String): List<String> =
        exec(cmd).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun numbers(s: String?): List<Long> =
        s?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.distinct()?.sorted().orEmpty()

    private fun firstColumn(s: String?): List<Long> =
        s?.lineSequence()?.mapNotNull { it.trim().substringBefore(' ').toLongOrNull() }
            ?.distinct()?.sorted()?.toList().orEmpty()

    /** Lists many directories in one round trip. Missing directories yield nothing. */
    private fun listDirs(shell: RootShell, dirs: List<String>): Map<String, List<String>> {
        if (dirs.isEmpty()) return emptyMap()
        val script = dirs.joinToString("\n") { d ->
            "for f in '$d'/*; do [ -e \"\$f\" ] && printf '%s\\t%s\\n' '$d' \"\${f##*/}\"; done"
        }
        val map = HashMap<String, MutableList<String>>()
        for (line in shell.exec(script).lineSequence()) {
            val i = line.indexOf('\t')
            if (i <= 0) continue
            map.getOrPut(line.substring(0, i)) { mutableListOf() }.add(line.substring(i + 1))
        }
        return map
    }
}
