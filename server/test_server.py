"""Tests: packet logic with fake pads, plus an end-to-end test against real uinput devices.

Run from the repo root:  python -m unittest discover -s server -v
"""

import os
import pathlib
import select
import socket
import stat
import tempfile
import threading
import time
import unittest

from protocol import (
    ACK_BAD_KEY,
    ACK_BUSY,
    ACK_OK,
    AUTH_PACKET_SIZE,
    NEUTRAL,
    PACKET_SIZE,
    Btn,
    State,
    decode,
    decode_ack,
    decode_discover_reply,
    encode,
    encode_discover,
    encode_discover_reply,
    is_discover,
    normalize_key,
)
from server import Server, load_secret, new_secret, serve

P1 = ("10.0.0.1", 5000)
P2 = ("10.0.0.2", 5000)


class FakePad:
    def __init__(self):
        self.states = []
        self.closed = False

    def apply(self, state):
        self.states.append(state)

    def close(self):
        self.closed = True


class Clock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now


class ProtocolTest(unittest.TestCase):
    def test_roundtrip(self):
        s = State(buttons=Btn.A | Btn.DPAD_LEFT, lx=-32768, ly=32767, lt=7, rt=255)
        data = encode(1, 0xFFFF, s)
        self.assertEqual(len(data), PACKET_SIZE)
        pkt = decode(data)
        self.assertEqual((pkt.slot, pkt.seq, pkt.state), (1, 0xFFFF, s))

    def test_rejects_garbage(self):
        good = encode(0, 1, NEUTRAL)
        self.assertIsNone(decode(good[:-1]))
        self.assertIsNone(decode(good + b"\0"))
        self.assertIsNone(decode(b"\x00" + good[1:]))  # wrong magic
        self.assertIsNone(decode(b""))


KEY = b"TESTKEY1234"
# encode(1, 0xFFFF, State(buttons=A|DPAD_LEFT, lx=-32768, ly=32767, lt=7, rt=255), KEY): packet + 8-byte tag
GOLDEN_SIGNED = "c701ffff01200080ff7f07ff6de7b4f77d977213"


class AuthProtocolTest(unittest.TestCase):
    STATE = State(buttons=Btn.A | Btn.DPAD_LEFT, lx=-32768, ly=32767, lt=7, rt=255)

    def test_signed_packet_is_twenty_bytes_and_verifies(self):
        data = encode(1, 0xFFFF, self.STATE, KEY)
        self.assertEqual(len(data), AUTH_PACKET_SIZE)
        pkt = decode(data)
        self.assertEqual((pkt.slot, pkt.seq, pkt.state), (1, 0xFFFF, self.STATE))
        self.assertTrue(pkt.verified(KEY))

    def test_wrong_key_missing_tag_and_tampering_fail(self):
        self.assertFalse(decode(encode(1, 1, self.STATE, KEY)).verified(b"OTHERKEY5678"))
        self.assertFalse(decode(encode(1, 1, self.STATE)).verified(KEY))  # no tag at all
        tampered = bytearray(encode(1, 1, self.STATE, KEY))
        tampered[4] ^= 0x01  # flip a button bit
        self.assertFalse(decode(bytes(tampered)).verified(KEY))
        tag_flipped = bytearray(encode(1, 1, self.STATE, KEY))
        tag_flipped[-1] ^= 0x01
        self.assertFalse(decode(bytes(tag_flipped)).verified(KEY))

    def test_keys_ignore_case_dashes_and_spaces(self):
        self.assertEqual(normalize_key("test-key 1234"), "TESTKEY1234")

    def test_golden_vector_for_the_android_app(self):
        # android/.../ProtocolTest.kt checks that the app produces exactly these bytes
        self.assertEqual(encode(1, 0xFFFF, self.STATE, KEY).hex(), GOLDEN_SIGNED)

    def test_unsigned_packets_keep_working(self):
        data = encode(1, 5, self.STATE)
        self.assertEqual(len(data), PACKET_SIZE)
        self.assertIsNotNone(decode(data))
        self.assertIsNone(decode(data + b"\0\0\0"))  # neither 12 nor 20 bytes


class SecretFileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name) / "config" / "secret"  # parent does not exist yet

    def test_default_key_is_four_digits_owner_only_and_loads_back(self):
        key = new_secret(self.path)
        self.assertRegex(key, r"^\d{4}$")
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(load_secret(self.path), key.encode())

    def test_longer_keys_are_digits_grouped_by_four(self):
        key = new_secret(self.path, 12)
        self.assertRegex(key, r"^\d{4}-\d{4}-\d{4}$")
        self.assertEqual(load_secret(self.path), key.replace("-", "").encode())

    def test_keys_are_random(self):
        self.assertGreater(len({new_secret(self.path) for _ in range(40)}), 20)

    def test_overwriting_tightens_a_loose_file(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text("0000\n")
        self.path.chmod(0o644)
        new_secret(self.path)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_missing_or_empty_file_means_no_key(self):
        self.assertIsNone(load_secret(self.path))
        self.path.parent.mkdir(parents=True)
        self.path.write_text(" \n")
        self.assertIsNone(load_secret(self.path))

    def test_a_hand_written_key_works_too(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text("48 27\n")  # whitespace and case are ignored
        self.assertEqual(load_secret(self.path), b"4827")


class DiscoveryProtocolTest(unittest.TestCase):
    def test_request_is_three_bytes_and_recognised(self):
        self.assertEqual(encode_discover(), bytes([0xC7, 0xD0, 0x01]))
        self.assertTrue(is_discover(encode_discover()))
        self.assertTrue(is_discover(encode_discover() + b"future"))  # later versions may append data
        self.assertFalse(is_discover(b""))
        self.assertFalse(is_discover(bytes([0x00, 0xD0, 1])))  # wrong magic
        self.assertFalse(is_discover(encode(0, 1, NEUTRAL)))  # a normal state packet

    def test_reply_roundtrip(self):
        d = decode_discover_reply(encode_discover_reply(9876, 2, "my-pc"))
        self.assertEqual((d.port, d.players, d.name), (9876, 2, "my-pc"))

    def test_long_multibyte_name_is_cut_on_a_character_boundary(self):
        raw = encode_discover_reply(1, 1, "é" * 40)  # 80 bytes of UTF-8
        d = decode_discover_reply(raw)
        self.assertEqual(d.name, "é" * 16)

    def test_reply_rejects_garbage(self):
        good = encode_discover_reply(9876, 2, "pc")
        self.assertIsNone(decode_discover_reply(good[:-1]))  # name shorter than declared
        self.assertIsNone(decode_discover_reply(good + b"x"))
        self.assertIsNone(decode_discover_reply(b"\x00" + good[1:]))  # wrong magic
        self.assertIsNone(decode_discover_reply(encode_discover()))


class ServerLogicTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.pads = {}

        def factory(slot):
            self.pads[slot] = FakePad()
            return self.pads[slot]

        self.reply = encode_discover_reply(9876, 2, "test-pc")
        self.srv = Server(factory, max_slots=2, timeout=0.5, clock=self.clock, discovery_reply=self.reply)

    def test_discovery_request_gets_the_reply_and_changes_nothing(self):
        self.assertEqual(self.srv.handle(encode_discover(), P1), self.reply)
        self.assertEqual(self.pads, {})  # no pad created
        self.assertIsNone(self.srv.slots[0].owner)  # no slot claimed

    def keyed(self):
        pads = {}

        def factory(slot):
            pads[slot] = FakePad()
            return pads[slot]

        return Server(factory, secret=KEY, clock=self.clock), pads

    def signed(self, slot=0, seq=1, key=KEY, **state):
        return encode(slot, seq, State(**state), key)

    def test_keyed_server_accepts_signed_packets(self):
        srv, pads = self.keyed()
        ack = srv.handle(self.signed(buttons=Btn.A), P1)
        self.assertEqual(decode_ack(ack).status, ACK_OK)
        self.assertEqual(pads[0].states, [State(buttons=Btn.A)])

    def test_keyed_server_refuses_missing_or_wrong_keys_without_side_effects(self):
        srv, pads = self.keyed()
        for data in (
            encode(0, 1, State(buttons=Btn.A)),  # unsigned
            self.signed(key=b"OTHERKEY5678", buttons=Btn.A),  # wrong key
        ):
            ack = decode_ack(srv.handle(data, P1))
            self.assertEqual(ack.status, ACK_BAD_KEY)
        self.assertEqual(pads, {})  # no pad created
        self.assertIsNone(srv.slots[0].owner)  # slot not claimed

    def test_forger_cannot_take_over_a_slot_or_steal_it_when_free(self):
        srv, pads = self.keyed()
        srv.handle(self.signed(seq=1, buttons=Btn.A), P1)
        forged = encode(0, 2, State(buttons=Btn.B), b"GUESSEDKEY00")
        self.assertEqual(decode_ack(srv.handle(forged, P2)).status, ACK_BAD_KEY)
        self.assertEqual(pads[0].states, [State(buttons=Btn.A)])  # the forger changed nothing
        self.clock.now += 0.6
        srv.tick()  # real phone went quiet; slot is free
        self.assertEqual(decode_ack(srv.handle(forged, P2)).status, ACK_BAD_KEY)
        self.assertIsNone(srv.slots[0].owner)

    def test_unkeyed_server_still_accepts_signed_packets(self):
        ack = self.srv.handle(self.signed(buttons=Btn.A), P1)  # phone has a key, server has none
        self.assertEqual(decode_ack(ack).status, ACK_OK)
        self.assertEqual(self.pads[0].states, [State(buttons=Btn.A)])

    def test_discovery_still_answers_on_a_keyed_server(self):
        srv, _ = self.keyed()
        srv.discovery_reply = self.reply
        self.assertEqual(srv.handle(encode_discover(), P1), self.reply)

    def test_discovery_is_ignored_when_not_configured(self):
        plain = Server(lambda slot: FakePad(), discovery_reply=None)
        self.assertIsNone(plain.handle(encode_discover(), P1))

    def send(self, addr, slot=0, seq=1, **state):
        return self.srv.handle(encode(slot, seq, State(**state)), addr)

    def test_first_packet_creates_pad_lazily_and_applies(self):
        self.assertEqual(self.pads, {})
        ack = self.send(P1, seq=1, buttons=Btn.A)
        self.assertEqual(decode_ack(ack).status, ACK_OK)
        self.assertEqual(decode_ack(ack).seq, 1)
        self.assertEqual(self.pads[0].states, [State(buttons=Btn.A)])
        self.assertNotIn(1, self.pads)  # no phantom player 2

    def test_two_phones_drive_separate_pads(self):
        self.send(P1, slot=0, seq=1, buttons=Btn.A)
        self.send(P2, slot=1, seq=1, buttons=Btn.B)
        self.assertEqual(self.pads[0].states, [State(buttons=Btn.A)])
        self.assertEqual(self.pads[1].states, [State(buttons=Btn.B)])

    def test_second_phone_on_same_slot_gets_busy_and_is_ignored(self):
        self.send(P1, seq=1, buttons=Btn.A)
        ack = self.send(P2, seq=1, buttons=Btn.B)
        self.assertEqual(decode_ack(ack).status, ACK_BUSY)
        self.assertEqual(self.pads[0].states, [State(buttons=Btn.A)])

    def test_stale_and_duplicate_seq_ignored(self):
        self.send(P1, seq=10, lx=100)
        self.assertIsNone(self.send(P1, seq=10, lx=200))  # duplicate
        self.assertIsNone(self.send(P1, seq=9, lx=300))  # reordered
        self.assertEqual([s.lx for s in self.pads[0].states], [100])

    def test_seq_wraparound_accepted(self):
        self.send(P1, seq=0xFFFF, lx=1)
        self.send(P1, seq=0, lx=2)
        self.assertEqual([s.lx for s in self.pads[0].states], [1, 2])

    def test_silence_releases_inputs_and_frees_slot(self):
        self.send(P1, seq=1, buttons=Btn.A)
        self.clock.now += 0.4
        self.srv.tick()
        self.assertEqual(self.pads[0].states[-1], State(buttons=Btn.A))  # still held
        self.clock.now += 0.2
        self.srv.tick()
        self.assertEqual(self.pads[0].states[-1], NEUTRAL)  # released
        # slot is free: another phone (or a restarted one with seq reset) can take it
        ack = self.send(P2, seq=1, buttons=Btn.X)
        self.assertEqual(decode_ack(ack).status, ACK_OK)
        self.assertEqual(self.pads[0].states[-1], State(buttons=Btn.X))

    def test_heartbeat_keeps_ownership(self):
        for seq in range(1, 20):
            self.send(P1, seq=seq, buttons=Btn.A)
            self.clock.now += 0.2
            self.srv.tick()
        self.assertEqual(self.pads[0].states[-1], State(buttons=Btn.A))

    def test_invalid_packets_dropped(self):
        self.assertIsNone(self.srv.handle(b"hello", P1))
        self.assertIsNone(self.send(P1, slot=2, seq=1))  # slot out of range
        self.assertEqual(self.pads, {})

    def test_close_closes_pads(self):
        self.send(P1, seq=1)
        self.srv.close()
        self.assertTrue(self.pads[0].closed)


@unittest.skipUnless(os.access("/dev/uinput", os.W_OK), "needs write access to /dev/uinput")
class EndToEndTest(unittest.TestCase):
    """Real UDP socket -> real uinput devices -> read back through evdev."""

    def setUp(self):
        from evdev import ecodes as e

        from pad import VirtualPad

        self.e = e
        tag = f"android-controller-test-{os.getpid()}"
        self.names = [f"{tag}-p{i + 1}" for i in range(2)]
        self.server = Server(lambda slot: VirtualPad(self.names[slot]), max_slots=2)
        self.server.create_pads()
        self.devs = [self.open_device(n) for n in self.names]

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.stop = threading.Event()
        self.thread = threading.Thread(target=serve, args=(self.sock, self.server, self.stop))
        self.thread.start()
        self.client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.client.settimeout(1.0)
        self.addr = self.sock.getsockname()

    def tearDown(self):
        self.stop.set()
        self.thread.join()
        for d in self.devs:
            d.close()
        self.server.close()
        self.sock.close()
        self.client.close()

    @staticmethod
    def open_device(name, timeout=5.0):
        import evdev

        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for path in evdev.list_devices():
                try:
                    dev = evdev.InputDevice(path)
                except PermissionError:
                    continue  # udev has not granted access yet
                if dev.name == name:
                    return dev
                dev.close()
            time.sleep(0.05)
        raise AssertionError(f"virtual device {name!r} did not appear")

    def events(self, dev, until, timeout=1.0):
        got = []
        end = time.monotonic() + timeout
        while time.monotonic() < end and not until(got):
            if select.select([dev.fd], [], [], 0.05)[0]:
                got += [
                    (ev.type, ev.code, ev.value) for ev in dev.read() if ev.type != self.e.EV_SYN
                ]
        return got

    def send(self, slot, seq, **state):
        self.client.sendto(encode(slot, seq, State(**state)), self.addr)
        return decode_ack(self.client.recvfrom(64)[0])

    def test_device_identity_is_xbox360(self):
        for dev in self.devs:
            self.assertEqual((dev.info.vendor, dev.info.product), (0x045E, 0x028E))

    def test_inputs_arrive_on_the_right_pad(self):
        e = self.e
        ack = self.send(0, 1, buttons=Btn.A | Btn.DPAD_LEFT, lx=12000, ly=-5000, rt=200)
        self.assertEqual(ack.status, ACK_OK)

        expected = {
            (e.EV_KEY, e.BTN_A, 1),
            (e.EV_ABS, e.ABS_HAT0X, -1),
            (e.EV_ABS, e.ABS_X, 12000),
            (e.EV_ABS, e.ABS_Y, -5000),
            (e.EV_ABS, e.ABS_RZ, 200),
        }
        got = self.events(self.devs[0], lambda g: expected <= set(g))
        self.assertTrue(expected <= set(got), f"missing {expected - set(got)}; got {got}")

        # player 2's device must have seen nothing
        self.assertEqual(self.events(self.devs[1], lambda g: False, timeout=0.2), [])

        # release
        self.send(0, 2)
        got = self.events(self.devs[0], lambda g: (e.EV_KEY, e.BTN_A, 0) in g)
        self.assertIn((e.EV_KEY, e.BTN_A, 0), got)
        self.assertIn((e.EV_ABS, e.ABS_HAT0X, 0), got)

    def test_silent_phone_gets_released(self):
        e = self.e
        self.server.timeout = 0.2
        self.send(1, 1, buttons=Btn.X)
        self.events(self.devs[1], lambda g: (e.EV_KEY, e.BTN_X, 1) in g)
        got = self.events(self.devs[1], lambda g: (e.EV_KEY, e.BTN_X, 0) in g, timeout=1.5)
        self.assertIn((e.EV_KEY, e.BTN_X, 0), got)


if __name__ == "__main__":
    unittest.main()
