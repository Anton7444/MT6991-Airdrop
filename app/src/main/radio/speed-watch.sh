#!/system/bin/sh
# Transmit-rate controller for the AirDrop radio.
#
# The radio transmits at one fixed rate (vendor command 3); it has no rate
# adaptation of its own. A single fast rate is quick at arm's length and
# collapses to a trickle a few metres away, so this watcher measures what
# actually gets through (awdl0 byte counters) and walks a small rate ladder:
# it steps down when a send collapses, keeps the step only if throughput
# really improved, and probes back up while the link is healthy.
#
# While receiving, our own frames are only acknowledgements. They start on the
# slow discovery rate, which is known to work, and move to a faster one only if
# the incoming stream measurably speeds up.
#
# Create /data/adb/owly/rate-fixed to get the previous behaviour back: one
# fixed fast rate whenever data is moving, the discovery rate otherwise.
MODDIR=${1:-/data/adb/o13}
S=/data/adb/owly
STATE=$S/stack
LOG=$S/daemon.log
OUT=$STATE/speed-watch.log
NET=/sys/class/net
SEND_MARKER=/data/user/0/com.owly.android/files/fast-send
PIDFILE="$STATE/speed-watch.pid"
PIN=$S/rate-fixed
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

# Leave the cgroups of whatever started us. Started from the app's root shell we
# would otherwise be frozen, throttled or killed together with the app.
for f in /sys/fs/cgroup/cgroup.procs /acct/cgroup.procs /dev/cg2_bpf/cgroup.procs \
    /dev/cpuset/cgroup.procs /dev/cpuctl/cgroup.procs /dev/stune/cgroup.procs /dev/blkio/cgroup.procs; do
    [ -w "$f" ] && echo $$ > "$f" 2>/dev/null
done
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null

note() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$OUT"; }

# preamble guard-interval mcs streams, slowest and most robust first.
# Only the last entry and DISCOVER were exercised before this controller existed.
rate_of() {
    case "$1" in
        0) echo "2 0 2 1" ;;
        1) echo "2 0 4 1" ;;
        2) echo "2 0 7 1" ;;
        3) echo "2 0 4 2" ;;
        4) echo "2 0 7 2" ;;
        *) echo "2 0 9 2" ;;
    esac
}
TOP=5
SAFE_IDX=1
DISCOVER="2 2 0 1"
# Acknowledgement rates tried while receiving, in order.
recv_rate_of() {
    case "$1" in
        0) echo "$DISCOVER" ;;
        1) rate_of 1 ;;
        *) rate_of 4 ;;
    esac
}
RECV_TOP=2

WINDOW=3            # seconds of traffic per measurement
SETTLE=1            # seconds ignored after a rate change
COLLAPSE=300000     # bytes/s: below this a send is considered collapsed
TRICKLE=20000       # bytes/s: above this something is trying to get through
PROBE_GAP=15        # seconds before the first attempt at a faster rate
PROBE_GAP_MAX=120
HOLD=30             # seconds to leave the rate alone after a step down that did not help
REMEMBER=180        # seconds a settled rate is reused by the next send

# Counters outgrow the shell's 32-bit arithmetic; nine digits are enough for a delta.
low9() {
    v=000000000$1
    echo "${v#"${v%?????????}"}"
}
delta9() {
    d=$(( 1$1 - 1$2 ))
    [ "$d" -lt 0 ] && d=$((d + 1000000000))
    echo "$d"
}
counter() {
    v=$(cat "$NET/awdl0/statistics/$1" 2>/dev/null)
    case "$v" in ''|*[!0-9]*) v=0 ;; esac
    echo "$v"
}

idx=$TOP
mode=discover
applied=""
session=""
session_ok=""
wanted=""
prev_tx=""
prev_rx=""
send_until=0
recv_until=0
busy_until=0
tick=0
win_n=0
win_sum=0
settle=0
trial=""
trial_from=$TOP
ref=0
best=0
hold_until=0
probe_gap=$PROBE_GAP
probe_at=0
saved_idx=$TOP
saved_at=0
ridx=0
rtrial=""
rref=0
rdone=""
rwin_n=0
rwin_sum=0
rsettle=0
rsaved_idx=0
rsaved_at=0

reset_window() { win_n=0; win_sum=0; settle=$SETTLE; }

apply_rate() {
    # $1 = "pre gi mcs nss", $2 = label
    key="$session|$1"
    [ "$key" = "$applied" ] && return 0
    index=$(cat "$NET/wonder0/ifindex" 2>/dev/null)
    [ -n "$index" ] && [ "$index" = "${session%%:*}" ] || return 1
    set -- $1 "$2"
    if "$MODDIR/bin/speed-vcmd" wonder0 3 "$1" "$2" "$3" "$4" >> "$OUT" 2>&1; then
        applied="$key"
        note "applied $5 rate (preamble=$1 gi=$2 mcs=$3 nss=$4) for interface $index"
        return 0
    fi
    return 1
}

start_send() {
    if [ $((t - saved_at)) -lt "$REMEMBER" ]; then idx=$saved_idx; else idx=$TOP; fi
    [ -f "$PIN" ] && idx=$TOP
    trial=""; best=0; hold_until=0
    probe_gap=$PROBE_GAP; probe_at=$((t + PROBE_GAP))
    reset_window
}

end_send() {
    [ -n "$trial" ] && idx=$trial_from
    trial=""
    saved_idx=$idx; saved_at=$t
}

try_step() {
    # $1 = new index, $2 = up|down, $3 = goodput that prompted it
    trial=$2; trial_from=$idx; ref=$3; idx=$1
    note "trying $2 to step $idx ($(rate_of "$idx")) from step $trial_from at $((ref / 1000)) kB/s"
    reset_window
}

start_receive() {
    # A verdict from a moment ago still holds; otherwise begin on the proven rate and probe once.
    if [ $((t - rsaved_at)) -lt "$REMEMBER" ]; then ridx=$rsaved_idx; rdone=1; else ridx=0; rdone=""; fi
    rtrial=""; rwin_n=0; rwin_sum=0; rsettle=$SETTLE
}

end_receive() {
    [ -n "$rtrial" ] && ridx=$((ridx - 1))
    rtrial=""
    rsaved_idx=$ridx; rsaved_at=$t
}

# One finished measurement window of incoming data at the current acknowledgement rate.
judge_receive() {
    g=$1
    if [ -n "$rtrial" ]; then
        rtrial=""
        if [ "$g" -gt $((rref / 100 * 110)) ]; then
            note "acknowledgement step $ridx is faster: $((g / 1000)) kB/s in (was $((rref / 1000)))"
        else
            ridx=$((ridx - 1)); rdone=1
            rwin_n=0; rwin_sum=0; rsettle=$SETTLE
        fi
    elif [ -z "$rdone" ] && [ "$ridx" -lt "$RECV_TOP" ] && [ "$g" -gt "$TRICKLE" ]; then
        rtrial=up; rref=$g; ridx=$((ridx + 1))
        rwin_n=0; rwin_sum=0; rsettle=$SETTLE
    fi
}

# One finished measurement window at the current rate.
judge() {
    g=$1
    case "$trial" in
        down)
            trial=""
            if [ "$g" -gt $((ref / 100 * 115)) ] && [ "$g" -gt "$TRICKLE" ]; then
                note "step $idx is better: $((g / 1000)) kB/s (was $((ref / 1000)))"
                best=$g; probe_gap=$PROBE_GAP; probe_at=$((t + PROBE_GAP))
            else
                note "step $idx did not help ($((g / 1000)) kB/s); back to step $trial_from"
                idx=$trial_from; hold_until=$((t + HOLD)); reset_window
            fi
            ;;
        up)
            trial=""
            if [ "$g" -gt $((ref / 100 * 110)) ]; then
                note "step $idx is faster: $((g / 1000)) kB/s (was $((ref / 1000)))"
                best=$g; probe_gap=$PROBE_GAP; probe_at=$((t + PROBE_GAP))
            else
                idx=$trial_from
                probe_gap=$((probe_gap * 2))
                [ "$probe_gap" -gt "$PROBE_GAP_MAX" ] && probe_gap=$PROBE_GAP_MAX
                probe_at=$((t + probe_gap)); reset_window
            fi
            ;;
        *)
            [ "$g" -gt "$best" ] && best=$g
            if [ "$idx" -gt 0 ] && [ "$t" -ge "$hold_until" ] &&
                { [ -n "$marker_fresh" ] || [ "$g" -gt "$TRICKLE" ]; }; then
                if [ "$g" -lt "$COLLAPSE" ]; then
                    if [ "$idx" -gt "$SAFE_IDX" ]; then try_step "$SAFE_IDX" down "$g"; else try_step 0 down "$g"; fi
                    return
                elif [ "$g" -lt $((best / 100 * 40)) ]; then
                    try_step $((idx - 1)) down "$g"
                    return
                fi
            fi
            if [ "$idx" -lt "$TOP" ] && [ "$t" -ge "$probe_at" ] && [ "$g" -ge "$COLLAPSE" ]; then
                try_step $((idx + 1)) up "$g"
            fi
            ;;
    esac
}

while :; do
    t=$(date +%s)
    tick=$((tick + 1))

    tx=$(counter tx_bytes); rx=$(counter rx_bytes)
    tx9=$(low9 "$tx"); rx9=$(low9 "$rx")
    tx_rate=0; rx_rate=0
    if [ -n "$prev_tx" ]; then
        tx_rate=$(delta9 "$tx9" "$prev_tx"); rx_rate=$(delta9 "$rx9" "$prev_rx")
        # A counter that restarted (new interface) is not traffic.
        [ "$tx_rate" -gt 400000000 ] && tx_rate=0
        [ "$rx_rate" -gt 400000000 ] && rx_rate=0
    fi
    prev_tx=$tx9; prev_rx=$rx9

    # The share app reads sent bytes from this setting; keep it fresh while busy.
    [ "$tx_rate" -gt 100000 ] && busy_until=$((t + 10))
    if [ "$t" -lt "$busy_until" ] || [ $((tick % 5)) -eq 0 ]; then
        settings put global airdrop_awdl_tx "$tx" 2>/dev/null
    fi

    index=$(cat "$NET/wonder0/ifindex" 2>/dev/null)
    if [ -z "$index" ]; then
        session=""; session_ok=""
    elif [ "${session%%:*}" != "$index" ] || [ -z "$session_ok" ] || [ $((tick % 5)) -eq 0 ]; then
        wanted=$(settings get system oshare_state 2>/dev/null)
        event=$(grep -a -e 'session up as' -e 'session down' "$LOG" 2>/dev/null | tail -1)
        session="$index:$event"
        case "$event" in *'session up as'*) session_ok=1 ;; *) session_ok="" ;; esac
    fi

    marker_fresh=""
    modified=$(stat -c %Y "$SEND_MARKER" 2>/dev/null)
    case "$modified" in
        ''|*[!0-9]*) ;;
        *) age=$((t - modified)); [ "$age" -ge 0 ] && [ "$age" -lt 30 ] && marker_fresh=1 ;;
    esac

    if [ "$rx_rate" -gt 50000 ] && [ "$rx_rate" -gt $((tx_rate * 3)) ]; then recv_until=$((t + 5)); fi
    if [ -n "$marker_fresh" ] || { [ "$tx_rate" -gt 100000 ] && [ "$t" -ge "$recv_until" ]; }; then
        send_until=$((t + 10))
    fi
    new=discover
    if [ -f "$PIN" ]; then
        if [ -n "$marker_fresh" ] || [ "$t" -lt "$busy_until" ]; then new=send; fi
    elif [ "$t" -lt "$send_until" ]; then new=send
    elif [ "$t" -lt "$recv_until" ]; then new=receive
    fi
    if [ "$new" != "$mode" ]; then
        [ "$mode" = send ] && end_send
        [ "$mode" = receive ] && end_receive
        [ "$new" = send ] && start_send
        [ "$new" = receive ] && start_receive
        mode=$new
    fi

    if [ "$wanted" = 1 ] && [ -n "$session_ok" ]; then
        case "$mode" in
            send)
                if [ -f "$PIN" ]; then
                    idx=$TOP; trial=""
                elif [ "$settle" -gt 0 ]; then
                    settle=$((settle - 1))
                else
                    win_sum=$((win_sum + tx_rate)); win_n=$((win_n + 1))
                    if [ "$win_n" -ge "$WINDOW" ]; then
                        goodput=$((win_sum / win_n))
                        win_n=0; win_sum=0
                        judge "$goodput"
                    fi
                fi
                apply_rate "$(rate_of "$idx")" send
                ;;
            receive)
                if [ "$rsettle" -gt 0 ]; then
                    rsettle=$((rsettle - 1))
                else
                    rwin_sum=$((rwin_sum + rx_rate)); rwin_n=$((rwin_n + 1))
                    if [ "$rwin_n" -ge "$WINDOW" ]; then
                        goodput=$((rwin_sum / rwin_n))
                        rwin_n=0; rwin_sum=0
                        judge_receive "$goodput"
                    fi
                fi
                apply_rate "$(recv_rate_of "$ridx")" receive
                ;;
            *) apply_rate "$DISCOVER" discover ;;
        esac
    fi

    if [ $((tick % 600)) -eq 0 ] && [ -f "$OUT" ] && [ "$(wc -c < "$OUT")" -gt 262144 ]; then
        tail -n 400 "$OUT" > "$OUT.tmp" 2>/dev/null && cat "$OUT.tmp" > "$OUT"
        rm -f "$OUT.tmp"
    fi
    sleep 1
done
