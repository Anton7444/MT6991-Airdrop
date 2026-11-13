#!/system/bin/sh
D=/data/adb/o13
S=/data/adb/owly
SHARE=com.coloros.oshare
TOKEN=airdrop-bridge-token

remove_all() {
    orig=$(cat "$D/share-original" 2>/dev/null)
    case "$orig" in
        ''|*[!0-9]*) orig=0 ;;
    esac
    if [ -f "$S/wifi-was-on" ] && [ "$(settings get global wifi_on)" != 1 ]; then
        svc wifi enable
    fi
    [ -f "$D/bin/stack.sh" ] && sh "$D/bin/stack.sh" down >/dev/null 2>&1
    pkill -f owly-watch.sh
    pkill -f speed-watch.sh
    pkill -f osharekeep.sh
    pkill -x owly
    sleep 1
    for m in owly_coex wonder mtk_wonder_dev; do
        grep -q "^$m " /proc/modules && rmmod "$m" 2>/dev/null
    done
    settings put system oshare_state "$orig"
    settings delete global airdrop_awdl_tx
    settings delete global airdrop_island_stamp
    rm -f /data/adb/service.d/airdrop-radio.sh /data/adb/service.d/airdrop-speed.sh
    rm -f "/data/user/0/$SHARE/files/$TOKEN"
    [ "$1" = gone ] && am force-stop "$SHARE"
    rm -rf "$D" "$D".old.* "$D".stage.* "$D"-coex-stage "$S" /data/etc/radio.toml /data/etc/drivers.toml /data/local/tmp/airdrop-start.log
}

remove_all "$1"
exit 0
