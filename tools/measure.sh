#!/usr/bin/env bash
# Measures the receiver on the Fire TV while someone mirrors an iPhone to it.
#
#   tools/measure.sh [seconds] [interval] [package]
#   defaults: 60 s, every 5 s, de.merzkevin.spiegel
#
# Prints PSS (dumpsys meminfo TOTAL, kB) and CPU (top, % of one core) every interval,
# then reads the log and prints the time from "Client connected" to the first rendered
# picture. The device must be connected: adb connect 192.168.8.11:5555
#
# Flow: start the script, then (within a few seconds) start screen mirroring on the
# iPhone. The script waits for the connection before it starts measuring.
set -euo pipefail

SECONDS_TOTAL="${1:-60}"
INTERVAL="${2:-5}"
PKG="${3:-de.merzkevin.spiegel}"
ADB="${ADB:-$(command -v adb || echo /opt/homebrew/bin/adb)}"
OLD_PKG="io.github.jqssun.airplay"
OUT_DIR="${OUT_DIR:-$(pwd)}"
STAMP="$(date +%Y%m%d-%H%M%S)"
CSV="$OUT_DIR/measure-$STAMP.csv"
LOG="$OUT_DIR/measure-$STAMP.log"

"$ADB" get-state >/dev/null

# two receivers on one stick would compete for the sender; stop the old one
"$ADB" shell am force-stop "$OLD_PKG" || true

pid="$("$ADB" shell pidof "$PKG" | tr -d '\r' || true)"
if [[ -z "$pid" ]]; then
  echo "starting $PKG"
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  pid="$("$ADB" shell pidof "$PKG" | tr -d '\r')"
fi
echo "pid $pid"

"$ADB" logcat -c
# stream the log to a file from now on: the Fire TV ring buffer is only 256 KB and
# overwrites our lines within a minute or two, so reading it afterwards is not enough
"$ADB" logcat -v epoch -s SpiegelService:I SpiegelVideo:I SpiegelNative:I > "$LOG" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT
echo "Now start screen mirroring on the iPhone. Waiting for the connection ..."
for _ in $(seq 1 120); do
  if grep -q "Client connected" "$LOG"; then break; fi
  sleep 1
done
if ! grep -q "Client connected" "$LOG"; then
  echo "no sender connected within 120 s" >&2
  exit 1
fi
echo "connected, measuring for ${SECONDS_TOTAL}s"

echo "t_s,pss_kb,cpu_pct,mirroring_started" > "$CSV"
start=$(date +%s)
while :; do
  now=$(date +%s)
  t=$((now - start))
  (( t > SECONDS_TOTAL )) && break
  pss=$("$ADB" shell dumpsys meminfo "$PKG" | awk '/TOTAL/ && !/TOTAL SWAP/ {print $2; exit}' | tr -d '\r')
  cpu=$("$ADB" shell top -b -n 1 -p "$pid" | awk -v p="$pid" '$1==p {print $9; exit}' | tr -d '\r')
  mirroring=$(grep -c "Mirroring started" "$LOG" || true)
  echo "$t,$pss,$cpu,$mirroring" | tee -a "$CSV"
  sleep "$INTERVAL"
done

sleep 1
kill "$LOGCAT_PID" 2>/dev/null || true

# first "Client connected" and first "First frame rendered" (epoch seconds.millis)
connected=$(awk '/Client connected/ {print $1; exit}' "$LOG")
mirror=$(awk '/Mirroring started/ {print $1; exit}' "$LOG")
first=$(awk '/First frame rendered/ {print $1; exit}' "$LOG")
echo
echo "log: $LOG"
echo "csv: $CSV"
if [[ -n "$connected" && -n "$first" ]]; then
  awk -v a="$connected" -v m="${mirror:-$connected}" -v b="$first" 'BEGIN {
    printf "Client connected -> first frame rendered: %d ms\n", (b - a) * 1000
    printf "Mirroring started -> first frame rendered: %d ms\n", (b - m) * 1000 }'
else
  echo "no first frame in the log (connected=$connected first=$first)"
fi
awk -F, 'NR>1 && $2!="" { n++; s+=$2; if ($2>max) max=$2; c+=$3; if ($3>cmax) cmax=$3 }
  END { if (n) printf "PSS avg %d kB, max %d kB; CPU avg %.0f%%, max %.0f%% (100%% = one core, %d samples)\n", s/n, max, c/n, cmax, n }' "$CSV"
