# Contributing

Thanks for helping. Debloat TV is small on purpose: plain Java, no libraries, no Gradle.

## Build and try it

```bash
./build.sh                         # needs JDK 17 and the Android SDK (platforms;android-34, build-tools;34.0.0)
adb install -r DebloatTV.apk       # update the app on your TV
adb logcat -s DebloatTV            # its logs
```

## Before a pull request

```bash
shellcheck -S warning install.sh build.sh tests/installer_test.sh
bash tests/installer_test.sh       # the installer against a fake adb: no TV needed
./build.sh
```

GitHub runs the same checks on every push.

## Where things are

| File | What |
|---|---|
| `HomeView.java` | The home screen: rows, cards, focus, backdrop, moving rows and apps |
| `SettingsPages.java` | The gear menu and everything under Home settings |
| `Menus.java` | Holding OK on an app or a row |
| `FirstRun.java` | The first-time questions |
| `HomeLayout.java` | The rows and hidden apps, saved on the TV |
| `Picks.java`, `Trakt.java`, `Stremio.java` | The titles in the rows |
| `Buttons.java`, `KeyService.java` | Remote buttons |
| `Adb.java`, `TvControl.java` | The TV's own adb: remote buttons after a restart, the Home switch |
| `install.sh` | Install, settings, undo |

## Ground rules

- Never uninstall system apps: turn them off (`pm disable-user`), so undo can turn them back on.
- Anything that changes the TV must have an undo, and must say clearly when it didn't work.
- Tell us your TV model when reporting how it went: it's only been tested on a TCL Google TV so far.
