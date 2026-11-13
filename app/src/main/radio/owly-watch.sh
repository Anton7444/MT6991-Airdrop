#!/system/bin/sh

MODDIR=""
for candidate in "${0%/*}/.." /data/adb/o13 /data/adb/modules/owly /data/adb/owly/radio; do
    [ -x "$candidate/owly" ] && [ -f "$candidate/bin/stack.sh" ] || continue
    MODDIR=$(CDPATH= cd -- "$candidate" && pwd)
    break
done
[ -n "$MODDIR" ] || MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)

S=/data/adb/owly
STATE=$S/stack
LOG=$STATE/watch.log
MARK=$STATE/watch-last
LOCK=$STATE/watch.pid
DAEMON_LOG=$S/daemon.log

MIN_GAP=45
POLL=2

RX_HOLD_MAX=180

REFRESH_AT=480

SESSION_GRACE_SKIPS=2

PROFILE_6=$MODDIR/profiles/6/owly

preferred_channel() {
    if [ ! -f "$S/coex-on" ] || [ ! -x "$PROFILE_6" ]; then
        echo 149
        return
    fi
    frequency=$(iw dev wlan0 link 2>/dev/null | awk '/^[[:space:]]*freq:/ { print int($2); exit }')
    case "$frequency" in
        5[0-8][0-9][0-9]) echo 6 ;;
        "") echo "${CH:-149}" ;;
        *) echo 149 ;;
    esac
}

mkdir -p "$STATE"
log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }
now() { date +%s; }

SETMETHOD_FILE=$STATE/setmethod

set_state() {
    want=$1

    case "$(cat "$SETMETHOD_FILE" 2>/dev/null)" in
        direct)
            settings put system oshare_state "$want" 2>/dev/null
            [ "$(settings get system oshare_state 2>/dev/null)" = "$want" ] && return 0
            ;;
        su)
            /system/bin/su -c "settings put system oshare_state $want" >/dev/null 2>&1
            [ "$(settings get system oshare_state 2>/dev/null)" = "$want" ] && return 0
            ;;
    esac

    settings put system oshare_state "$want" 2>/dev/null
    if [ "$(settings get system oshare_state 2>/dev/null)" = "$want" ]; then
        echo direct > "$SETMETHOD_FILE" 2>/dev/null
        return 0
    fi

    /system/bin/su -c "settings put system oshare_state $want" >/dev/null 2>&1
    if [ "$(settings get system oshare_state 2>/dev/null)" = "$want" ]; then
        echo su > "$SETMETHOD_FILE" 2>/dev/null
        return 0
    fi

    log "could not write oshare_state=$want by any route"
    return 1
}

SLEEP_FRAC=
if sleep 0.05 2>/dev/null; then SLEEP_FRAC=1; fi
files() { ls /sys/class/net 2>/dev/null | grep -E 'wonder0|awdl0|wondertap0' | tr '\n' ' '; }

wanted() {
    case "$(settings get system oshare_state 2>/dev/null)" in
        1) return 0 ;;
        *) return 1 ;;
    esac
}

if [ -f "$LOCK" ]; then
    other=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$other" ] && [ "$other" != "$$" ] && kill -0 "$other" 2>/dev/null; then
        echo "$(date '+%H:%M:%S') another watchdog (pid $other) is already running; exiting" >> "$LOG"
        exit 0
    fi
fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"' EXIT

KEEPER=$MODDIR/bin/osharekeep.sh

session_count() {
    count=$(grep -ac 'session up as' "$DAEMON_LOG" 2>/dev/null)
    echo "${count:-0}"
}

nudge_session() {
    log "no session while AirDrop is wanted; cycling oshare_state to start one"
    set_state 0
    if [ -n "$SLEEP_FRAC" ]; then sleep 0.3; else sleep 1; fi
    tries=0
    while [ "$tries" -lt 4 ]; do
        set_state 1
        v=$(settings get system oshare_state 2>/dev/null)
        case "$v" in 1) return 0 ;; esac
        tries=$((tries + 1))
        log "oshare_state came back as '$v' after the nudge, not 1; retry $tries"
        sleep 1
    done
    log "could not put oshare_state back to 1 after the nudge; AirDrop stays off until the user turns it on"
    return 1
}

session_is_stale() { return 1; }

wait_session() {
    i=0
    while [ "$i" -lt "$1" ]; do
        sleep 2; i=$((i + 2))
        [ "$(session_count)" -gt "$2" ] && [ -e /sys/class/net/awdl0 ] && return 0
    done
    return 1
}

start_owly() {
    cd "$MODDIR" 2>/dev/null || return 1
    rmmod owly_coex 2>/dev/null
    daemon=$MODDIR/owly
    [ "$CH" = 6 ] && daemon=$PROFILE_6
    setsid "$daemon" android-daemon >> "$DAEMON_LOG" 2>&1 &
}

stop_owly() {
    pkill -x owly 2>/dev/null
    remaining=20
    while pidof owly >/dev/null 2>&1; do
        [ "$remaining" -gt 0 ] || return 1
        sleep 0.1
        remaining=$((remaining - 1))
    done
}

airdrop_connection_open() {
    connections=$(ss -tn 2>/dev/null) || return 0
    printf '%s\n' "$connections" | grep -qE '%awdl0|:877[01]([[:space:]]|$)'
}

light_rebuild() {
    [ -e /sys/class/net/wondertap0 ] || return 1
    if [ ! -e /sys/class/net/wonder0 ]; then
        iw phy wonder interface add wonder0 type monitor 2>>"$LOG" || return 1
    fi
    ip link set wonder0 up 2>>"$LOG" || return 1
    iw dev wonder0 set channel "$CH" 2>>"$LOG" || return 1
    [ "$(cat /sys/class/net/wondertap0/operstate 2>/dev/null)" = up ] &&
        [ "$(rx_nodrv)" = 0 ]
}

heavy_rebuild() {
    WONDER_CHANNEL=$CH sh "$MODDIR/bin/stack.sh" up >> "$LOG" 2>&1 || return 1
    return 0
}

rx_nodrv() { cat /sys/module/wonder/parameters/opairshare_rx_nodrv 2>/dev/null || echo 0; }
rx_summary() {
    p=/sys/module/wonder/parameters
    echo "entry=$(cat $p/opairshare_rx_entry 2>/dev/null) delivered=$(cat $p/opairshare_rx_delivered 2>/dev/null) wonder_rx=$(cat $p/opairshare_wonder_rx 2>/dev/null) invalid=$(cat $p/opairshare_wonder_rx_invalid 2>/dev/null) nodrv=$(cat $p/opairshare_rx_nodrv 2>/dev/null)"
}

clear_awdl() {
    if [ -e /sys/class/net/awdl0 ]; then
        ip link set awdl0 down 2>/dev/null
        iw dev awdl0 del 2>/dev/null
    fi
}

CH=$(preferred_channel)
for daemon_pid in $(pidof owly); do
    case "$(readlink "/proc/$daemon_pid/exe")" in
        "$PROFILE_6") CH=6 ;;
        "$MODDIR/owly") CH=149 ;;
    esac
done
log "watchdog started (channel $CH, module $MODDIR, gated on oshare_state)"
was_wanted=""

rx_traffic() {
    cat /sys/module/wonder/parameters/opairshare_wonder_rx 2>/dev/null
}

hold_since=""
last_rx=""

APP=com.owly.android
APP_CHECK_EVERY=15
APP_GONE_AFTER=90
app_checked_at=0
app_gone_since=""

app_removed() {
    [ "$(getprop sys.boot_completed)" = 1 ] || return 1
    [ -f "$MODDIR/bin/remove.sh" ] || return 1
    [ -n "$(pm path android 2>/dev/null)" ] || return 1
    [ -z "$(pm path --user 0 "$APP" 2>/dev/null)" ]
}

check_app() {
    t=$(now)
    [ $((t - app_checked_at)) -ge "$APP_CHECK_EVERY" ] || return 0
    app_checked_at=$t
    if app_removed; then
        [ -n "$app_gone_since" ] || app_gone_since=$t
        if [ $((t - app_gone_since)) -ge "$APP_GONE_AFTER" ]; then
            log "app $APP has been uninstalled; removing the service"
            setsid sh "$MODDIR/bin/remove.sh" gone </dev/null >/dev/null 2>&1 &
            exit 0
        fi
    else
        app_gone_since=""
    fi
}

while true; do
    check_app
    channel=$(preferred_channel)
    if [ "$channel" != "$CH" ] && [ "$channel" = "${previous_channel:-}" ] &&
        ! airdrop_connection_open; then
        if stop_owly; then
            CH=$channel
            rm -f "$MARK"
            idle_start_at=0
            log "selected AirDrop channel $CH for the current Wi-Fi band"
        else
            log "could not stop the previous daemon for a channel change"
        fi
    fi
    previous_channel=$channel

    if ! wanted; then
        if [ "$was_wanted" != "no" ]; then
            log "AirDrop not wanted (oshare_state != 1); keeping hands off the radio"
            was_wanted="no"
        fi
        hold_since=""
        if ! pidof owly >/dev/null 2>&1 &&
            [ $(( $(now) - ${idle_start_at:-0} )) -ge "$MIN_GAP" ]; then
            idle_start_at=$(now)
            start_owly
        fi
        sleep "$POLL"
        continue
    fi

    coex=off
    [ -f "$S/coex-on" ] && coex=on
    if [ "$coex" != "${last_coex:-}" ]; then
        last_coex=$coex
        between_rounds=0
        rm -f "$MARK"
        newch=$(preferred_channel)
        if [ "$newch" != "$CH" ] && stop_owly; then
            CH=$newch
            idle_start_at=0
            log "coexistence is $coex; AirDrop channel $CH"
        fi
    fi

    if [ "$coex" = off ] && [ "$(settings get global wifi_on 2>/dev/null)" = 1 ] &&
        pidof owly >/dev/null 2>&1; then
        last=$(grep -a -e 'holding off: Wi-Fi was turned back on' -e 'session up as' "$DAEMON_LOG" 2>/dev/null | tail -1)
        case "$last" in
            *'holding off'*) sleep "$POLL"; continue ;;
        esac
    fi

    rx_delta=""
    rx_seen=$(rx_traffic)
    if [ -n "$last_rx" ] && [ "$rx_seen" != "$last_rx" ]; then rx_delta=1; fi
    last_rx="$rx_seen"

    if [ "$was_wanted" != "yes" ]; then
        log "AirDrop wanted (oshare_state = 1); looking after the stack"
        was_wanted="yes"
        rm -f "$MARK"
        between_rounds=0
    fi

    bad=""
    if [ ! -e /sys/class/net/wonder0 ]; then bad="no wonder0"
    elif [ ! -e /sys/class/net/wondertap0 ]; then bad="no wondertap0"
    elif [ "$(cat /sys/class/net/wondertap0/operstate 2>/dev/null)" != "up" ]; then
        bad="wondertap0 is $(cat /sys/class/net/wondertap0/operstate 2>/dev/null)"
    elif [ ! -e /sys/class/net/awdl0 ]; then bad="no awdl0"
    elif ! pidof owly >/dev/null 2>&1; then bad="owly not running"
    fi

    if [ -n "$bad" ]; then
        last=0
        [ -f "$MARK" ] && last=$(cat "$MARK" 2>/dev/null)
        gap=$(( $(now) - ${last:-0} ))

        between=0
        last_word=$(grep -a -e 'session up as' -e 'session down' "$DAEMON_LOG" 2>/dev/null | tail -1)
        case "$last_word" in
            *'session down'*) if pidof owly >/dev/null 2>&1; then
                between=$(( ${between_rounds:-0} + 1 ))
                between_rounds=$between
                if [ "$between" -le "$SESSION_GRACE_SKIPS" ]; then
                    log "unhealthy: $bad -- owly is between sessions (round ${between}/${SESSION_GRACE_SKIPS}); waiting for it"
                    sleep "$POLL"
                    continue
                fi
                fi ;;
            *) between_rounds=0 ;;
        esac

        if [ -n "$rx_delta" ]; then
            [ -z "$hold_since" ] && hold_since=$(now)
            held=$(( $(now) - hold_since ))
            if [ "$held" -lt "$RX_HOLD_MAX" ]; then
                log "unhealthy: $bad -- but frames are moving (${rx_seen}); holding off ${held}s/${RX_HOLD_MAX}s"
                sleep "$POLL"
                continue
            fi
            log "traffic has kept the watchdog off for ${held}s; rebuilding anyway"
        fi
        hold_since=""

        if [ "$gap" -lt "$MIN_GAP" ]; then
            waited=$(( ${waited:-0} + $POLL ))
            [ $((waited % 15)) -eq 5 ] && log "unhealthy: $bad -- holding off ${gap}s/${MIN_GAP}s since the last restart"
            sleep "$POLL"
            continue
        fi
        waited=0

        log "unhealthy: $bad -- restarting the stack"
        echo "$(now)" > "$MARK"

        last_rx=""

        if ! stop_owly; then
            log "owly did not stop, retrying after ${MIN_GAP}s"
            sleep "$POLL"
            continue
        fi

        before=$(session_count)
        path=""

        if light_rebuild; then
            clear_awdl
            start_owly
            if wait_session 20 "$before"; then
                path="light"
                log "  light rebuild worked (files=[$(files)])"
            else
                log "  light rebuild gave interfaces but no session; falling back to heavy"
                if ! stop_owly; then
                    log "owly did not stop after the light rebuild, retrying after ${MIN_GAP}s"
                    sleep "$POLL"
                    continue
                fi
            fi
        fi

        if [ -z "$path" ]; then
            if heavy_rebuild; then
                clear_awdl
                before=$(session_count)
                start_owly
                if wait_session 30 "$before"; then
                    path="heavy"
                else
                    log "  heavy rebuild gave a radio but no session"
                fi
            else
                log "  bring-up failed; owly left stopped, will retry after ${MIN_GAP}s"
            fi
        fi

        if [ -n "$path" ]; then
            log "owly up on channel $CH via the $path path (files=[$(files)])"
            log "  rx: $(rx_summary)"
        fi
    fi

    if [ -z "$bad" ]; then
        between_rounds=0
        last=$(grep -a -e 'session up as' -e 'session down' "$DAEMON_LOG" 2>/dev/null | tail -1)
        case "$last" in
            *'session up as'*)
                if session_is_stale; then
                    log "session has run ${REFRESH_AT}s; renewing it before owly's ten-minute deadline"
                    rm -f "$STATE/session-up"
                    nudge_session
                fi
                ;;
            *)
                rm -f "$STATE/session-up"
                nudge_last=0
                [ -f "$STATE/nudge-last" ] && nudge_last=$(cat "$STATE/nudge-last" 2>/dev/null)
                if [ $(( $(now) - ${nudge_last:-0} )) -ge "$MIN_GAP" ]; then
                    echo "$(now)" > "$STATE/nudge-last"
                    nudge_session
                fi
                ;;
        esac
    fi

    sleep "$POLL"
done
