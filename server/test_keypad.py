"""Tests for the keyboard pad: the key mapping, and the device loop with a fake keyboard.

Run from the repo root:  python -m unittest discover -s server -v
"""

import unittest
from types import SimpleNamespace

import keypad as k
from keypad import KeyMapper, run
from protocol import NEUTRAL, Btn

DOWN, UP, REPEAT = 1, 0, 2


class KeyMapperTest(unittest.TestCase):
    def setUp(self):
        self.m = KeyMapper()

    def press(self, *codes):
        state = None
        for c in codes:
            state = self.m.key(c, DOWN)
        return state

    def test_every_button_key(self):
        expected = {
            k.KEY_SPACE: Btn.A,
            k.KEY_K: Btn.B,
            k.KEY_J: Btn.X,
            k.KEY_LEFTSHIFT: Btn.Y,
            k.KEY_I: Btn.LB,
            k.KEY_L: Btn.RB,
            k.KEY_ESC: Btn.START,
            k.KEY_ENTER: Btn.START,
        }
        for code, btn in expected.items():
            with self.subTest(code=code):
                self.assertEqual(self.m.key(code, DOWN).buttons, btn)
                self.assertEqual(self.m.key(code, UP), NEUTRAL)

    def test_wasd_and_arrows_are_the_dpad(self):
        for keys, btn in (
            ((k.KEY_W, k.KEY_UP), Btn.DPAD_UP),
            ((k.KEY_S, k.KEY_DOWN), Btn.DPAD_DOWN),
            ((k.KEY_A, k.KEY_LEFT), Btn.DPAD_LEFT),
            ((k.KEY_D, k.KEY_RIGHT), Btn.DPAD_RIGHT),
        ):
            for code in keys:
                with self.subTest(code=code):
                    self.assertEqual(self.m.key(code, DOWN).buttons, btn)
                    self.m.key(code, UP)

    def test_diagonal_plus_buttons(self):
        state = self.press(k.KEY_W, k.KEY_D, k.KEY_SPACE, k.KEY_J, k.KEY_LEFTSHIFT)
        self.assertEqual(state.buttons, Btn.DPAD_UP | Btn.DPAD_RIGHT | Btn.A | Btn.X | Btn.Y)

    def test_last_pressed_direction_wins_and_the_other_returns(self):
        self.assertEqual(self.press(k.KEY_A, k.KEY_D).buttons, Btn.DPAD_RIGHT)
        self.assertEqual(self.m.key(k.KEY_D, UP).buttons, Btn.DPAD_LEFT)
        self.assertEqual(self.press(k.KEY_W, k.KEY_S).buttons, Btn.DPAD_LEFT | Btn.DPAD_DOWN)
        self.assertEqual(self.m.key(k.KEY_W, UP).buttons, Btn.DPAD_LEFT | Btn.DPAD_DOWN)

    def test_wasd_and_arrows_share_an_axis(self):
        self.assertEqual(self.press(k.KEY_A, k.KEY_RIGHT).buttons, Btn.DPAD_RIGHT)

    def test_two_keys_on_one_button_hold_until_both_lift(self):
        self.press(k.KEY_ESC, k.KEY_ENTER)
        self.assertEqual(self.m.key(k.KEY_ESC, UP).buttons, Btn.START)
        self.assertEqual(self.m.key(k.KEY_ENTER, UP), NEUTRAL)

    def test_repeat_and_unmapped_keys_change_nothing(self):
        self.press(k.KEY_J)
        self.assertIsNone(self.m.key(k.KEY_J, REPEAT))
        self.assertIsNone(self.m.key(k.KEY_J, DOWN))
        self.assertIsNone(self.m.key(44, DOWN))  # Z
        self.assertIsNone(self.m.key(44, UP))

    def test_release_of_a_key_held_before_the_grab_is_ignored(self):
        self.assertIsNone(self.m.key(k.KEY_ENTER, UP))

    def test_reset_releases_everything(self):
        self.press(k.KEY_W, k.KEY_J)
        self.assertEqual(self.m.reset(), NEUTRAL)
        self.assertEqual(self.m.state(), NEUTRAL)


class FindKeyboardTest(unittest.TestCase):
    def test_a_path_that_matches_nothing_is_not_found(self):
        self.assertIsNone(k.find_keyboard("/dev/input/by-id/no-such-keyboard-*-event-kbd"))

    def test_a_name_that_matches_nothing_is_not_found(self):
        self.assertIsNone(k.find_keyboard("no such keyboard, android-controller test"))


class FakePad:
    def __init__(self):
        self.states = []
        self.closed = False

    def apply(self, state):
        self.states.append(state)

    def close(self):
        self.closed = True


class FakeKeyboard:
    """Plays its events, then fails like an unplugged device."""

    def __init__(self, events):
        self.events = events
        self.grabbed = self.closed = False
        self.name, self.path = "fake", "/dev/input/fake"

    def grab(self):
        self.grabbed = True

    def read_loop(self):
        for etype, code, value in self.events:
            yield SimpleNamespace(type=etype, code=code, value=value)
        raise OSError(19, "No such device")

    def close(self):
        self.closed = True


class RunLoopTest(unittest.TestCase):
    def test_keys_drive_the_pad_and_a_disconnect_releases_and_reconnects(self):
        first = FakeKeyboard([(k.EV_KEY, k.KEY_D, DOWN), (k.EV_KEY, k.KEY_J, DOWN), (k.EV_KEY, k.KEY_J, REPEAT)])
        second = FakeKeyboard([(k.EV_KEY, k.KEY_SPACE, DOWN)])
        found = [first, None, second]  # connected, gone for a moment, back again
        calls = []

        def find(name):
            calls.append(name)
            return found.pop(0) if found else None

        pad = FakePad()
        run("K380", pad, find=find, stop=lambda: not found, retry_s=0)

        self.assertEqual(calls[0], "K380")
        self.assertTrue(first.grabbed and first.closed and second.grabbed and second.closed)
        self.assertEqual(
            [s.buttons for s in pad.states],
            [
                Btn.DPAD_RIGHT,
                Btn.DPAD_RIGHT | Btn.X,
                0,  # released on the disconnect
                Btn.A,
                0,  # released on the second disconnect
                0,  # and once more on the way out
            ],
        )
        self.assertTrue(pad.closed)

    def test_non_key_events_are_ignored(self):
        kbd = FakeKeyboard([(4, 4, 30), (k.EV_KEY, k.KEY_K, DOWN)])  # EV_MSC scan code first
        found = [kbd]
        pad = FakePad()
        run("K380", pad, find=lambda _: found.pop(0) if found else None, stop=lambda: not found, retry_s=0)
        self.assertEqual(pad.states[0].buttons, Btn.B)


if __name__ == "__main__":
    unittest.main()
