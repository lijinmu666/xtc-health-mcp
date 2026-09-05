#!/system/bin/sh

MODDIR=${0%/*}
LOGFILE="$MODDIR/watchdog.log"
PACKAGE="com.lijinmu.xtchealth"
COMPONENT="com.lijinmu.xtchealth/.collector.HealthCollectorService"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 10
done

# Best effort only. Some vendor ROMs ignore this whitelist.
dumpsys deviceidle whitelist "+$PACKAGE" >/dev/null 2>&1

while true; do
  if ! pidof "$PACKAGE" >/dev/null 2>&1; then
    NOW=$(date '+%Y-%m-%d %H:%M:%S')
    echo "$NOW process absent; starting foreground service" >> "$LOGFILE"
    am start-foreground-service -n "$COMPONENT" >/dev/null 2>&1 || \
      am startservice -n "$COMPONENT" >/dev/null 2>&1
  fi
  sleep 180
done
