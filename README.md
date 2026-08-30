# Governor

A kernel manager for rooted Android that tells you whether anything actually happened.

<p align="center">
  <img src="docs/cpu.png" width="45%" alt="CPU clusters, discovered rather than hardcoded" />
  <img src="docs/capability.png" width="45%" alt="Capability report" />
</p>

Every app in this category does the same two things wrong. They **hardcode paths** —
`cpu0`, `cpu4`, `cpu7`, fixed frequency tables, fixed tunable names — which is why they
break on each new SoC and why half the sliders on any given phone do nothing. And they
**never report back**: forty toggles, no measurement, no feedback, so the whole category
runs on folklore.

Governor discovers everything at runtime and reads back after every write.

## What makes it different

Apply a profile and it samples real battery draw — `current_now × voltage_now` — over a
fixed window, diffs per-cluster residency from `time_in_state`, and compares against a
stored baseline. A delta under 5% of baseline is reported as *"no measurable difference"*
rather than as a win, because sampling noise on a phone is easily that large.

Every change that could wedge a phone — a governor swap, an offlined core, a frequency
ceiling — is applied with a 30-second countdown. Don't confirm, and it goes back. Desktop
display settings have worked this way for twenty years.

And it will tell you what your kernel has: a browsable report of 79 known tunables against
what this one actually exposes, with paths, and read-only marked separately from writable.

## What it deliberately does not do

- **No one-tap "Optimize".** It does nothing and everyone knows it.
- **No thermal writes.** Thermal is exposed as telemetry only. The vendor's thermal
  governor is a working closed loop that re-parks any change within seconds; an app that
  offered those as sliders would be lying about what it can do, and a phone that lost that
  argument runs hot.
- **No overclock claims.** `cpuinfo_max_freq` is a hard ceiling. Raising it needs a custom
  kernel, not an app.
- **No hardcoded "recommended" values.** Where a tunable was measured to do nothing on real
  hardware, the capability report says so instead of presenting it as meaningful.

## How the portability works

```
/sys/devices/system/cpu/cpufreq/policy*/related_cpus
```

Enumerating that gives cluster topology on any SoC — 4+4, 4+3+1, 2+4+2, whatever ships
next — with nothing hardcoded. Governor tunables are found by listing
`policyN/<current governor>/`, so schedutil, interactive and anything future work the same
way. If a node is not there, the control is disabled with the reason shown, and the app
does not crash.

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
| **Profiles** | Named settings, applied by trigger, exportable as a Magisk module |
| **Measure** | A/B battery draw with residency breakdown |
| **Capability** | What this kernel exposes, out of what is known to exist |

A quick-settings tile cycles through saved profiles and applies each on tap, so a profile
can be changed from the pull-down without unlocking anything. Add it from the quick
settings edit screen.

### Triggers

Profiles apply themselves on unplug, plug in, screen off, screen on, battery below a
percentage, battery temperature above a threshold, or while a chosen app is open. The app
trigger restores whatever was in effect before you opened that app when you leave it, so a
game profile cannot quietly stay on all day.

Nothing is polled unless an app trigger exists, and then only while the screen is on.

### Boot persistence

Profiles export as a flashable Magisk/KernelSU module. The generated script waits **45
seconds** before writing: vendor init and the userspace thermal daemon start late and will
overwrite anything applied earlier, which is why modules that write at `post-fs-data` look
like they did nothing.

## Requirements

- Android 8.0 (API 26) or newer
- Root: Magisk, KernelSU or APatch
- Usage access, **only** if you want per-app profiles

## Building

```sh
git clone https://github.com/attey-san/governor
cd governor
./gradlew assembleDebug
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
