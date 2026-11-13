#!/system/bin/sh
cd "${0%/*}" || exit 1
MODDIR=$PWD
S=/data/adb/owly
STACK=$S/stack
mkdir -p "$S" "$STACK" /data/etc

[ -f /data/etc/radio.toml ]   || cp -f "$MODDIR/etc/radio.toml"   /data/etc/radio.toml
[ -f /data/etc/drivers.toml ] || cp -f "$MODDIR/etc/drivers.toml" /data/etc/drivers.toml

chmod 700 "$MODDIR/owly" 2>/dev/null
chmod 700 "$MODDIR/profiles/6/owly" 2>/dev/null
chmod 700 "$MODDIR/bin/"*.sh 2>/dev/null
chmod 700 "$MODDIR/bin/speed-vcmd" 2>/dev/null
setsid sh "$MODDIR/bin/speed-watch.sh" "$MODDIR" </dev/null >/dev/null 2>&1 &

i=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 90 ]; do
    sleep 2; i=$((i + 1))
done

bid=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ "$(cat "$STACK/stack-boot" 2>/dev/null)" != "$bid" ]; then
    echo "$bid" > "$STACK/stack-boot"
    {
        echo "=== $(date) stack up"
        sh "$MODDIR/bin/stack.sh" up
        echo "rc=$?"
    } >> "$STACK/stack.log" 2>&1
fi

if [ -f "$STACK/watch.pid" ] && kill -0 "$(cat "$STACK/watch.pid" 2>/dev/null)" 2>/dev/null; then
    echo "owly service: a watchdog is already running; stack left as it is"
    exit 0
fi

pkill -x owly 2>/dev/null
sleep 2
setsid sh "$MODDIR/bin/owly-watch.sh" </dev/null >> "$STACK/watch.log" 2>&1 &
echo "owly service: stack loaded, watchdog started"
