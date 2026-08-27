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

    private var shell: RootShell? = null
    private var model: DeviceModel? = null
    private var liveJob: Job? = null
    private var revertJob: Job? = null

    init {
        refresh()
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
            while (isActive) {
                val sh = shell ?: break
                val m = model ?: break
                val live = DeviceProbe.sampleLive(sh, m)
                val current = _state.value
                if (current is UiState.Ready) _state.value = current.copy(live = live)
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
        guarded("${label(p)} ${fmt(min)}-${fmt(max)}", ops)
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

    private fun fmt(kHz: Long) = String.format("%.2f GHz", kHz / 1_000_000.0)

    override fun onCleared() {
        liveJob?.cancel()
        revertJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val REVERT_SECONDS = 30
    }
}
