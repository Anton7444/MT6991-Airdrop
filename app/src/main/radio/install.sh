#!/system/bin/sh
D=/data/adb/o13
BOOT=/data/adb/service.d/airdrop-radio.sh
STATE=/data/adb/owly/stack
LOG=/data/local/tmp/airdrop-start.log
ZIP=$1
BOOT_SOURCE=$2
COUNTRY=$3
ORIGINAL=$4
LEGACY_OWNED=$5
STAMP=$(date +%s).$$
STAGE=$D.stage.$STAMP
BACKUP=$D.old.$STAMP
BOOT_BACKUP=$D.boot-old.$STAMP
BOOT_NEW=$D.boot-new.$STAMP
HAD_INSTALL=0
HAD_BOOT=0
SWAPPED=0
BOOT_CHANGED=0
COMMITTED=0
STOPPED=0

[ ! -d /data/adb/modules/owly ] && [ ! -d /data/adb/owly/radio ] &&
    [ ! -f /data/adb/service.d/owly-daemon.sh ] || {
    echo 'Conflicting radio installation or boot service; leaving it untouched'; exit 1;
}
if [ -e "$D" ]; then
    [ "$(cat "$D/.airdrop-owned" 2>/dev/null)" = com.owly.android ] ||
        [ "$LEGACY_OWNED" = 1 ] || { echo 'Existing radio is not app-owned'; exit 1; }
fi
case "$COUNTRY" in [A-Z][A-Z]|00) ;; *) exit 1 ;; esac
case "$ORIGINAL" in ''|*[!0-9]*) exit 1 ;; esac

stop_radio() {
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
            # A watchdog script in the middle of a sleep only acts on TERM when the sleep ends.
            [ "$tries" = 5 ] && kill -9 "$pid" 2>/dev/null
            [ "$tries" -lt 10 ] || { echo "Radio process $pid did not stop"; return 1; }
            sleep 1; tries=$((tries + 1))
        done
    done
    [ ! -f "$D/bin/stack.sh" ] || sh "$D/bin/stack.sh" down
}
start_radio() {
    # service.sh otherwise skips stack bring-up after a same-boot reinstall.
    rm -f "$STATE/stack-boot"
    sh "$D/service.sh" >"$LOG" 2>&1 &
}
finish() {
    rc=$?
    trap - EXIT HUP INT TERM
    if [ "$COMMITTED" != 1 ]; then
        if [ "$SWAPPED" = 1 ]; then
            stop_radio || { echo "Cannot stop failed radio; previous installation retained at $BACKUP"; exit 1; }
            mv "$D" "$D.failed.$STAMP" || { echo "Rollback blocked; previous installation remains at $BACKUP"; exit 1; }
        fi
        if [ "$HAD_INSTALL" = 1 ] && [ -d "$BACKUP" ]; then
            mv "$BACKUP" "$D" || { echo "Restore failed; backup retained at $BACKUP"; exit 1; }
        fi
        if [ "$BOOT_CHANGED" = 1 ]; then
            if [ "$HAD_BOOT" = 1 ]; then cp -p "$BOOT_BACKUP" "$BOOT" || exit 1; else rm -f "$BOOT" || exit 1; fi
        fi
        if [ "$STOPPED" = 1 ] && [ -f "$D/service.sh" ]; then start_radio; fi
        echo 'Installation failed; previous installation and boot entry restored where available'
    fi
    rm -rf "$STAGE"
    rm -f "$BOOT_NEW"
    exit "$rc"
}
trap finish EXIT
trap 'exit 1' HUP INT TERM
mkdir "$STAGE" || exit 1
unzip -oq "$ZIP" -d "$STAGE" || exit 1
[ -f "$STAGE/module.prop" ] && [ -f "$STAGE/service.sh" ] && [ -f "$STAGE/bin/stack.sh" ] && [ -f "$STAGE/owly" ] || exit 1
chmod 755 "$STAGE/owly" "$STAGE/service.sh" "$STAGE/bin/"* || exit 1
sed -i "s/^country = .*/country = \"$COUNTRY\"/" "$STAGE/etc/radio.toml" || exit 1
printf '%s\n' "$ORIGINAL" > "$STAGE/share-original" || exit 1
printf '%s\n' com.owly.android > "$STAGE/.airdrop-owned" || exit 1
mkdir -p "${BOOT%/*}" "${LOG%/*}" || exit 1
if [ -e "$BOOT" ]; then cp -p "$BOOT" "$BOOT_BACKUP" || exit 1; HAD_BOOT=1; fi
cp "$BOOT_SOURCE" "$BOOT_NEW" && chmod 755 "$BOOT_NEW" || exit 1
STOPPED=1
stop_radio || exit 1
if [ -d "$D" ]; then mv "$D" "$BACKUP" || exit 1; HAD_INSTALL=1; fi
mv "$STAGE" "$D" || exit 1
SWAPPED=1
BOOT_CHANGED=1
mv -f "$BOOT_NEW" "$BOOT" || exit 1
start_radio
# Verify a live daemon and its IPC endpoint, not merely successful extraction. The radio
# interface is not part of the check: with AirDrop off the watchdog leaves it down on purpose,
# and waiting for it then ran into the timeout and rolled a good install back.
i=0
while [ "$i" -lt 45 ]; do
    if pidof owly >/dev/null 2>&1 && grep -q 'owly-android' /proc/net/unix; then
        COMMITTED=1
        echo SKY_OK
        exit 0
    fi
    sleep 1
    i=$((i + 1))
done
echo 'Radio did not become ready; rolling back'
exit 1
