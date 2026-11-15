"""Virtual Xbox 360 controller backed by /dev/uinput."""

from evdev import AbsInfo, UInput
from evdev import ecodes as e

from protocol import NEUTRAL, Btn, State

# Same USB IDs as a real Xbox 360 pad, so Steam/SDL/Proton pick the standard mapping.
XBOX360_VENDOR = 0x045E
XBOX360_PRODUCT = 0x028E
DEFAULT_NAME = "Microsoft X-Box 360 pad"

BUTTON_CODES = {
    Btn.A: e.BTN_A,
    Btn.B: e.BTN_B,
    Btn.X: e.BTN_X,
    Btn.Y: e.BTN_Y,
    Btn.LB: e.BTN_TL,
    Btn.RB: e.BTN_TR,
    Btn.SELECT: e.BTN_SELECT,
    Btn.START: e.BTN_START,
    Btn.MODE: e.BTN_MODE,
    Btn.L3: e.BTN_THUMBL,
    Btn.R3: e.BTN_THUMBR,
}


def _stick() -> AbsInfo:
    return AbsInfo(value=0, min=-32768, max=32767, fuzz=0, flat=128, resolution=0)


def _trigger() -> AbsInfo:
    return AbsInfo(value=0, min=0, max=255, fuzz=0, flat=0, resolution=0)


def _hat() -> AbsInfo:
    return AbsInfo(value=0, min=-1, max=1, fuzz=0, flat=0, resolution=0)


# Layout of the Linux xpad driver: sticks, triggers on Z/RZ, D-pad as a hat.
CAPABILITIES = {
    e.EV_KEY: list(BUTTON_CODES.values()),
    e.EV_ABS: [
        (e.ABS_X, _stick()),
        (e.ABS_Y, _stick()),
        (e.ABS_Z, _trigger()),
        (e.ABS_RX, _stick()),
        (e.ABS_RY, _stick()),
        (e.ABS_RZ, _trigger()),
        (e.ABS_HAT0X, _hat()),
        (e.ABS_HAT0Y, _hat()),
    ],
}


def _values(s: State) -> dict[tuple[int, int], int]:
    v = {(e.EV_KEY, code): int(bool(s.buttons & btn)) for btn, code in BUTTON_CODES.items()}
    v[(e.EV_ABS, e.ABS_X)] = s.lx
    v[(e.EV_ABS, e.ABS_Y)] = s.ly
    v[(e.EV_ABS, e.ABS_Z)] = s.lt
    v[(e.EV_ABS, e.ABS_RZ)] = s.rt
    v[(e.EV_ABS, e.ABS_HAT0X)] = bool(s.buttons & Btn.DPAD_RIGHT) - bool(s.buttons & Btn.DPAD_LEFT)
    v[(e.EV_ABS, e.ABS_HAT0Y)] = bool(s.buttons & Btn.DPAD_DOWN) - bool(s.buttons & Btn.DPAD_UP)
    return v


class VirtualPad:
    def __init__(self, name: str = DEFAULT_NAME):
        self._ui = UInput(
            CAPABILITIES,
            name=name,
            vendor=XBOX360_VENDOR,
            product=XBOX360_PRODUCT,
            version=0x0110,
            bustype=e.BUS_USB,
        )
        self._last = _values(NEUTRAL)

    def apply(self, state: State) -> None:
        """Emit only what changed since the previous call."""
        new = _values(state)
        changed = False
        for key, value in new.items():
            if self._last[key] != value:
                self._ui.write(key[0], key[1], value)
                changed = True
        if changed:
            self._ui.syn()
        self._last = new

    def close(self) -> None:
        self._ui.close()
