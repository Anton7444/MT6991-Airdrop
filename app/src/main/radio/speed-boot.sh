#!/system/bin/sh
MODDIR=/data/adb/o13
if [ -f "$MODDIR/bin/speed-watch.sh" ] && [ -f "$MODDIR/bin/speed-vcmd" ]; then
    chmod 700 "$MODDIR/bin/speed-vcmd"
    exec sh "$MODDIR/bin/speed-watch.sh" "$MODDIR"
fi
