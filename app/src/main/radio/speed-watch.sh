#!/system/bin/sh
MODDIR=${1:-/data/adb/o13}
STATE=/data/adb/owly/stack
LOG=/data/adb/owly/daemon.log
SEND_MARKER=/data/user/0/com.owly.android/files/pad2-fast-send
PIDFILE="$STATE/speed-watch.pid"
mkdir -p "$STATE"
old=$(cat "$PIDFILE" 2>/dev/null)
if [ -n "$old" ] && tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q 'speed-watch.sh'; then
    started=$(stat -c %Y "/proc/$old" 2>/dev/null)
    mine=$(stat -c %Y "$0" 2>/dev/null)
    if [ -n "$started" ] && [ -n "$mine" ] && [ "$started" -lt "$mine" ]; then
        kill "$old" 2>/dev/null
        sleep 1
    else
        exit 0
    fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT
last=""
prev_tx=0
busy_until=0
while :; do
    tx=$(cat /sys/class/net/awdl0/statistics/tx_bytes 2>/dev/null)
    case "$tx" in ''|*[!0-9]*) tx=0 ;; esac
    if [ "$prev_tx" -gt 0 ] && [ $((tx - prev_tx)) -gt 100000 ]; then busy_until=$(( $(date +%s) + 10 )); fi
    prev_tx=$tx
    tick=$((${tick:-0} + 1))
    if [ "$(date +%s)" -lt "$busy_until" ] || [ $((tick % 5)) -eq 0 ]; then
        settings put global airdrop_awdl_tx "$tx" 2>/dev/null
    fi
    if [ "$(settings get system oshare_state 2>/dev/null)" = 1 ] && [ -r /sys/class/net/wonder0/ifindex ]; then
        event=$(grep -a -e 'session up as' -e 'session down' "$LOG" 2>/dev/null | tail -1)
        case "$event" in
            *'session up as'*)
                index=$(cat /sys/class/net/wonder0/ifindex 2>/dev/null)
                mode=discover
                modified=$(stat -c %Y "$SEND_MARKER" 2>/dev/null)
                case "$modified" in
                    ''|*[!0-9]*) ;;
                    *) age=$(( $(date +%s) - modified )); [ "$age" -ge 0 ] && [ "$age" -lt 30 ] && mode=send ;;
                esac
                [ "$(date +%s)" -lt "$busy_until" ] && mode=send
                key="$index:$event:$mode"
                if [ -n "$index" ] && [ "$key" != "$last" ]; then
                    if [ "$mode" = send ]; then pre=2; gi=0; mcs=9; nss=2; else pre=2; gi=2; mcs=0; nss=1; fi
                    if [ "$(cat /sys/class/net/wonder0/ifindex 2>/dev/null)" = "$index" ] &&
                       "$MODDIR/bin/speed-vcmd" wonder0 3 "$pre" "$gi" "$mcs" "$nss" >> "$STATE/speed-watch.log" 2>&1; then
                        last="$key"
                        echo "$(date) applied $mode rate (preamble=$pre gi=$gi mcs=$mcs nss=$nss) for interface $index" >> "$STATE/speed-watch.log"
                    fi
                fi
                ;;
        esac
    fi
    sleep 1
done
