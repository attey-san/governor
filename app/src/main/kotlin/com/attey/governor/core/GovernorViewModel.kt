package com.attey.governor.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GovernorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow<List<Capability>>(emptyList())
    val capabilities: StateFlow<List<Capability>> = _capabilities.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _triggers = MutableStateFlow<List<Trigger>>(emptyList())
    val triggers: StateFlow<List<Trigger>> = _triggers.asStateFlow()

    private val _measurement = MutableStateFlow<MeasureRun?>(null)
    val measurement: StateFlow<MeasureRun?> = _measurement.asStateFlow()

    private val _lastResult = MutableStateFlow<MeasureResult?>(null)
    val lastResult: StateFlow<MeasureResult?> = _lastResult.asStateFlow()

    private val _moduleExport = MutableStateFlow<String?>(null)
    val moduleExport: StateFlow<String?> = _moduleExport.asStateFlow()

    private val store = ProfileStore(app)
    private var baseline: MeasureRun? = null
    private var measureJob: Job? = null

    private var shell: RootShell? = null
    private var model: DeviceModel? = null
    private var liveJob: Job? = null
    private var revertJob: Job? = null

    init {
        _profiles.value = store.loadProfiles()
        _triggers.value = store.loadTriggers()
        refresh()
    }

    // ----------------------------------------------------------- Profiles

    fun saveCurrentAsProfile(name: String) {
        val m = model ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _profiles.value.any { it.name.equals(trimmed, true) }) return
        _profiles.value = _profiles.value + ProfileEngine.snapshot(m, trimmed)
        store.saveProfiles(_profiles.value)
    }

    fun applyProfile(name: String) {
        val sh = shell ?: return
        val m = model ?: return
        val profile = _profiles.value.firstOrNull { it.name == name } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val rejections = ProfileEngine.apply(sh, m, profile)
            reload(rejections.firstOrNull()?.let { "\"$name\": $it" })
        }
    }

    fun deleteProfile(name: String) {
        _profiles.value = _profiles.value.filterNot { it.name == name }
        store.saveProfiles(_profiles.value)
        // A trigger pointing at a profile that no longer exists would silently
        // never fire, so it goes with it.
        _triggers.value = _triggers.value.filterNot { it.profileName == name }
        store.saveTriggers(_triggers.value)
        TriggerService.sync(getApplication())
    }

    // ----------------------------------------------------------- Triggers

    fun addTrigger(type: TriggerType, threshold: Int, profileName: String) {
        if (_profiles.value.none { it.name == profileName }) return
        _triggers.value = _triggers.value +
            Trigger(System.currentTimeMillis(), type, threshold, profileName)
        store.saveTriggers(_triggers.value)
        TriggerService.sync(getApplication())
    }

    fun setTriggerEnabled(id: Long, enabled: Boolean) {
        _triggers.value = _triggers.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        store.saveTriggers(_triggers.value)
        TriggerService.sync(getApplication())
    }

    fun deleteTrigger(id: Long) {
        _triggers.value = _triggers.value.filterNot { it.id == id }
        store.saveTriggers(_triggers.value)
        TriggerService.sync(getApplication())
    }

    // -------------------------------------------------------- Measurement

    /**
     * One window. A null [profileName] measures the device as it stands and keeps
     * that as the baseline; a named profile is applied first and then compared
     * against it.
     *
     * Deliberately not a back-to-back double run: that doubles the wait and still
     * cannot control for what the phone was doing, and one stored baseline can be
     * compared against every profile in turn.
     */
    fun startMeasurement(profileName: String?, minutes: Int) {
        val sh = shell ?: return
        val m = model ?: return
        measureJob?.cancel()
        measureJob = viewModelScope.launch(Dispatchers.IO) {
            if (profileName != null) {
                _profiles.value.firstOrNull { it.name == profileName }
                    ?.let { ProfileEngine.apply(sh, m, it) }
            }
            val label = profileName ?: "baseline"
            val run = Measurement.window(sh, m, label, minutes * 60) { _measurement.value = it }
            _measurement.value = run
            if (profileName == null) {
                baseline = run
                _lastResult.value = null
            } else {
                baseline?.let { _lastResult.value = MeasureResult(it, run) }
            }
            reload(null)
        }
    }

    fun stopMeasurement() {
        measureJob?.cancel()
        _measurement.value = _measurement.value?.copy(running = false)
    }

    fun exportMagiskModule(profileName: String) {
        val m = model ?: return
        val profile = _profiles.value.firstOrNull { it.name == profileName } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _moduleExport.value = runCatching {
                MagiskModule.generate(getApplication(), m, profile).absolutePath
            }.getOrNull()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val sh = withContext(Dispatchers.IO) { RootShell.get() }
            if (sh == null) {
                _state.value = UiState.NoRoot(
                    "Root was refused or is not present. Governor reads and writes kernel " +
                        "nodes that are unreachable without it -- there is no degraded mode " +
                        "worth showing you."
                )
                return@launch
            }
            shell = sh
            val m = withContext(Dispatchers.IO) { DeviceProbe.probe(sh) }
            model = m
            val live = withContext(Dispatchers.IO) { DeviceProbe.sampleLive(sh, m) }
            _state.value = UiState.Ready(m, live)
            startLive()
            viewModelScope.launch(Dispatchers.IO) {
                _capabilities.value = Capabilities.report(sh, m)
            }
        }
    }

    private fun startLive() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                val sh = shell ?: break
                val m = model ?: break
                val previous = (_state.value as? UiState.Ready)?.live?.hottestZone
                val live = DeviceProbe.sampleLive(
                    shell = sh,
                    model = m,
                    // 93 zones is too much to re-read twice a second's worth of
                    // battery for a number that changes on the scale of minutes.
                    includeThermal = tick % 5 == 0,
                    previousHottest = previous,
                )
                val current = _state.value
                if (current is UiState.Ready) _state.value = current.copy(live = live)
                tick++
                delay(2_000)
            }
        }
    }

    // ------------------------------------------------------------- Writes

    fun setPolicyFreq(policyId: Int, min: Long, max: Long) {
        val p = model?.policies?.firstOrNull { it.id == policyId } ?: return
        // Widen before narrowing. The kernel clamps a min written above the
        // current max and a max written below the current min, and the clamp is
        // silent -- you get a value you did not ask for and no error.
        val ops = if (max >= p.scalingMax) {
            listOf(p.maxNode to max.toString(), p.minNode to min.toString())
        } else {
            listOf(p.minNode to min.toString(), p.maxNode to max.toString())
        }
        guarded("${label(p)} ${range(min, max)}", ops)
    }

    fun setPolicyGovernor(policyId: Int, governor: String) {
        val p = model?.policies?.firstOrNull { it.id == policyId } ?: return
        guarded("${label(p)} governor -> $governor", listOf(p.govNode to governor))
    }

    fun setCoreOnline(cpu: Int, online: Boolean) {
        // cpu0 is the boot CPU. Some kernels expose the node and then refuse the
        // write; some accept it and hang. Neither is worth finding out on a phone.
        if (cpu == 0) {
            reject("cpu0 cannot be taken offline")
            return
        }
        guarded(
            "cpu$cpu ${if (online) "online" else "offline"}",
            listOf("/sys/devices/system/cpu/cpu$cpu/online" to if (online) "1" else "0"),
        )
    }

    fun setGpuFreq(min: Long, max: Long) {
        val g = model?.gpus?.firstOrNull() ?: return
        val ops = if (max >= g.maxFreq) {
            listOf("${g.path}/max_freq" to max.toString(), "${g.path}/min_freq" to min.toString())
        } else {
            listOf("${g.path}/min_freq" to min.toString(), "${g.path}/max_freq" to max.toString())
        }
        guarded("GPU ${max / 1_000_000} MHz", ops)
    }

    fun setGpuGovernor(governor: String) {
        val g = model?.gpus?.firstOrNull() ?: return
        guarded("GPU governor -> $governor", listOf("${g.path}/governor" to governor))
    }

    fun setTunable(path: String, value: String) = direct(path, value)

    fun setScheduler(deviceName: String, scheduler: String) =
        direct("/sys/block/$deviceName/queue/scheduler", scheduler)

    fun setReadAhead(deviceName: String, kb: Long) =
        direct("/sys/block/$deviceName/queue/read_ahead_kb", kb.toString())

    fun setVmTunable(name: String, value: String) = direct("/proc/sys/vm/$name", value)

    /**
     * A write that cannot wedge the phone: a governor tunable, a queue depth, a
     * vm knob. Applied straight, with the read-back still reported if the kernel
     * declined it.
     */
    private fun direct(path: String, value: String) {
        val sh = shell ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = Writer.write(sh, path, value)
            reload(rejectionOf(path, result))
        }
    }

    /**
     * A write that could. Applied, then undone in [REVERT_SECONDS] unless the
     * user says to keep it.
     *
     * The countdown is the whole point. Offlining a core the scheduler is holding
     * a lock on, or moving to a governor the vendor never tested, can take a phone
     * down hard enough to need the power button held for ten seconds. This turns
     * that into a wait.
     */
    private fun guarded(description: String, ops: List<Pair<String, String>>) {
        val sh = shell ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val before = sh.readAll(ops.map { it.first })
            var rejection: String? = null
            val restore = LinkedHashMap<String, String>()
            for ((path, value) in ops) {
                before[path]?.trim()?.let { restore.putIfAbsent(path, it) }
                val result = Writer.write(sh, path, value)
                rejectionOf(path, result)?.let { rejection = it }
            }

            // A second change while one is already pending must not lose the
            // original values -- otherwise "undo" walks back one step and leaves
            // the phone in a state the user never chose. Oldest value wins.
            val pending = (_state.value as? UiState.Ready)?.pending
            val merged = LinkedHashMap(restore)
            pending?.restore?.forEach { (k, v) -> merged[k] = v }

            reload(rejection)
            val current = _state.value
            if (current is UiState.Ready) {
                _state.value = current.copy(
                    pending = PendingRevert(description, REVERT_SECONDS, merged),
                )
            }
            startCountdown()
        }
    }

    private fun startCountdown() {
        revertJob?.cancel()
        revertJob = viewModelScope.launch(Dispatchers.IO) {
            var left = REVERT_SECONDS
            while (left > 0 && isActive) {
                delay(1_000)
                left--
                val current = _state.value
                val pending = (current as? UiState.Ready)?.pending ?: return@launch
                _state.value = current.copy(pending = pending.copy(secondsLeft = left))
            }
            if (isActive) revertPending()
        }
    }

    fun confirmPending() {
        revertJob?.cancel()
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(pending = null)
    }

    fun revertPending() {
        revertJob?.cancel()
        val sh = shell ?: return
        val pending = (_state.value as? UiState.Ready)?.pending ?: return
        viewModelScope.launch(Dispatchers.IO) {
            pending.restore.forEach { (path, value) -> Writer.write(sh, path, value) }
            reload(null)
            val current = _state.value
            if (current is UiState.Ready) _state.value = current.copy(pending = null)
        }
    }

    fun dismissRejection() {
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(lastRejection = null)
    }

    // ------------------------------------------------------------ Plumbing

    /** Re-probes and republishes, preserving whatever revert is in flight. */
    private suspend fun reload(rejection: String?) {
        val sh = shell ?: return
        val m = DeviceProbe.probe(sh)
        model = m
        val previous = _state.value as? UiState.Ready
        _state.value = UiState.Ready(
            device = m,
            live = previous?.live ?: DeviceProbe.sampleLive(sh, m),
            pending = previous?.pending,
            lastRejection = rejection ?: previous?.lastRejection,
        )
    }

    private fun reject(message: String) {
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(lastRejection = message)
    }

    private fun rejectionOf(path: String, result: WriteResult): String? = when (result) {
        WriteResult.Ok -> null
        is WriteResult.Refused -> result.reason
        is WriteResult.Rejected ->
            "${path.substringAfterLast('/')}: asked for ${result.wanted}, kernel kept ${result.actual}"
    }

    private fun label(p: CpuPolicy): String {
        val m = model ?: return "policy${p.id}"
        val i = m.policies.indexOf(p)
        return m.clusterLabels.getOrNull(i) ?: "policy${p.id}"
    }

    private fun range(minKHz: Long, maxKHz: Long) = String.format(
        java.util.Locale.US, "%.2f\u2013%.2f GHz", minKHz / 1_000_000.0, maxKHz / 1_000_000.0,
    )

    override fun onCleared() {
        liveJob?.cancel()
        revertJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val REVERT_SECONDS = 30
    }
}
