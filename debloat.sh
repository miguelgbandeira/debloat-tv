#!/usr/bin/env bash
# Debloat only: turns off the ad, promo and tracking apps in debloat-list.txt and keeps the TV's own home screen.
# Run it on a computer on the same network as the TV, with adb installed.
#
#   ./debloat.sh <TV-IP>[:port]           turn off the apps in debloat-list.txt
#   ./debloat.sh <TV-IP>[:port] --dns     …and block ads for every app with AdGuard DNS (Private DNS)
#   ./debloat.sh <TV-IP>[:port] --undo    turn back on everything this script turned off, and put DNS back
#
# On the TV first: Settings → System → About → press "Android TV OS build" 7 times, then Developer options →
# USB debugging (and Wireless debugging on Google TV). Accept "Allow debugging?" when it appears.
set -euo pipefail
cd "$(dirname "$0")"

LIST=debloat-list.txt
# What this script changed is kept on the TV, so undo works from any computer.
OFF_FILE=/data/local/tmp/debloat-tv-off.txt
DNS_FILE=/data/local/tmp/debloat-tv-dns.txt
CHANNEL=com.tcl.channelplus

TV=${1:-}; shift || true
[ -n "$TV" ] || { sed -n '2,10p' "$0"; exit 1; }
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
MODE=debloat DNS=
while [ $# -gt 0 ]; do
  case "$1" in
    --undo) MODE=undo ;;
    --dns) DNS=1 ;;
    *) echo "Unknown option $1" >&2; exit 1 ;;
  esac
  shift
done

say() { printf '\033[1m%s\033[0m\n' "$*"; }
tv() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

adb connect "$TV" >/dev/null || true
for i in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] && break
  [ "$i" = 1 ] && say "Accept \"Allow debugging?\" on the TV (tick Always allow)…"
  sleep 3; adb connect "$TV" >/dev/null || true
done
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach the TV at $TV." >&2; exit 1; }

if [ "$MODE" = undo ]; then
  off=$(tv "cat $OFF_FILE 2>/dev/null" || true)
  if [ -z "$off" ]; then
    say "Nothing to turn back on: this script hasn't turned anything off on this TV."
  else
    say "Turning apps back on…"
    failed=
    for p in $off; do
      r=$(tv pm enable "$p")
      if grep -q enabled <<<"$r"; then echo "  $p"; else failed="$failed $p"; fi
    done
    case " $off " in *" $CHANNEL "*) tv cmd appops set $CHANNEL RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true ;; esac
    if [ -n "$failed" ]; then echo "Couldn't turn back on:$failed" >&2; exit 1; fi
    tv rm -f $OFF_FILE
  fi
  dns=$(tv "cat $DNS_FILE 2>/dev/null" || true)
  if [ -n "$dns" ]; then
    mode=${dns%% *} spec=${dns#* }
    if [ "$mode" = null ]; then tv settings delete global private_dns_mode >/dev/null; else tv settings put global private_dns_mode "$mode"; fi
    if [ "$spec" = null ]; then tv settings delete global private_dns_specifier >/dev/null; else tv settings put global private_dns_specifier "$spec"; fi
    tv rm -f $DNS_FILE
    say "DNS is back as it was."
  fi
  say "Done. Restart the TV to finish:  adb -s $TV reboot"
  exit 0
fi

[ -f "$LIST" ] || { echo "No $LIST here." >&2; exit 1; }
installed=$(tv pm list packages)
disabled=$(tv pm list packages -d)
wifi=$(tv dumpsys wifi)

say "Turning off ads, promos and tracking…"
count=0
while read -r p _; do
  case "$p" in ''|\#*) continue ;; esac
  grep -qx "package:$p" <<<"$installed" || continue           # not on this TV
  grep -qx "package:$p" <<<"$disabled" && continue            # already off (not ours to undo)
  # Turning off an app deletes the Wi-Fi networks it saved: never risk the TV's connection.
  if grep -q "cname=$p" <<<"$wifi"; then echo "  skipped $p: it saved a Wi-Fi network"; continue; fi
  r=$(tv pm disable-user --user 0 "$p")
  if grep -q disabled <<<"$r"; then
    tv "echo $p >> $OFF_FILE"
    echo "  $p"
    count=$((count + 1))
  else
    echo "  couldn't turn off $p" >&2
  fi
done < "$LIST"
# TCL Channel turns itself back on at every restart: stop it from running in the background too.
if grep -qx "package:$CHANNEL" <<<"$installed"; then tv cmd appops set $CHANNEL RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1 || true; fi
say "Turned off $count apps."

if [ -n "$DNS" ]; then
  if [ -z "$(tv "cat $DNS_FILE 2>/dev/null" || true)" ]; then
    tv "echo $(tv settings get global private_dns_mode) $(tv settings get global private_dns_specifier) > $DNS_FILE"
  fi
  tv settings put global private_dns_mode hostname
  tv settings put global private_dns_specifier dns.adguard-dns.com
  say "Ads blocked for every app (Private DNS: dns.adguard-dns.com)."
fi

say "Done. Restart the TV to finish:  adb -s $TV reboot"
echo "Undo any time:  ./debloat.sh ${TV%:5555} --undo"
