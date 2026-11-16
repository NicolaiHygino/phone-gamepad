"""Turn a keyboard into a virtual Xbox 360 pad, for playing next to the phones.

The keyboard is grabbed exclusively while this runs: it stops typing on the desktop and in the
game, and only drives the pad. That matters for games that also read the keyboard themselves
(Cuphead does), where an ungrabbed key would press twice, once for each player.

The keyboard is found by its device name (Bluetooth keyboards have no stable /dev/input/by-id path)
or, for a USB keyboard, by a path such as /dev/input/by-id/usb-...-event-kbd (a glob is fine).
One process drives one keyboard and one pad; run one per keyboard.
If it disconnects or sleeps, every button is released and it is picked up again when it returns;
the virtual pad stays alive the whole time.

Usage:  python server/keypad.py --list                 # names of the keyboards you can use
        python server/keypad.py "Keyboard K380 Keyboard"
        python server/keypad.py "/dev/input/by-id/usb-*-event-kbd"
"""

import argparse
import glob
import logging
import pathlib
import signal
import sys
import time

from protocol import NEUTRAL, Btn, State

log = logging.getLogger("keypad")

# evdev codes, from linux/input-event-codes.h (kept here so the mapping is testable without evdev).
EV_KEY = 1
KEY_ESC = 1
KEY_I = 23
KEY_ENTER = 28
KEY_A = 30
KEY_S = 31
KEY_D = 32
KEY_J = 36
KEY_K = 37
KEY_L = 38
KEY_LEFTSHIFT = 42
KEY_SPACE = 57
KEY_W = 17
KEY_UP = 103
KEY_LEFT = 105
KEY_RIGHT = 106
KEY_DOWN = 108

# Captions are Cuphead's default Xbox bindings, like the phone's.
BUTTONS = {
    KEY_SPACE: Btn.A,  # jump, menu confirm
    KEY_K: Btn.B,  # EX, menu back
    KEY_J: Btn.X,  # shoot
    KEY_LEFTSHIFT: Btn.Y,  # dash
    KEY_I: Btn.LB,  # switch weapon
    KEY_L: Btn.RB,  # lock
    KEY_ESC: Btn.START,  # pause
    KEY_ENTER: Btn.START,
}

DIRECTIONS = {
    KEY_W: Btn.DPAD_UP,
    KEY_S: Btn.DPAD_DOWN,
    KEY_A: Btn.DPAD_LEFT,
    KEY_D: Btn.DPAD_RIGHT,
    KEY_UP: Btn.DPAD_UP,
    KEY_DOWN: Btn.DPAD_DOWN,
    KEY_LEFT: Btn.DPAD_LEFT,
    KEY_RIGHT: Btn.DPAD_RIGHT,
}

_AXES = (Btn.DPAD_LEFT | Btn.DPAD_RIGHT, Btn.DPAD_UP | Btn.DPAD_DOWN)

KEY_UP_EVENT, KEY_DOWN_EVENT, KEY_REPEAT_EVENT = 0, 1, 2


class KeyMapper:
    """Held keys -> pad state.

    When both directions of an axis are held (A and D, say), the one pressed last wins, and
    letting go of it returns to the other. That keeps a platformer responsive when one finger
    rolls onto the next key before the first lifts.
    """

    def __init__(self) -> None:
        self._held: list[int] = []  # mapped keys currently down, oldest first

    def key(self, code: int, value: int) -> State | None:
        """Feeds one key event; returns the new state, or None if nothing this mapper cares about changed."""
        if code not in BUTTONS and code not in DIRECTIONS:
            return None
        if value == KEY_DOWN_EVENT:
            if code in self._held:
                return None
            self._held.append(code)
        elif value == KEY_UP_EVENT:
            if code not in self._held:
                return None  # e.g. a key that was already down when the keyboard was grabbed
            self._held.remove(code)
        else:
            return None  # autorepeat
        return self.state()

    def state(self) -> State:
        buttons = 0
        for code in self._held:
            buttons |= BUTTONS.get(code, 0)
        for axis in _AXES:
            last = next((DIRECTIONS[c] for c in reversed(self._held) if DIRECTIONS.get(c, 0) & axis), 0)
            buttons |= last
        return State(buttons=buttons)

    def reset(self) -> State:
        self._held.clear()
        return NEUTRAL


def keyboard_names() -> dict[str, str]:
    """Event node -> device name for every input device that has keys, read from sysfs (no permission needed)."""
    found = {}
    nodes = pathlib.Path("/sys/class/input").glob("event*")
    for node in sorted(nodes, key=lambda n: int(n.name[5:])):
        try:
            name = (node / "device" / "name").read_text().strip()
            keys = (node / "device" / "capabilities" / "key").read_text().split()
        except OSError:
            continue
        if any(int(word, 16) for word in keys):
            found[f"/dev/input/{node.name}"] = name
    return found


def find_keyboard(spec: str):
    """Opens the first event node called [spec], or matching it if it is a path (glob), or returns None.

    Raises PermissionError if a match exists but is unreadable.
    """
    import evdev

    if spec.startswith("/"):
        paths = sorted(glob.glob(spec))
    else:
        paths = [path for path, name in keyboard_names().items() if name == spec]
    denied = None
    for path in paths:
        try:
            return evdev.InputDevice(path)
        except PermissionError as err:
            denied = err
        except OSError:
            continue  # disappeared between listing and opening
    if denied:
        raise denied
    return None


def run(name: str, pad, find=find_keyboard, stop=lambda: False, retry_s: float = 1.0) -> None:
    """Drives [pad] from the keyboard called [name] until [stop] returns true (checked between connections)."""
    log.info("keyboard: %r", name)
    mapper = KeyMapper()
    waiting_logged = False
    try:
        while not stop():
            try:
                kbd = find(name)
            except PermissionError:
                if not waiting_logged:
                    log.error("cannot open %r: permission denied (see the udev rule in the README)", name)
                    waiting_logged = True
                time.sleep(retry_s)
                continue
            if kbd is None:
                if not waiting_logged:
                    log.info("waiting for %r", name)
                    waiting_logged = True
                time.sleep(retry_s)
                continue
            waiting_logged = False
            try:
                kbd.grab()
                log.info("grabbed %s (%s)", kbd.name, kbd.path)
                for ev in kbd.read_loop():
                    if ev.type != EV_KEY:
                        continue
                    state = mapper.key(ev.code, ev.value)
                    if state is not None:
                        log.debug("buttons=0x%04x", state.buttons)
                        pad.apply(state)
            except OSError:
                log.info("keyboard disconnected")
            finally:
                pad.apply(mapper.reset())
                try:
                    kbd.close()
                except OSError:
                    pass
            time.sleep(retry_s)
    finally:
        pad.apply(NEUTRAL)
        pad.close()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("keyboard", nargs="?", help="the keyboard's device name (see --list) or its /dev/input path")
    ap.add_argument("--list", action="store_true", help="list input devices with keys and exit")
    ap.add_argument("--name", default=None, help="name of the virtual pad shown to the system")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format=f"keypad [{args.keyboard}]: %(message)s")

    if args.list:
        for path, name in keyboard_names().items():
            print(f"{path}\t{name}")
        return 0
    if not args.keyboard:
        ap.error("give the keyboard's name (see --list) or its path")

    from pad import DEFAULT_NAME, VirtualPad

    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        run(args.keyboard, VirtualPad(args.name or DEFAULT_NAME))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
