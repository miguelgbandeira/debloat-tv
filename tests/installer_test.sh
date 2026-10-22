#!/usr/bin/env bash
# Tests install.sh against a fake adb (no TV needed): the Wi-Fi safety check, settings with spaces and empty values,
# and undo refusing to report success when Home didn't go back.   bash tests/installer_test.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
fails=0
pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); }

# The fake adb: answers like a Google TV, controlled by environment variables, and logs every shell command.
mkdir -p "$work/bin"
cat > "$work/bin/adb" <<'FAKE'
#!/usr/bin/env bash
log="$FAKE_DIR/log"
args=("$@"); [ "${args[0]}" = "-s" ] && args=("${args[@]:2}")
case "${args[0]}" in
  connect) echo "connected"; exit 0 ;;
  get-state) echo device; exit 0 ;;
  install) echo Success; exit 0 ;;
  shell) ;;
  *) exit 0 ;;
esac
cmd="${args[*]:1}"
echo "$cmd" >> "$log"
case "$cmd" in
  "pm list packages "*) p=${cmd#pm list packages }; for x in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do [ "$x" = "$p" ] && echo "package:$x"; done ;;
  "cmd package resolve-activity"*) cat "$FAKE_DIR/home" ;;
  # The match comes first, then lots more output: "grep -q" stops reading early, which used to break the check.
  "dumpsys wifi") [ -n "${FAKE_WIFI_SETUP:-}" ] && echo "cname=com.google.android.tungsten.setupwraith"; yes "padding line to make the output long" | head -200000; true ;;
  "pm disable-user --user 0 com.google.android.apps.tv.launcherx") echo "com.google.android.tungsten.setupwraith/.RecoveryActivity" > "$FAKE_DIR/home" ;;
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith") echo "tv.debloat.home/.HomeActivity" > "$FAKE_DIR/home" ;;
  "pm enable "*) [ -n "${FAKE_ENABLE_FAILS:-}" ] && exit 1; echo "com.google.android.apps.tv.launcherx/.home.HomeActivity" > "$FAKE_DIR/home"; echo enabled ;;
  "am broadcast "*) echo 'Broadcast completed: result=0, data="ok: something"' ;;
  "settings get "*) echo null ;;
esac
exit 0
FAKE
chmod +x "$work/bin/adb"
touch DebloatTV.apk.test
run() { FAKE_DIR="$work" PATH="$work/bin:$PATH" APK=DebloatTV.apk.test ./install.sh 10.0.0.2 "$@" > "$work/out" 2>&1; }
reset() { : > "$work/log"; echo "com.google.android.apps.tv.launcherx/.home.HomeActivity" > "$work/home"; }

echo "installer:"
reset; FAKE_WIFI_SETUP=1 run; code=$?
if [ $code -ne 0 ] && ! grep -q "disable-user" "$work/log"; then pass "stops when the setup app saved Wi-Fi (long dumpsys output)"; else fail "Wi-Fi check bypassed (exit $code)"; fi

reset; run; code=$?
if [ $code -eq 0 ] && grep -q "disable-user --user 0 com.google.android.apps.tv.launcherx" "$work/log"; then pass "makes Debloat TV the home screen"; else fail "install (exit $code): $(tail -2 "$work/out")"; fi

reset; printf 'city=New York\ntmdb_key=\nlive_tv_app=a.b\n' > "$work/conf"; run --config "$work/conf"; code=$?
line=$(grep "am broadcast" "$work/log")
if [[ "$line" == *"--es city 'New York'"* && "$line" == *"--es tmdb_key ''"* ]]; then pass "settings keep spaces and empty values"; else fail "settings quoting: $line"; fi

reset; echo "tv.debloat.home/.HomeActivity" > "$work/home"; run --undo; code=$?
if [ $code -eq 0 ] && grep -q "Done: Home opens com.google" "$work/out"; then pass "undo gives Home back"; else fail "undo (exit $code): $(tail -2 "$work/out")"; fi

reset; echo "tv.debloat.home/.HomeActivity" > "$work/home"; FAKE_ENABLE_FAILS=1 run --undo; code=$?
if [ $code -ne 0 ] && ! grep -q "^.*Done" "$work/out"; then pass "undo fails loudly when it can't restore"; else fail "undo reported success after a failure"; fi

rm -f DebloatTV.apk.test
[ $fails -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
