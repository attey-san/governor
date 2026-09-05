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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

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

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _hasUsageAccess = MutableStateFlow(false)
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    private val store = ProfileStore(app)
    private var baseline: MeasureRun? = null
    private var measureJob: Job? = null

    private var shell: RootShell? = null
    private var model: DeviceModel? = null
    private var liveJob: Job? = null
    private var revertJob: Job? = null
    private var capabilityJob: Job? = null

    init {
        _profiles.value = store.loadProfiles()
        _triggers.value = store.loadTriggers()
        recheckUsageAccess()
        refresh()
    }

    /**
     * Saves what the device looked like the first time this app ran.
     *
     * Not "kernel defaults" -- by the time anyone installs this, a vendor daemon
     * has usually already moved things, and that is exactly the state worth being
     * able to get back to. Without it the only way back from a bad afternoon of
     * tuning is a reboot.
     */
    private fun captureAsFoundProfile(m: DeviceModel) {
        if (_profiles.value.any { it.name == AS_FOUND }) return
        val next = _profiles.value + ProfileEngine.snapshot(m, AS_FOUND)
        if (store.saveProfiles(next)) _profiles.value = next
        else reject("Could not save the automatic as-found restore point")
    }

    // --- Profiles

    fun saveCurrentAsProfile(name: String) {
        val m = model ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80 || trimmed.any(Char::isISOControl) ||
            _profiles.value.any { it.name.equals(trimmed, true) }
        ) return
        val next = _profiles.value + ProfileEngine.snapshot(m, trimmed)
        if (store.saveProfiles(next)) _profiles.value = next
        else reject("Could not save profile \"$trimmed\"")
    }

    fun applyProfile(name: String) {
        val sh = shell ?: return
        val m = model ?: return
        val profile = _profiles.value.firstOrNull { it.name == name } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val rejections = ProfileEngine.apply(sh, m, profile)
            val issue = when {
                rejections.isNotEmpty() -> "\"$name\": ${rejections.first()}"
                !store.saveActiveProfile(profile.name) ->
                    "\"$name\" applied, but its active marker could not be saved"
                else -> null
            }
            reload(issue)
        }
    }

    fun deleteProfile(name: String) {
        if (name == AS_FOUND) {
            reject("The as-found profile is the automatic restore point and cannot be deleted")
            return
        }
        val nextProfiles = _profiles.value.filterNot { it.name == name }
        // A trigger pointing at a profile that no longer exists would silently
        // never fire, so it goes with it.
        val nextTriggers = _triggers.value.filterNot { it.profileName == name }
        // Remove dependent triggers first. If the profile write then fails, the
        // safe failure is an unused profile, not a live trigger with no target.
        if (!store.saveTriggers(nextTriggers)) {
            reject("Could not update the profile's trigger list; nothing was deleted")
            return
        }
        _triggers.value = nextTriggers
        if (!store.saveProfiles(nextProfiles)) {
            reject("Its triggers were removed, but profile \"$name\" could not be deleted")
            TriggerService.sync(getApplication())
            return
        }
        _profiles.value = nextProfiles
        if (store.loadActiveProfile() == name && !store.clearActiveProfile()) {
            reject("Profile deleted, but its active marker could not be cleared")
        }
        TriggerService.sync(getApplication())
    }

    // --- Triggers

    fun addTrigger(
        type: TriggerType,
        threshold: Int,
        profileName: String,
        packageName: String = "",
        appLabel: String = "",
    ) {
        if (_profiles.value.none { it.name == profileName }) return
        if (type.needsApp && packageName.isEmpty()) return
        val validThreshold = when (type) {
            TriggerType.BATTERY_BELOW -> threshold in 1..100
            TriggerType.TEMP_ABOVE -> threshold in 0..120
            else -> true
        }
        if (!validThreshold) {
            reject("That trigger threshold is outside its valid range")
            return
        }
        val conflicts = _triggers.value.any { existing ->
            existing.type == type && when {
                type.needsApp -> existing.packageName == packageName
                type.needsThreshold -> existing.threshold == threshold
                else -> true
            }
        }
        if (conflicts) {
            reject("A trigger already exists for that condition")
            return
        }
        val next = _triggers.value +
            Trigger(
                id = nextTriggerId(),
                type = type,
                threshold = threshold,
                profileName = profileName,
                packageName = packageName,
                appLabel = appLabel,
            )
        if (!store.saveTriggers(next)) {
            reject("Could not save the trigger")
            return
        }
        _triggers.value = next
        TriggerService.sync(getApplication())
    }

    fun setTriggerEnabled(id: Long, enabled: Boolean) {
        val next = _triggers.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (!store.saveTriggers(next)) {
            reject("Could not update the trigger")
            return
        }
        _triggers.value = next
        TriggerService.sync(getApplication())
    }

    fun deleteTrigger(id: Long) {
        val next = _triggers.value.filterNot { it.id == id }
        if (!store.saveTriggers(next)) {
            reject("Could not delete the trigger")
            return
        }
        _triggers.value = next
        TriggerService.sync(getApplication())
    }

    /**
     * Re-reads the usage-access appop and the launcher list.
     *
     * The appop is granted in Settings, not in a dialog, so the only way to know
     * it happened is to look again when the user comes back.
     */
    fun recheckUsageAccess() {
        val app = getApplication<Application>()
        _hasUsageAccess.value = UsageAccess.hasAccess(app)
        // Returning from Usage Access settings is also how the service learns
        // that permission was revoked and restores an active app override.
        TriggerService.sync(app)
        if (_hasUsageAccess.value && _installedApps.value.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                _installedApps.value = UsageAccess.installedApps(app)
            }
        }
    }

    // --- Measurement

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
        val profile = profileName?.let { name ->
            _profiles.value.firstOrNull { it.name == name } ?: run {
                reject("Profile \"$name\" no longer exists")
                return
            }
        }
        if (profile != null && baseline == null) {
            reject("Measure a baseline before comparing a profile")
            return
        }
        measureJob?.cancel()
        _lastResult.value = null
        val durationSeconds = minutes.coerceIn(1, 60) * 60
        measureJob = viewModelScope.launch(Dispatchers.IO) {
            if (profile != null) {
                val rejections = ProfileEngine.apply(sh, m, profile)
                if (rejections.isNotEmpty()) {
                    _measurement.value = null
                    reload("\"${profile.name}\" was not measured: ${rejections.first()}")
                    return@launch
                }
            }
            val label = profileName ?: "baseline"
            _measurement.value = MeasureRun(label, true, 0, durationSeconds)
            val run = Measurement.window(sh, m, label, durationSeconds) { _measurement.value = it }
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
        measureJob = null
        _measurement.value = _measurement.value?.copy(running = false)
    }

    fun exportMagiskModule(profileName: String) {
        val m = model ?: return
        val profile = _profiles.value.firstOrNull { it.name == profileName } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MagiskModule.generate(getApplication(), m, profile).absolutePath }
                .onSuccess { _moduleExport.value = it }
                .onFailure {
                    _moduleExport.value = null
                    reject("Module not written: ${it.message ?: it::class.simpleName}")
                }
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
            // A probe walks roughly nine hundred nodes on hardware nobody here
            // has seen. One that throws inside viewModelScope takes the process
            // with it, on every launch, with no way for the user out of it.
            val m = withContext(Dispatchers.IO) { runCatching { DeviceProbe.probe(sh) } }
                .getOrElse {
                    _state.value = UiState.NoRoot(
                        "Probing this kernel failed: ${it.message ?: it::class.simpleName}. " +
                            "That is a bug in Governor, not in your phone."
                    )
                    return@launch
                }
            model = m
            val live = withContext(Dispatchers.IO) { DeviceProbe.sampleLive(sh, m) }
            _state.value = UiState.Ready(m, live)
            captureAsFoundProfile(m)
            startLive()
            capabilityJob?.cancel()
            capabilityJob = viewModelScope.launch(Dispatchers.IO) {
                _capabilities.value = Capabilities.report(sh, m)
            }
        }
    }

    /**
     * Live sampling follows the UI, not the ViewModel.
     *
     * [viewModelScope] lives until the activity is finished, so without this the
     * poll below carries on through the screen being off and the app sitting in
     * Recents: a root round trip over a hundred sysfs nodes every two seconds,
     * indefinitely, from the app that exists to find exactly that.
     */
    fun onUiStarted() {
        if (model != null) startLive()
    }

    fun onUiStopped() {
        liveJob?.cancel()
        liveJob = null
    }

    private fun startLive() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                val sh = shell ?: break
                val m = model ?: break
                val previousLive = (_state.value as? UiState.Ready)?.live
                val previous = previousLive?.hottestZone
                val live = DeviceProbe.sampleLive(
                    shell = sh,
                    model = m,
                    // 93 zones is too much to re-read twice a second's worth of
                    // battery for a number that changes on the scale of minutes.
                    includeThermal = tick % 5 == 0,
                    previousHottest = previous,
                )
                val current = _state.value
                // Carry the last full thermal read through the ticks that skip it,
                // otherwise the screen blanks four times out of five.
                val merged = if (live.zoneTemps.isEmpty() && previousLive != null)
                    live.copy(zoneTemps = previousLive.zoneTemps) else live
                if (current is UiState.Ready) _state.value = current.copy(live = merged)
                tick++
                delay(2_000)
            }
        }
    }

    // --- Writes

    fun setPolicyFreq(policyId: Int, min: Long, max: Long) {
        val p = model?.policies?.firstOrNull { it.id == policyId } ?: return
        val ops = ProfileEngine.frequencyWindowWrites(p.minNode, p.maxNode, min, max)
        guarded("${label(p)} ${range(min, max)}", ops)
    }

    fun setPolicyGovernor(policyId: Int, governor: String) {
        val p = model?.policies?.firstOrNull { it.id == policyId } ?: return
        guarded("${label(p)} governor \u2192 $governor", listOf(p.govNode to governor))
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

    fun setGpuFreq(path: String, min: Long, max: Long) {
        val g = model?.gpus?.firstOrNull { it.path == path } ?: return
        val ops = ProfileEngine.frequencyWindowWrites(
            "${g.path}/min_freq", "${g.path}/max_freq", min, max
        )
        guarded("GPU ${max / 1_000_000} MHz", ops)
    }

    fun setGpuGovernor(path: String, governor: String) {
        val g = model?.gpus?.firstOrNull { it.path == path } ?: return
        guarded("GPU governor \u2192 $governor", listOf("${g.path}/governor" to governor))
    }

    fun setTunable(path: String, value: String) = direct(path, value)

    fun setScheduler(deviceName: String, scheduler: String) =
        direct("/sys/block/$deviceName/queue/scheduler", scheduler)

    fun setReadAhead(deviceName: String, kb: Long) =
        direct("/sys/block/$deviceName/queue/read_ahead_kb", kb.toString())

    fun setNrRequests(deviceName: String, requests: Long) =
        direct("/sys/block/$deviceName/queue/nr_requests", requests.toString())

    fun setVmTunable(name: String, value: String) = direct("/proc/sys/vm/$name", value)

    /**
     * A write that cannot wedge the phone: a governor tunable, a queue depth, a
     * vm knob. Applied straight, with the read-back still reported if the kernel
     * declined it.
     */
    private fun direct(path: String, value: String) {
        val sh = shell ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = kernelWriteMutex.withLock { Writer.write(sh, path, value) }
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
            kernelWriteMutex.withLock {
                // A second change while one is already pending must not lose the
                // original values -- otherwise "undo" walks back one step and leaves
                // the phone in a state the user never chose. Oldest value wins.
                val pending = (_state.value as? UiState.Ready)?.pending
                val before = sh.readAll(ops.map { it.first }.distinct())
                val merged = LinkedHashMap<String, String>()
                pending?.restore?.forEach { (path, value) -> merged[path] = value }
                for ((path) in ops) before[path]?.trim()?.let { merged.putIfAbsent(path, it) }

                // Arm first. There must be no interval in which a risky write exists
                // but only the app process knows how to put it back.
                val guard = RollbackGuard.arm(sh, merged, REVERT_SECONDS)
                if (guard == null) {
                    reject("Could not arm the automatic rollback; nothing was changed")
                    return@launch
                }
                if (pending != null && !RollbackGuard.disarm(sh, pending.guardToken)) {
                    RollbackGuard.disarm(sh, guard)
                    reject("The previous rollback had already started; nothing else was changed")
                    return@launch
                }

                val rejected = LinkedHashMap<String, String>()
                for ((path, value) in ops) {
                    val result = Writer.write(sh, path, value)
                    val message = rejectionOf(path, result)
                    if (message == null) rejected.remove(path) else rejected[path] = message
                }

                reload(rejected.values.firstOrNull())
                val current = _state.value
                if (current is UiState.Ready) {
                    _state.value = current.copy(
                        pending = PendingRevert(description, REVERT_SECONDS, merged, guard),
                    )
                }
                startCountdown()
            }
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
        val sh = shell ?: return
        val pending = (_state.value as? UiState.Ready)?.pending ?: return
        viewModelScope.launch(Dispatchers.IO) {
            kernelWriteMutex.withLock {
                val currentPending = (_state.value as? UiState.Ready)?.pending
                if (currentPending?.guardToken != pending.guardToken) return@withLock
                if (RollbackGuard.disarm(sh, pending.guardToken)) {
                    val current = _state.value
                    if (current is UiState.Ready &&
                        current.pending?.guardToken == pending.guardToken
                    ) {
                        _state.value = current.copy(pending = null)
                    }
                } else {
                    delay(250)
                    reload("The rollback had already started; the change was not kept")
                    val current = _state.value
                    if (current is UiState.Ready &&
                        current.pending?.guardToken == pending.guardToken
                    ) {
                        _state.value = current.copy(pending = null)
                    }
                }
            }
        }
    }

    fun revertPending() {
        revertJob?.cancel()
        val sh = shell ?: return
        val pending = (_state.value as? UiState.Ready)?.pending ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val rejected = LinkedHashMap<String, String>()
            kernelWriteMutex.withLock {
                val currentPending = (_state.value as? UiState.Ready)?.pending
                if (currentPending?.guardToken != pending.guardToken) return@withLock
                ProfileEngine.restorationWrites(pending.restore)
                    .forEach { (path, value) ->
                        val message = rejectionOf(path, Writer.write(sh, path, value))
                        if (message == null) rejected.remove(path) else rejected[path] = message
                    }
                // If manual restoration was incomplete, leave the independent
                // guard alive so it gets one more attempt at the original deadline.
                if (rejected.isEmpty()) RollbackGuard.disarm(sh, pending.guardToken)
            }
            reload(
                rejected.values.firstOrNull()?.let {
                    "Manual rollback was incomplete; the root guard will retry: $it"
                }
            )
            val current = _state.value
            if (current is UiState.Ready && current.pending?.guardToken == pending.guardToken) {
                _state.value = current.copy(pending = null)
            }
        }
    }

    fun dismissRejection() {
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(lastRejection = null)
    }

    // --- Plumbing

    /** Re-probes and republishes, preserving whatever revert is in flight. */
    private suspend fun reload(rejection: String?) {
        val sh = shell ?: return
        // Keep the last good model rather than crashing out of a write's
        // coroutine: the write already happened either way.
        val m = runCatching { DeviceProbe.probe(sh) }.getOrNull() ?: model ?: return
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
        Locale.US, "%.2f\u2013%.2f GHz", minKHz / 1_000_000.0, maxKHz / 1_000_000.0,
    )

    override fun onCleared() {
        liveJob?.cancel()
        revertJob?.cancel()
        capabilityJob?.cancel()
        super.onCleared()
    }

    /** Unique even for two adds inside the same millisecond. */
    private fun nextTriggerId(): Long {
        val now = System.currentTimeMillis()
        val taken = _triggers.value.map { it.id }.toSet()
        var id = now
        while (id in taken) id++
        return id
    }

    private companion object {
        const val REVERT_SECONDS = 30
        const val AS_FOUND = "as found"
    }
}
