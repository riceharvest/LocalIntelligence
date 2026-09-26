#!/usr/bin/env bash
# Takes the on-device RAM measurement this project does not yet have.
#
# READ THIS FIRST. Every number this script prints is a MEASUREMENT. None of
# them exist yet, anywhere in this repository, because the emulator on the
# development host SIGSEGVs at boot (exit 139, after reaching bootanim with
# -verbose -show-kernel). Until someone runs this on hardware or a working
# emulator, every RAM figure the app shows a user is COMPUTED from the GGUF
# tensor table, not measured. That is documented in docs/memory-model.md.
#
# This script does not guess. If a step cannot run it says so and stops rather
# than emitting a number that did not come from the device.
#
# USAGE
#   adb devices                     # confirm exactly one device is attached
#   bash docs/measure/measure_ram.sh <package> [<gguf-on-host>]
#
#   package       default dev.localintelligence.app
#   gguf-on-host  a local .gguf to push and adopt. Optional: without it the
#                 script measures the idle baseline and the already-installed
#                 model, and tells you which it did.
#
# WHAT IT PRODUCES, in $OUTDIR (default /tmp/li-ram):
#   00-env.txt            device, ABI, kernel, RAM, the app's own view
#   01-idle-meminfo.txt   dumpsys meminfo with no model resident
#   01-idle-smaps.txt     /proc/<pid>/smaps_rollup, idle
#   02-loaded-meminfo.txt dumpsys meminfo with the model resident
#   02-loaded-smaps.txt   smaps_rollup, loaded
#   03-summary.txt        the deltas, and what each one does and does not mean
#
# THE ONE RULE: do not edit a number in here to make it look like something
# else. If a value is missing it stays missing.

set -euo pipefail

PKG="${1:-dev.localintelligence.app}"
GGUF="${2:-}"
OUTDIR="${OUTDIR:-/tmp/li-ram}"

say()  { printf '\n=== %s ===\n' "$*"; }
die()  { printf '\nSTOP: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight --
# Every one of these is a hard requirement. A RAM number taken on the wrong
# device or against a dead process is worse than no number at all, because it
# looks like data.
command -v adb >/dev/null 2>&1 || die "adb is not on PATH. Install Android platform-tools."

state="$(adb get-state 2>/dev/null || true)"
[ "$state" = "device" ] || die "no device is attached (adb get-state = '${state:-nothing}'). Do not run this against an emulator that is not fully booted."
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
  || die "sys.boot_completed is not 1. The device is still booting; a meminfo taken now measures the boot animation."

mkdir -p "$OUTDIR"

# --------------------------------------------------------------------- env --
say "environment"
{
  echo "package        : $PKG"
  echo "date (device)  : $(adb shell date -u 2>/dev/null | tr -d '\r')"
  echo "sdk            : $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "release        : $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "model          : $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "abi            : $(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
  echo "kernel         : $(adb shell uname -a 2>/dev/null | tr -d '\r')"
  echo "MemTotal       : $(adb shell grep MemTotal /proc/meminfo 2>/dev/null | tr -d '\r')"
  echo "MemAvailable   : $(adb shell grep MemAvailable /proc/meminfo 2>/dev/null | tr -d '\r')"
  # This is the *budget* side of the fit gate, read straight from the OS rather
  # than computed by RamEstimate.usableDeviceBytes(). Comparing the two is how
  # you find out whether AndroidDeviceBudget.USABLE_FRACTION (0.55) is honest.
  echo "lowmemorykiller: $(adb shell getprop ro.lowmemorykiller.minfree 2>/dev/null | tr -d '\r')"
  echo "lmkd props      : $(adb shell getprop | grep -i lmkd 2>/dev/null | tr -d '\r' || echo '(none)')"
} | tee "$OUTDIR/00-env.txt"

ABI="$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"

# -------------------------------------------------------------- the helper --
# smaps_rollup is the single most useful file in this whole procedure: it
# separates file-backed pages (the mmap'd weights) from anonymous pages (the KV
# cache, the compute graph, the JVM heap). It is what settles whether mmap is
# actually working, which is the difference between a 640 MB model costing
# ~0 extra RSS and costing 640 MB.
snap() {
  local tag="$1"
  local pid
  pid="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  [ -n "$pid" ] || die "the app ($PKG) is not running, so there is nothing to measure. Start it and retry."
  say "snap: $tag (pid $pid)"
  sleep "${SETTLE:-12}"   # let the allocator settle; a decode in flight skews everything
  adb shell dumpsys meminfo "$PKG" > "$OUTDIR/$tag-meminfo.txt" 2>/dev/null \
    || die "dumpsys meminfo $PKG failed"
  adb shell cat "/proc/$pid/smaps_rollup" > "$OUTDIR/$tag-smaps.txt" 2>/dev/null \
    || printf '(smaps_rollup unreadable on this kernel; the file-backed split cannot be separated)\n' \
       > "$OUTDIR/$tag-smaps.txt"
  adb shell cat "/proc/$pid/status" 2>/dev/null \
    | grep -E 'VmRSS|VmHWM|VmSwap' > "$OUTDIR/$tag-vm.txt" || true
  cat "$OUTDIR/$tag-vm.txt" 2>/dev/null || true
}

# Pull the four fields that matter, in MiB, from a meminfo dump. TOTAL PSS is
# the app's real footprint including native memory; "Native Heap" is only the
# malloc arena, so a model in native memory does NOT show up there and reading
# only that field is how a 2 GB model looks like it cost 12 MB.
field() {
  awk -v k="$2" '
    $1 == "TOTAL"            { if (k=="pss")  print $2/1024 }
    /Native Heap/            { if (k=="nat")  print $2/1024 }
    /Dalvik Heap/            { if (k=="dal")  print $2/1024 }
    /^ +Other:/              { if (k=="oth")  print $2/1024 }
  ' "$1" 2>/dev/null | head -1
}

# ------------------------------------------------------------------ phase 1 --
say "phase 1: baseline, no model resident"
adb shell am force-stop "$PKG" || true
adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 \
  || adb shell am start -n "$PKG/com.dev.localintelligence.app.MainActivity" >/dev/null 2>&1 \
  || die "could not start $PKG. Launch it by hand and re-run."
snap 01-idle

# ------------------------------------------------------------------ phase 2 --
if [ -n "$GGUF" ]; then
  [ -f "$GGUF" ] || die "no such file: $GGUF"
  say "phase 2: adopt $(basename "$GGUF")"
  # The model has to live in the app's own storage, not /data/local/tmp: the
  # app reads it through a SAF descriptor and /proc/self/fd, and the app's
  # "Scan storage" button globs filesDir/models/*.gguf.
  adb shell "run-as $PKG mkdir -p files/models" 2>/dev/null \
    || printf '(run-as unavailable; this is normal on a release build - use the app Scan storage button and a file you pushed to /sdcard)\n'
  adb push "$GGUF" "/data/local/tmp/$(basename "$GGUF")" >/dev/null \
    || die "adb push failed"
  cat <<EOF

  ------------------------------------------------------------------
  MANUAL STEP, and it cannot be automated away:

    In the app: open the model manager, import/scan, and select
    $(basename "$GGUF").

  Then come back and press Enter. Waiting here is the point: the
  measurement has to be taken AFTER llama.cpp has actually allocated its
  context, not merely after the file was mapped.
  ------------------------------------------------------------------

EOF
  read -r _ || die "no interactive input; re-run with a TTY"
  snap 02-loaded
else
  say "phase 2: SKIPPED - no .gguf supplied"
  say "the idle baseline was measured; the loaded state was not"
fi

# ------------------------------------------------------------------ summary --
say "summary -> $OUTDIR/03-summary.txt"
{
  echo "MEASURED ON DEVICE. Taken $(date -u +%Y-%m-%dT%H:%M:%SZ) UTC."
  echo "Device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r') (sdk $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'), $ABI)"
  echo
  printf '%-28s %12s %12s %12s\n' "MiB" "idle" "loaded" "delta"
  if [ -f "$OUTDIR/02-loaded-meminfo.txt" ]; then
    for kv in pss:total_pss nat:native_heap dal:dalvik_heap oth:other; do
      k="${kv%%:*}"; name="${kv##*:}"
      a="$(field "$OUTDIR/01-idle-meminfo.txt" "$k")"
      b="$(field "$OUTDIR/02-loaded-meminfo.txt" "$k")"
      d="$(awk -v x="$a" -v y="$b" 'BEGIN{printf "%.1f", y-x}')"
      printf '%-28s %12s %12s %12s\n' "$name" "${a:-?}" "${b:-?}" "$d"
    done
  else
    echo "  (no loaded-state measurement: phase 2 was skipped)"
  fi
  echo
  echo "--- file-backed vs anonymous (the mmap verdict) ---"
  for f in "$OUTDIR"/0*-smaps.txt; do
    [ -f "$f" ] || continue
    echo "[$f]"
    grep -E '^(Rss|Pss|Private_Dirty|Private_Clean|Shared_Clean|Shared_Dirty|Anonymous):' "$f" 2>/dev/null || true
  done
  echo
  echo "--- what these numbers do and do not mean ---"
  cat <<'EOF'
  * TOTAL PSS delta = the model's marginal cost. It is the only part of the
    number the fit model in core/hub/FitGate.kt has to predict, and the only
    part that transfers between devices.
  * A large Private_Clean / Shared_Clean in smaps_rollup with a small
    Private_Dirty delta means mmap is working: the weights are file-backed
    pages, reclaimable under pressure, and cost ~0 extra anonymous RAM.
  * A large Private_Dirty delta with no file-backed growth means the weights
    were READ INTO RAM, not mapped. On a FUSE-backed SAF provider (most cloud
    drives) that is expected; LoadedModel.isMmapCapable cannot detect it in
    advance, and the app has no read-into-RAM fallback.
  * Nothing here says anything about the emulator. An x86_64 AVD has no GPU,
    a different allocator and a different page cache, and CPU-bound decode
    there has been measured at ~0.66 tok/s, which is an artefact of emulated
    CPU and must never be quoted as a device number.
  * TOKENS PER SECOND MUST NOT BE COMPARED ACROSS DEVICES from this script.
    This measures RAM only.
EOF
} | tee "$OUTDIR/03-summary.txt"

say "done"
cat <<EOF

  Wrote $OUTDIR. Attach every file in that directory to the PR or the issue.

  If a value above printed "?" the corresponding field is absent from that
  dumpsys output - which is itself a finding, not a gap to paper over.
EOF
