#!/usr/bin/env bash
# Debloat TV installer. Run it on a computer on the same network as the TV, with adb installed.
#
#   ./install.sh <TV-IP>[:port]                 install and make Debloat TV the home screen
#   ./install.sh <TV-IP>[:port] --config FILE   …and send settings (key=value lines: tmdb_key, trakt_client_id,
#                                               city, latitude, longitude, region, live_tv_app, units)
#   ./install.sh <TV-IP>[:port] --undo          give Home back to the TV's own home screen
#
# On the TV first: Settings → System → About → press "Android TV OS build" 7 times, then Developer options →
# USB debugging (and Wireless debugging on Google TV). Accept "Allow debugging?" when it appears.
set -euo pipefail
cd "$(dirname "$0")"

PKG=tv.debloat.home
APK=${APK:-DebloatTV.apk}
GOOGLE_HOME=com.google.android.apps.tv.launcherx
GOOGLE_SETUP=com.google.android.tungsten.setupwraith
OLD_HOMES="com.google.android.tvlauncher com.google.android.leanbacklauncher"

TV=${1:-}; shift || true
[ -n "$TV" ] || { sed -n '2,12p' "$0"; exit 1; }
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
MODE=install CONFIG=
while [ $# -gt 0 ]; do
  case "$1" in
    --undo) MODE=undo ;;
    --config) CONFIG=$2; shift ;;
    *) echo "Unknown option $1" >&2; exit 1 ;;
  esac
  shift
done

say() { printf '\033[1m%s\033[0m\n' "$*"; }
tv() { adb -s "$TV" shell "$@" | tr -d '\r'; }

adb connect "$TV" >/dev/null || true
for i in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] && break
  [ "$i" = 1 ] && say "Accept \"Allow debugging?\" on the TV (tick Always allow)…"
  sleep 3; adb connect "$TV" >/dev/null || true
done
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach the TV at $TV." >&2; exit 1; }

installed() { tv pm list packages "$1" | grep -qx "package:$1"; }

if [ "$MODE" = undo ]; then
  say "Giving Home back to the TV's own home screen…"
  for p in $GOOGLE_SETUP $GOOGLE_HOME $OLD_HOMES; do installed "$p" && tv pm enable "$p" >/dev/null || true; done
  for p in $GOOGLE_HOME $OLD_HOMES; do
    if installed "$p"; then tv cmd role add-role-holder --user 0 android.app.role.HOME "$p" >/dev/null 2>&1 || true; break; fi
  done
  # Our remote-button service off (other accessibility services stay).
  list=$(tv settings get secure enabled_accessibility_services)
  rest=$(printf '%s' "$list" | tr ':' '\n' | grep -v "^$PKG/" | grep -v '^null$' | paste -sd: - || true)
  if [ -n "$rest" ]; then tv settings put secure enabled_accessibility_services "$rest"; else tv settings delete secure enabled_accessibility_services >/dev/null; fi
  say "Done. Debloat TV is still installed: remove it with  adb -s $TV uninstall $PKG"
  exit 0
fi

[ -f "$APK" ] || { echo "No $APK here: build it with ./build.sh or download it from the releases." >&2; exit 1; }
say "Installing Debloat TV…"
adb -s "$TV" install -r "$APK" >/dev/null

say "Permissions…"
tv pm grant $PKG android.permission.WRITE_SECURE_SETTINGS || true
# Some TVs (TCL) block apps from starting in the background unless allowed.
tv cmd appops set $PKG AUTO_START allow 2>/dev/null || true

say "Remote buttons…"
list=$(tv settings get secure enabled_accessibility_services)
case "$list" in *"$PKG/"*) ;; null|"") tv settings put secure enabled_accessibility_services "$PKG/$PKG.KeyService" ;;
  *) tv settings put secure enabled_accessibility_services "$list:$PKG/$PKG.KeyService" ;; esac
tv settings put secure accessibility_enabled 1

if [ -n "$CONFIG" ]; then
  say "Settings from ${CONFIG}…"
  args=()
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue ;; esac
    args+=(--es "$k" "$v")
  done < "$CONFIG"
  adb -s "$TV" shell am broadcast -a $PKG.SETUP -n $PKG/.SetupReceiver "${args[@]}" | tail -1
fi

say "Making it the home screen…"
tv cmd role add-role-holder --user 0 android.app.role.HOME $PKG >/dev/null 2>&1 || true
tv cmd package set-home-activity $PKG/.HomeActivity >/dev/null 2>&1 || true
current=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
if [ "${current%%/*}" != "$PKG" ]; then
  # The TV's own home screen still wins: turn it off (it stays installed; --undo turns it back on).
  if installed $GOOGLE_HOME; then
    # Turning off an app deletes the Wi-Fi networks it saved. The TV's first setup often saved the home Wi-Fi.
    if tv dumpsys wifi | grep -q "cname=$GOOGLE_SETUP"; then
      echo "The TV's first setup saved a Wi-Fi network; turning it off would erase it." >&2
      echo "On the TV: Settings → Network & Internet → forget that network and connect again. Then run this again." >&2
      exit 1
    fi
    tv pm disable-user --user 0 $GOOGLE_HOME >/dev/null
    current=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
    # On Google TV the setup app answers Home next.
    if [ "${current%%/*}" = "$GOOGLE_SETUP" ]; then tv pm disable-user --user 0 $GOOGLE_SETUP >/dev/null; fi
  fi
  for p in $OLD_HOMES; do installed "$p" && tv pm disable-user --user 0 "$p" >/dev/null || true; done
fi
tv input keyevent KEYCODE_HOME
current=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
if [ "${current%%/*}" = "$PKG" ]; then say "Done: Home opens Debloat TV."; else say "Installed, but Home still opens $current."; fi
echo
echo "Keep USB debugging on: Debloat TV uses it to turn remote buttons back on after a restart (TCL and others),"
echo "and for the Home switch in its settings. The TV asks \"Allow USB debugging?\" once for Debloat TV: allow it."
echo "Undo any time:  ./install.sh ${TV%:5555} --undo"
