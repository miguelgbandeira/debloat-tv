# Debloat TV — notes for AI assistants

Debloat TV is a home screen for Android TV / Google TV, installed from a computer over adb. When someone asks you to
install it ("Install Debloat TV on my TV at 192.168.1.50"), do this:

1. **Tools.** Check `adb` is installed (macOS: `brew install --cask android-platform-tools`). To build from source you
   also need a JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0`); otherwise download
   `DebloatTV.apk` from the latest GitHub release into the repo folder.
2. **Connect.** On the TV: Settings → System → About → press "Android TV OS build" 7 times; then System → Developer
   options → USB debugging on (and Wireless debugging on Google TV). `adb connect <IP>:5555`, or on Google TV pair
   with the code from Wireless debugging (`adb pair <IP>:<pairing-port> <code>`, then connect to the port shown on
   the Wireless debugging screen). The person must accept "Allow debugging?" on the TV.
3. **Ask, briefly, and only what applies** (look at what's installed first: `adb shell pm list packages`):
   - Do they use **Stremio** (if `com.stremio.one` is installed) and/or the **streaming apps** found (Netflix, Prime…)?
   - For streaming apps: their free **TMDB key** (themoviedb.org/settings/api). Optional.
   - Their **city** (look up latitude/longitude, e.g. with the Open-Meteo geocoding API) and **country**.
   - **Trakt** (optional): their own Trakt app's Client ID.
   - A **Live TV app** (an IPTV app they use), optional.
4. **Write `debloat.conf`** from `debloat.conf.example` with their answers and `setup_done=1`. Never commit it
   (it's in .gitignore): it holds their keys.
5. **Install:** `./build.sh` (if building), then `./install.sh <IP>[:port] --config debloat.conf`.
6. **Sign-ins happen on the TV** (Stremio and Trakt show a code / QR code to approve on the phone): Settings (top
   right) → Home settings → Stremio / Trakt.
7. Tell them how to undo: `./install.sh <IP> --undo` gives Home back to the TV's own home screen.

Safety: the installer refuses to turn off the TV's setup app when it saved a Wi-Fi network (Android would delete
that network). If it stops there, the person forgets and re-adds the network on the TV, then you run it again.
Never use `pm uninstall` on system apps; Debloat TV only turns them off (`pm disable-user`), which undo reverses.
