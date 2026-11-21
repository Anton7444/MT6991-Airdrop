#!/system/bin/sh
D=/data/adb/o13
S=/data/adb/owly
BOOT=/data/adb/service.d/airdrop-radio.sh
SHARE=com.coloros.oshare
TOKEN=airdrop-bridge-token

# A legacy or independently installed stack must never be claimed by uninstall.
[ "$(cat "$D/.airdrop-owned" 2>/dev/null)" = com.owly.android ] || {
    echo 'Refusing to remove a radio without app ownership metadata'; exit 1;
}
orig=$(cat "$D/share-original" 2>/dev/null)
case "$orig" in ''|*[!0-9]*) orig=0 ;; esac

# Stop only processes whose command line names this installation.
pids=""
for proc in /proc/[0-9]*/cmdline; do
    args=$(tr '\000' ' ' < "$proc" 2>/dev/null)
    case "$args" in
        *"$D/"*) pid=${proc#/proc/}; pid=${pid%/cmdline}; [ "$pid" = "$$" ] || { pids="$pids $pid"; kill "$pid" 2>/dev/null; } ;;
    esac
done
for pid in $pids; do
    tries=0
    while kill -0 "$pid" 2>/dev/null; do
        [ "$tries" -lt 10 ] || { echo "Radio process $pid did not stop"; exit 1; }
        sleep 1; tries=$((tries + 1))
    done
done
if [ -f "$D/bin/stack.sh" ]; then
    sh "$D/bin/stack.sh" down || { echo 'Radio did not stop; installation retained'; exit 1; }
fi
[ -f "$S/wifi-was-on" ] && [ "$(settings get global wifi_on)" != 1 ] && svc wifi enable
settings put system oshare_state "$orig"
settings delete global airdrop_awdl_tx
settings delete global airdrop_island_stamp
# Shared /data/adb/owly, /data/etc, old installations and logs are retained.
if [ -f "$BOOT" ] && grep -q -F "$D/service.sh" "$BOOT"; then
    rm -f "$BOOT" || exit 1
fi
SPEED_BOOT=/data/adb/service.d/airdrop-speed.sh
if [ -f "$SPEED_BOOT" ] && grep -q -F "$D/" "$SPEED_BOOT"; then
    rm -f "$SPEED_BOOT" || exit 1
fi
rm -f "/data/user/0/$SHARE/files/$TOKEN"
rm -rf "$D" || exit 1
[ ! -e "$D" ] || exit 1
echo SKY_REMOVED
