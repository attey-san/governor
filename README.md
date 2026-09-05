# Governor

A kernel manager for rooted Android that tells you whether anything actually happened.

<p align="center">
  <img src="docs/cpu.png" width="45%" alt="CPU clusters, discovered rather than hardcoded" />
  <img src="docs/capability.png" width="45%" alt="Capability report" />
</p>

Kernel managers often assume fixed CPU indices, frequency tables, and tunable names. Those
assumptions break across SoCs. They also tend to stop at writing a value, without checking
what the kernel accepted or measuring whether the change helped.

Governor discovers everything at runtime and reads back after every write.

## What makes it different

Apply a profile and it samples real battery draw — `current_now × voltage_now` — over a
fixed window, diffs per-cluster residency from `time_in_state`, and compares against a
stored baseline. A delta under 5% of baseline is reported as *"no measurable difference"*
rather than as a win, because sampling noise on a phone is easily that large.

Changes that can destabilize a phone — a governor swap, an offlined core, a frequency
ceiling — get a 30-second confirmation window. The rollback is owned by a detached root
process, so it still runs if Governor is closed or Android kills its process.

The capability report compares 79 known tunables with what the current kernel exposes,
including paths and read-only status.

## Scope

- **No one-tap "Optimize" preset.** Useful values depend on the device and workload.
- **No thermal writes.** Thermal is telemetry only. Vendor thermal control is a closed
  loop that overwrites manual changes, and bypassing it can leave the phone running hot.
- **No overclock claims.** `cpuinfo_max_freq` is a hard ceiling. Raising it needs a custom
  kernel, not an app.
- **No hardcoded "recommended" values.** Tunables measured to have no effect are labelled
  in the capability report.

## How the portability works

```
/sys/devices/system/cpu/cpufreq/policy*/related_cpus
```

Enumerating that gives cluster topology on any SoC — 4+4, 4+3+1, 2+4+2 — without fixed
core indices. Governor tunables are found by listing `policyN/<current governor>/`, so the
same probe works with schedutil, interactive, and vendor-specific governors. If a node is
absent, its control is disabled with the reason shown.

The same idea runs through the rest: the GPU is found by filtering the devfreq class (a
phone has around twenty devfreq devices and exactly one of them is the GPU); real disks are
separated from `dm-*`, `loop*` and `zram*`, and mount points are resolved through
`holders/` because `/data` is usually a device-mapper target stacked on a partition.

## Features

| | |
|---|---|
| **CPU** | Per-policy min/max/governor, auto-discovered governor tunables, per-core hotplug |
| **GPU** | Frequency range, governor, busy percentage where exposed |
| **Battery** | Live draw in mW, capacity against design, temperature |
| **Thermal** | Every zone, read-only, unpopulated sensors labelled as such |
| **I/O** | Scheduler, read-ahead and queue depth per real block device, virtual ones listed read-only |
| **Memory** | vm tunables, zram, and a per-CPU input-boost editor |
| **Profiles** | Named settings, applied by trigger, exportable and shareable as a Magisk module |
| **Measure** | A/B battery draw with residency breakdown |
| **Capability** | What this kernel exposes, out of what is known to exist |

A quick-settings tile cycles through saved profiles and applies each on tap, so a profile
can be changed from the pull-down without unlocking anything. Add it from the quick
settings edit screen.

### Triggers

Profiles apply themselves on unplug, plug in, screen off, screen on, battery below a
percentage, battery temperature above a threshold, or while a chosen app is open. The app
trigger restores whatever was in effect before you opened that app when you leave it.

Nothing is polled unless an app trigger exists, and then only while the screen is on.

### Boot persistence

Profiles export as a flashable Magisk/KernelSU module. The generated script waits **45
seconds** before writing: vendor init and the userspace thermal daemon start late and will
overwrite anything applied earlier, which is why modules that write at `post-fs-data` look
like they did nothing. The generated zip can be sent directly to a file manager or root
manager through Android's share sheet.

## Requirements

- Android 8.0 (API 26) or newer
- Root: Magisk, KernelSU or APatch
- Usage access, **only** if you want per-app profiles

Governor does not request network access.

## Building

```sh
git clone https://github.com/attey-san/governor
cd governor
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Needs a JDK 21 toolchain and an Android SDK with API 36 platform. If your default `java`
is newer than 21, point Gradle at a 21 in `~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk-21
```

`assembleRelease` produces an unsigned APK, which Android will not install as-is. Sign it
with your own key:

```sh
apksigner sign --ks your.keystore --out governor.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

Or just build and install the debug one, which is signed with the SDK's debug key.

## Notes from real hardware

Things that cost time, kept here so they cost nobody else any:

- **`[ -w path ]` lies.** It calls `access(2)`, which consults SELinux, and returns false
  for every cpufreq node even as root — nodes that are demonstrably writable. Writability
  comes from the mode bits via `stat`. Trusting `access(2)` disables every control in the
  app on a device where all of them work.
- **`adb shell su` is not the app's `su`.** The same `echo > scaling_max_freq` fails from an
  adb shell and succeeds from a rooted app: they get different SELinux contexts. Don't
  conclude a node is unwritable because a terminal couldn't write it.
- **Battery current has no portable sign convention.** Some devices report positive while
  charging, some negative. Direction comes from `status`; only magnitude comes from
  `current_now`. Units are microamps on nearly everything and milliamps on a few, so the
  unit guess latches on the first sample rather than flipping mid-session.
- **`cat` and `stat` fork.** Reading 505 sysfs nodes costs about 1350 ms through `cat` and
  about 45 ms through the shell's `read` builtin; 43 `stat` calls cost 780 ms separately
  and 29 ms batched. A full probe touches roughly nine hundred nodes.
- **A fuel gauge reporting `cycle_count` of 0 or 1 on a worn pack is not reporting.** It is
  shown as unreported rather than printed as fact.

## License

MIT. See [LICENSE](LICENSE).
