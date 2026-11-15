# phone-gamepad

Use Android phones as touchscreen gamepads for a Linux PC. It was built so two people can play
**Cuphead** co-op with a phone each, but it works with any game that accepts an Xbox 360 controller.

The phones send their touches over Wi-Fi. A small Python server on the PC turns them into virtual
Xbox 360 controllers, so Steam, Proton and the game treat them like real pads.

```
 Phone 1 ──UDP──┐                            ┌─ virtual Xbox 360 pad #1 ─┐
                ├─► PC server (Python) ──────┤                           ├─► the game
 Phone 2 ──UDP──┘    writes to /dev/uinput   └─ virtual Xbox 360 pad #2 ─┘
```

> **Status:** a personal project. Tested with Cuphead (Steam, Proton Experimental) on an Arch-based Linux
> desktop (Hyprland) with two Samsung phones running Android 15 and 16. Other games, distributions and phones
> are untested. The app is built from source (a debug build); it is not on the Play Store.
> Cuphead is a trademark of Studio MDHR, which is not affiliated with this project.

## Features

- **Two players**, one phone each. Each phone picks Player 1 or 2.
- **Touch controls** drawn for landscape: an 8-way D-pad, A/B/X/Y, LB/RB and START, labelled with Cuphead's
  default actions. Real multi-touch, and sliding a finger between two face buttons presses both.
- **Hands-free shooting:** double-tap SHOOT to hold it down, double-tap again to let go, so one thumb can
  jump and dash while the game keeps shooting (see [Controls](#controls)).
- **Low latency.** Phones send their full state on every change plus a 60 Hz heartbeat, and hold a
  low-latency Wi-Fi lock while the app is in front. On a home Wi-Fi network the round trip was about 3 ms
  (median), usually 10-15 ms at worst. The app shows both numbers live.
- **Finds the PC by itself** with a LAN broadcast, so there is no address to type.
- **Adjustable layout:** button size, D-pad size and a swap-hands mode for left-handed play.
- **Safe by default:** a phone that goes quiet (locked, out of range) releases all its buttons after half a
  second, so nothing stays held down.
- **Optional shared key** so only your phones can play (see [Security](#security)).
- **Starts with the game:** a Steam launch-option wrapper runs the server only while the game runs, so the
  virtual pads don't linger in other games.

## What you need

| | |
|---|---|
| **PC** | Linux with the `uinput` module, Python 3 and `python-evdev` (`pacman -S python-evdev` on Arch), and write access to `/dev/uinput` (on systemd desktops the logged-in user normally has it; otherwise a udev rule or the `input` group). |
| **Phones** | Android 8 or newer, on the same local network as the PC. USB debugging for installing. |
| **To build the app** | JDK 21 (what it was developed with; a JDK 25 build also worked) and the Android SDK command-line tools. No Android Studio needed. |
| **Firewall** | UDP port 9876 open to your local network, if the PC runs one. |

## Quick start

### 1. Start the PC server

```sh
python server/server.py --eager        # 2 virtual pads, created at startup
```

If a firewall is active, let your local network reach the port, for example with ufw:

```sh
sudo ufw allow from 192.168.1.0/24 to any port 9876 proto udp    # use your own subnet
```

### 2. Build and install the app

Install the SDK pieces once (`platform-tools`, `build-tools;36.0.0`, `platforms;android-36` and
`platforms;android-37.0`) and point `ANDROID_HOME` at the SDK (or put `sdk.dir=...` in
`android/local.properties`). Then, with the phone plugged in:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`compileSdk` is 37 because current Jetpack Compose requires it; `targetSdk` is 36 and `minSdk` is 26.
`mise.toml` pins JDK 21 for [mise](https://mise.jdx.dev) users, so the build matches the one it was
developed with.

### 3. Play

1. Open **Pad Controller** on the phone. On first run it searches for the server and fills in the address
   (tap **Find PC** to search again, or type it). Choose Player 1 or 2 and tap **Save**.
2. The status line at the top shows `connected · 3.0 ms (worst 10)` when the server answers. Tap it to
   change the settings.
3. In Cuphead, **press a button on the phone at the title screen first.** The first device to press there
   becomes Player 1, and the second player joins as usual.

## Controls

| Phone button | Xbox | Cuphead default |
|---|---|---|
| JUMP | A | jump |
| SHOOT | X | shoot |
| EX | B | EX move |
| DASH | Y | dash |
| SWAP | LB | switch weapon |
| LOCK | RB | lock in place |
| PAUSE | START | pause |
| D-pad | D-pad | move and aim, 8 directions |

**Shooting while jumping.** Double-tap SHOOT and it stays held down after you lift your finger: a white
ring appears around it, its label changes to `HELD`, and the phone buzzes. The thumb is then
free for JUMP, DASH and EX. Double-tap SHOOT again to let go (the button stays down until you lift the
finger from that second tap). Details:

- Both taps must be quick: the first press shorter than 250 ms and the second within 300 ms of lifting.
  Holding SHOOT, lifting and pressing again never latches by accident.
- A third quick tap does not undo the hold; only a fresh double tap does.
- Only SHOOT can be held. The hold is dropped when the app goes to the background or the layout is changed
  in Settings.

The captions follow Cuphead's default Xbox bindings. If you rebind buttons in the game, the labels (in
`android/.../PadLayout.kt`) will not match, but the Xbox buttons underneath still work.

## Start the server with a Steam game

`scripts/padserver-run` starts the server when a game launches and stops it when the game exits. Link it
next to your other wrappers and set the game's Steam launch options (Properties > General > Launch Options):

```sh
ln -s "$PWD/scripts/padserver-run" ~/.local/bin/padserver-run
```
```
~/.local/bin/padserver-run %command%
```

To chain it with another wrapper: `~/.local/bin/padserver-run ~/.local/bin/other-wrapper %command%`.
Extra server arguments go in `PADSERVER_ARGS` (for example `PADSERVER_ARGS="--players 1"`), and the
server's output is in `$XDG_RUNTIME_DIR/padserver.log`. Don't also run a server by hand: both want the
same port.

## Security

Without a key, anyone on your Wi-Fi who knows the port can send gamepad input to your PC. To require one:

```sh
python server/server.py --new-secret     # saves ~/.config/android-controller/secret and prints a 4-digit key
```

The server reads the key when it starts. Enter it in each phone's settings under **Key**. A phone with a
missing or wrong key shows "the PC wants a key" and cannot play. Delete the file to turn the key off.

A 4-digit key is easy to type but only keeps accidental and casual senders out: anyone who captures one
packet can try all 10,000 keys offline. For more protection use `--new-secret --key-length 12`. This is
designed for a home network, not the open internet. Details and limits are in [PROTOCOL.md](PROTOCOL.md).

## Troubleshooting

| What you see | Likely cause |
|---|---|
| `no reply from <address>` | The server isn't running, a firewall is blocking UDP 9876, or the phone is on a different network. Try **Find PC**. |
| `slot taken by another phone` | Two phones chose the same player, or the previous session hasn't timed out yet (about half a second). |
| `the PC wants a key` | The server has a key and the phone's is missing or wrong. |
| Nothing happens in the game | The game window needs focus, and the phone must be unlocked: Android pauses the app when the screen locks. |
| Menus ignore the phone | In Cuphead, whichever device presses a button first at the title screen controls the menus. |

## Development

```
server/      PC side: wire protocol, virtual pad (uinput), the server, a fake-phone tester, tests
android/     the app (Kotlin + Jetpack Compose), built with Gradle
scripts/     padserver-run, the Steam launch-option wrapper
PROTOCOL.md  the wire protocol: packets, discovery, the optional key
```

```sh
python -m unittest discover -s server -v     # includes tests on real uinput devices when allowed
cd android && ./gradlew testDebugUnitTest    # protocol bytes are checked against the Python code
```

Without a phone, `server/send_test.py` plays the part of one:

```sh
python server/send_test.py press A                  # tap A as player 1
python server/send_test.py --slot 1 press DPAD_DOWN,A
python server/send_test.py stick 1 0 --hold 2       # hold the stick right
python server/send_test.py discover                 # broadcast "who is there?" and list the answers
```

Wait about a second between separate runs for the same slot: each run is a new socket, and the slot stays
owned by the previous one for half a second.

To skip the settings dialog while developing:

```sh
adb shell am start -n dev.androidcontroller.pad/.MainActivity --es host 192.168.1.10 --ei slot 0
```

## Limitations

- The server is Linux only (it needs `uinput`).
- The touch UI has no analog sticks or triggers. The protocol carries them, so adding them is mostly UI work.
- The app is a debug build installed over USB.
- Only Cuphead has been tried.

## License

No license has been chosen yet, so by default all rights are reserved.
