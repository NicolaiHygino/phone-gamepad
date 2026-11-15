# android-controller

Use Android phones as gamepads for a Linux PC game (built for Cuphead, 2 players).
Phones send their touch controls over UDP; the PC server turns them into virtual
Xbox 360 controllers via `/dev/uinput`. Wire format: [PROTOCOL.md](PROTOCOL.md).

## PC server (`server/`)

Needs Python 3 with `python-evdev` and write access to `/dev/uinput`.

```sh
python server/server.py --eager       # 2 pads, created at startup
python server/server.py --players 1   # single player
python server/server.py -v            # more logging
```

Without `--eager`, a pad is only created when a phone first sends to its slot.
If a firewall is active, allow the UDP port (9876 by default) from your LAN.

### Optional: a key so only your phones can play

Without a key, anyone on your Wi-Fi who knows the port could send gamepad input to the PC. To require one:

```sh
python server/server.py --new-secret    # saves ~/.config/android-controller/secret (owner-only) and prints a 4-digit key
```

The server reads the key when it starts (with the Steam wrapper: the next time the game launches). Enter
the printed key in each phone's settings under **Key**. A phone with a missing or wrong key shows "the PC
wants a key" and cannot play; delete the secret file to turn the key off again. You can also write any
key you like into that file (letters and digits; case, spaces and dashes are ignored).

A 4-digit key is easy to type but only keeps accidental and casual senders out: anyone who captures one
packet can try all 10,000 keys offline. For more protection use `--new-secret --key-length 12`.
Details and limits: [PROTOCOL.md](PROTOCOL.md). Do this after every phone runs the current app: older
versions don't know about keys.

### Start it with the game (Steam)

`scripts/padserver-run` runs the server only while a game runs, so the two virtual pads exist exactly
as long as the game does. Link it next to your other wrappers and set the game's Steam launch options
(Properties > General > Launch Options):

```sh
ln -s "$PWD/scripts/padserver-run" ~/.local/bin/padserver-run
```
```
~/.local/bin/padserver-run %command%
```

To chain with another wrapper such as `kbd2pad-run`:
`~/.local/bin/padserver-run ~/.local/bin/kbd2pad-run %command%`. Extra server arguments go in
`PADSERVER_ARGS` (e.g. `PADSERVER_ARGS="--players 1"`); the server's output is in
`$XDG_RUNTIME_DIR/padserver.log`. Stop any server you started by hand first: both want the same port.

### Testing without a phone

```sh
python server/send_test.py press A                 # tap A as player 1
python server/send_test.py --slot 1 press DPAD_DOWN,A
python server/send_test.py stick 1 0 --hold 2      # hold stick right
python server/send_test.py circle                  # sweep the stick
python server/send_test.py discover                # broadcast "who is there?" and list the answers
python -m unittest discover -s server -v           # automated tests
```

Wait about a second between separate `send_test.py` runs for the same slot: each run
is a new socket, and the slot stays owned by the previous one for 0.5 s.

## Android app (`android/`)

Kotlin + Jetpack Compose, built with Gradle from the command line (no Android Studio needed).

Toolchain: JDK 21 (pinned for this repo by `mise.toml`), the Android SDK in `~/Android/Sdk`
(`platform-tools`, `build-tools;36.0.0`, `platforms;android-36` and `platforms;android-37.0`),
and USB debugging enabled on the phone. `compileSdk` is 37 because current Compose requires it;
`targetSdk` is 36.

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.androidcontroller.pad/.MainActivity
```

`android/local.properties` holds the machine-specific SDK path and is not meant to be shared.

### Using it

1. Start the PC server (`python server/server.py --eager`) and allow its port through the firewall
   if one is active: `sudo ufw allow from <your-lan>/24 to any port 9876 proto udp`.
2. Open the app. On first run it searches the network for the server and fills in the address
   (tap **Find PC** to search again, e.g. if the router gave the PC a new address); you can also type
   it. Choose Player 1 or 2. The status line at the top
   shows `connected · N ms (worst M)` when the server answers (median and worst round trip over
   the last 10 s); tap it to change the settings. While the app is in front it holds a low-latency
   Wi-Fi lock, which measured ~0.3 ms lower median and ~2 ms lower p99 on a home 5 GHz network.
   The same dialog sets the **button size**, the **D-pad size** (the pad redraws live as you drag) and
   **swap hands** for left-handed play, and the **key** if the server uses one.
3. Controls follow Cuphead's default Xbox layout: A jump, X shoot, B EX move, Y dash,
   LB switch weapon, RB lock, START pause. Sliding a finger between two face buttons presses both.
4. In Cuphead, press a button on the phone at the title screen first: the first device to press
   becomes Player 1 there.

Skip the settings dialog while developing:
`adb shell am start -n dev.androidcontroller.pad/.MainActivity --es host 192.168.1.10 --ei port 9876 --ei slot 0`

Unit tests (protocol bytes checked against the Python reference, touch handling): `./gradlew testDebugUnitTest`.
