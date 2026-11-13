#!/system/bin/sh
set -u

MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)
KO="$MODDIR/kernel"
STATE=/data/adb/owly/stack
LOG=$STATE/radio.log
CHAN=${WONDER_CHANNEL:-149}
VENDOR=wlan_drv_gen4m_6991_6653
PRIV_OFFSET=0x9b40

mkdir -p "$STATE"
log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }
loaded() { grep -q "^$1 " /proc/modules 2>/dev/null; }

etc_is_intact() {
    [ "$(ls /system/etc 2>/dev/null | wc -l)" -ge 70 ] &&
        [ -e /system/etc/llndk.libraries.txt ]
}

preflight() {
    [ "$(id -u)" = 0 ] || { echo 'need root'; return 2; }
    etc_is_intact || { echo '/system/etc is not intact; refusing to load'; return 1; }
    case "$(getprop ro.product.model)" in
        OPD2508|PKT110|PLB110) ;;
        *) echo "unsupported model $(getprop ro.product.model)"; return 2 ;;
    esac
    loaded "$VENDOR" || { echo "$VENDOR is not loaded; start Wi-Fi once first"; return 3; }
}

load_shim() {
    [ "$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)" != 0 ] &&
        echo 0 > /proc/sys/kernel/kptr_restrict 2>/dev/null
    sym=$(awk -v m="[$VENDOR]" '$3=="dhd_wonder_priv" && $4==m {print $1; exit}' /proc/kallsyms 2>/dev/null)
    case "$sym" in
        ''|0000000000000000) sym="" ;;
        *) priv="0x$sym"
           insmod "$KO/mtk_wonder_dev.ko" priv_addr="$priv" 2>>"$LOG" || {
               log "shim load failed"; echo "shim failed"; return 4; }
           log "shim up, priv=$priv (dhd_wonder_priv from kallsyms)"
           return 0 ;;
    esac
    if [ "$(getprop ro.product.model)" != OPD2508 ]; then
        log "dhd_wonder_priv not visible in kallsyms; refusing to guess its address on $(getprop ro.product.model)"
        echo "gen4m priv symbol not found"
        return 3
    fi
    rodata=$(cat "/sys/module/$VENDOR/sections/.rodata" 2>/dev/null)
    case "$rodata" in
        0x0000000000000000|"")
            log "gen4m rodata unreadable; is $VENDOR loaded?"
            echo "gen4m not ready"
            return 3
            ;;
    esac
    hex=${rodata#0x}
    hi=${hex%????????}
    lo=${hex#????????}
    lo_hi=$(( 0x${lo%????} ))
    lo_lo=$(( 0x${lo#????} + PRIV_OFFSET ))
    if [ "$lo_lo" -gt 65535 ]; then
        lo_hi=$(( lo_hi + 1 ))
        lo_lo=$(( lo_lo - 65536 ))
    fi
    if [ "$lo_hi" -gt 65535 ]; then
        hi=$(printf '%x' $(( 0x$hi + 1 )))
        lo_hi=$(( lo_hi - 65536 ))
    fi
    priv="0x${hi}$(printf '%04x%04x' "$lo_hi" "$lo_lo")"

    insmod "$KO/mtk_wonder_dev.ko" priv_addr="$priv" 2>>"$LOG" || {
        log "shim load failed"
        echo "shim failed"
        return 4
    }
    log "shim up, priv=$priv"
    return 0
}

rxhandler_stamp() {
    dmesg 2>/dev/null \
        | grep 'Registered mac80211 RX handler with Vendor' \
        | tail -1 \
        | sed -n 's/^\[ *\([0-9][0-9]*\)\..*/\1/p'
}

prepare_memory() {
    sync
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
    echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
    sleep 2
}

try_up() {
    for i in wonder0 awdl0; do
        [ -e /sys/class/net/$i ] && ip link set "$i" down 2>/dev/null
        [ -e /sys/class/net/$i ] && iw dev "$i" del 2>/dev/null
    done

    if loaded wonder; then
        rmmod wonder 2>>"$LOG" || log "rmmod wonder failed"
        log "unloaded a previous wonder"
        sleep 1
    fi
    if loaded mtk_wonder_dev; then
        rmmod mtk_wonder_dev 2>>"$LOG"
        sleep 1
    fi

    loaded mtk_wonder_dev || load_shim || return $?

    if ! loaded wonder; then
        prepare_memory
        insmod "$KO/wonder.ko" 2>>"$LOG" || {
            log "wonder load failed"
            echo "wonder failed"
            return 5
        }
        log "wonder up"
    fi

    if [ ! -e /sys/class/ieee80211/wonder ]; then
        log "wonder module loaded without its PHY"
        return 12
    fi

    if [ ! -e /sys/class/net/wonder0 ]; then
        iw phy wonder interface add wonder0 type monitor 2>>"$LOG" || {
            log "wonder0 add failed"
            echo "wonder0 add failed"
            return 6
        }
    fi
    ip link set wonder0 up 2>>"$LOG" || {
        log "wonder0 up failed"
        echo "wonder0 up failed"
        return 7
    }
    iw dev wonder0 set channel "$CHAN" 2>>"$LOG"

    if [ ! -e /sys/class/net/awdl0 ]; then
        iw phy wonder interface add awdl0 type monitor 2>>"$LOG"
    fi
    ip link set awdl0 up 2>>"$LOG"
    iw dev awdl0 set channel "$CHAN" 2>>"$LOG"
    if ! ip -6 addr show awdl0 2>/dev/null | grep -q inet6; then
        ip -6 addr add fe80::1/64 dev awdl0 2>>"$LOG"
    fi

    if ! etc_is_intact; then
        log "/system/etc changed during load; unloading"
        rmmod wonder 2>/dev/null
        rmmod mtk_wonder_dev 2>/dev/null
        echo "/system/etc changed; radio unloaded"
        return 1
    fi

    log "radio up on channel $CHAN"

    after=$(rxhandler_stamp)
    if ! dmesg >/dev/null 2>&1; then
        log "dmesg unreadable; cannot verify the RX handler, accepting the bring-up"
    elif [ -z "$after" ] || [ "$after" -le "${rxhandler_before:-0}" ]; then
        log "RX handler not registered by this load (stamp ${rxhandler_before:-none} -> ${after:-none}); the radio would be deaf"
        echo "RX handler not registered"
        return 8
    fi

    if [ "$(cat /sys/class/net/wondertap0/operstate 2>/dev/null)" != "up" ]; then
        log "wondertap0 is $(cat /sys/class/net/wondertap0/operstate 2>/dev/null) after bring-up; the radio would be deaf"
        echo "wondertap0 is not up"
        return 9
    fi

    n=0
    for i in wonder0 awdl0; do
        [ -e /sys/class/net/$i ] && n=$((n + 1))
    done
    echo "radio up: $n interfaces on channel $CHAN"
    return 0
}

do_up() {
    preflight || return $?

    attempt=1
    while [ "$attempt" -le 3 ]; do
        rxhandler_before=$(rxhandler_stamp)
        log "bring-up attempt $attempt (rx handler stamp ${rxhandler_before:-none})"

        if try_up; then
            [ "$attempt" -gt 1 ] && log "bring-up succeeded on attempt $attempt"
            return 0
        else
            rc=$?
        fi
        log "attempt $attempt failed (rc=$rc)"
        attempt=$((attempt + 1))
        [ "$attempt" -le 3 ] && { sleep 3; do_down >/dev/null 2>&1; sleep 2; }
    done

    echo "bring-up failed after 3 attempts"
    return 1
}

do_down() {
    pkill -x owly 2>/dev/null
    sleep 1
    for i in wonder0 awdl0; do
        [ -e /sys/class/net/$i ] && ip link set "$i" down 2>/dev/null
        [ -e /sys/class/net/$i ] && iw dev "$i" del 2>/dev/null
    done
    loaded wonder && { rmmod wonder || return 10; }
    loaded mtk_wonder_dev && { rmmod mtk_wonder_dev || return 11; }
    log "stack down"
    echo "STACK_DOWN"
    return 0
}

case "${1:-status}" in
    up)   do_up; exit $? ;;
    down) do_down; exit $? ;;
    status)
        echo "model=$(getprop ro.product.model) kernel=$(uname -r) channel=$CHAN"
        for m in "$VENDOR" mac80211 cfg80211 wonder mtk_wonder_dev; do
            loaded "$m" && echo "$m=loaded" || echo "$m=absent"
        done
        for i in wonder0 awdl0 wondertap0; do
            [ -e /sys/class/net/$i ] && echo "net/$i=present" || echo "net/$i=absent"
        done
        echo "channel=$(iw dev wonder0 info 2>/dev/null | grep -o 'channel [0-9]*' | head -1)"
        ;;
    *) echo 'Usage: stack.sh up|down|status'; exit 2 ;;
esac
