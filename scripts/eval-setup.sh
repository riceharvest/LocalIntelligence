#!/usr/bin/env bash
# Prepares an emulator for scripts/run-evals.py --target device --mode simple.
#
# It grants the app the permissions the task list needs, allows the two special accesses the
# alarm and do-not-disturb tools check, and inserts the one test contact the message tasks use
# (Sam). It refuses to run on anything that is not an emulator: a phone keeps its own permissions,
# and a test contact must never end up in a real address book.
#
#   scripts/eval-setup.sh [-s serial]
#
# Environment: LI_PKG overrides the package (default dev.localintelligence.app).
# Run it once per emulator image; running it again is harmless.
set -euo pipefail

PKG="${LI_PKG:-dev.localintelligence.app}"
SERIAL=()
if [[ "${1:-}" == "-s" ]]; then
  SERIAL=(-s "$2")
fi

adb_sh() { adb "${SERIAL[@]}" shell "$@"; }

if [[ -z "${ANDROID_SERIAL:-}" && ${#SERIAL[@]} -eq 0 ]] && [[ $(adb devices | awk 'NR>1 && $2=="device"' | wc -l) -ne 1 ]]; then
  echo "error: attach exactly one device, or pass -s SERIAL" >&2
  exit 1
fi

# An emulator reports the qemu properties, or the ranchu/goldfish hardware name. Newer images leave
# ro.kernel.qemu empty, so any one of the three is enough, and none of them is a phone.
QEMU=$(adb_sh getprop ro.kernel.qemu | tr -d '\r')
BOOT_QEMU=$(adb_sh getprop ro.boot.qemu | tr -d '\r')
HW=$(adb_sh getprop ro.hardware | tr -d '\r')
if [[ "$QEMU" != "1" && "$BOOT_QEMU" != "1" && "$HW" != "ranchu" && "$HW" != "goldfish" ]]; then
  echo "error: the attached device is not an emulator (ro.hardware=$HW). Refusing: this script changes permissions and adds a contact." >&2
  exit 1
fi

if ! adb_sh pm path "$PKG" >/dev/null 2>&1; then
  echo "error: $PKG is not installed on this emulator. Install the debug build first (docs/build.md)." >&2
  exit 1
fi

# Runtime permissions: contacts (message tasks), calendar (the calendar tasks), coarse location
# (weather) and notifications (timers and alarms on Android 13 and later).
for perm in android.permission.READ_CONTACTS android.permission.READ_CALENDAR \
            android.permission.ACCESS_COARSE_LOCATION android.permission.POST_NOTIFICATIONS; do
  if adb_sh pm grant "$PKG" "$perm" >/dev/null 2>&1; then
    echo "granted $perm"
  else
    echo "could not grant $perm (the app may not declare it; check the manifest)" >&2
  fi
done

# Special accesses that a runtime grant does not cover. Without them the alarm tool and the
# do-not-disturb tool are refused, and the task list counts those refusals as failures.
adb_sh cmd appops set "$PKG" SCHEDULE_EXACT_ALARM allow && echo "allowed exact alarms"
adb_sh cmd notification allow_dnd "$PKG" && echo "allowed do-not-disturb access"

# One test contact, Sam. Added only when no contact named Sam exists yet.
SAM=$(adb_sh content query --uri content://com.android.contacts/data --projection data1 \
        --where "mimetype='vnd.android.cursor.item/name' AND data1='Sam'" || true)
if grep -q "Row:" <<<"$SAM"; then
  echo "test contact Sam is already there"
else
  adb_sh content insert --uri content://com.android.contacts/raw_contacts \
    --bind account_name:s: --bind account_type:s: >/dev/null
  # Captured first, not piped into head: an early exit would fail the pipeline under pipefail.
  RAWS=$(adb_sh content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC")
  RAW=$(sed -n 's/^Row: [0-9]* _id=\([0-9]*\).*/\1/p' <<<"$RAWS" | sed -n '1p')
  if [[ -z "$RAW" ]]; then
    echo "error: could not read the id of the new raw contact" >&2
    exit 1
  fi
  adb_sh content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:"$RAW" --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Sam >/dev/null
  adb_sh content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:"$RAW" --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
    --bind data1:s:+31600000000 --bind data2:i:2 >/dev/null
  echo "added test contact Sam"
fi

echo "ready. Next: scripts/run-evals.py --target device --model-id ... --mode simple (docs/evals.md)"
