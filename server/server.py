#!/usr/bin/env python3
"""UDP -> virtual Xbox 360 gamepad server. One pad per player slot."""

import argparse
import logging
import os
import pathlib
import secrets
import socket
import sys
import threading
import time
from typing import Callable

from protocol import (
    ACK_BAD_KEY,
    ACK_BUSY,
    ACK_OK,
    NEUTRAL,
    decode,
    encode_ack,
    encode_discover_reply,
    is_discover,
    normalize_key,
)

# A numeric key is quick to type on a phone. 4 digits are only 10,000 possibilities: that keeps
# accidental and casual senders out, not a determined attacker (see PROTOCOL.md). Use --key-length
# for a longer one.
KEY_ALPHABET = "0123456789"
KEY_LENGTH = 4
MIN_KEY_LENGTH, MAX_KEY_LENGTH = 4, 32
DEFAULT_SECRET_FILE = (
    pathlib.Path(os.environ.get("XDG_CONFIG_HOME") or pathlib.Path.home() / ".config")
    / "android-controller"
    / "secret"
)


def load_secret(path: pathlib.Path) -> bytes | None:
    """The shared key from `path`, or None when there is no (usable) key file."""
    try:
        key = normalize_key(path.read_text())
    except FileNotFoundError:
        return None
    if not key:
        log.warning("%s is empty, so authentication stays off", path)
        return None
    return key.encode()


def new_secret(path: pathlib.Path, length: int = KEY_LENGTH) -> str:
    """Generates a random numeric key, stores it (owner-only) in `path`, and returns it.

    Keys longer than 4 digits are returned in groups of four (1234-5678); the dashes are optional.
    """
    key = "".join(secrets.choice(KEY_ALPHABET) for _ in range(length))
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(key + "\n")
    os.chmod(path, 0o600)  # also tightens a file that already existed
    return "-".join(key[i : i + 4] for i in range(0, length, 4))

log = logging.getLogger("padserver")

POLL_INTERVAL = 0.05  # how often the watchdog runs while idle


def is_newer(seq: int, last: int) -> bool:
    """True if 16-bit `seq` is ahead of `last` (wrap-around aware)."""
    d = (seq - last) & 0xFFFF
    return 0 < d < 0x8000


class Slot:
    def __init__(self) -> None:
        self.pad = None
        self.owner: tuple | None = None  # (ip, port) of the phone driving this slot
        self.last_seq = 0
        self.last_seen = 0.0


class Server:
    """Pure packet logic; no sockets, so it can be tested with fakes."""

    def __init__(
        self,
        pad_factory: Callable[[int], object],
        max_slots: int = 2,
        timeout: float = 0.5,
        clock: Callable[[], float] = time.monotonic,
        discovery_reply: bytes | None = None,
        secret: bytes | None = None,
    ) -> None:
        self.pad_factory = pad_factory
        self.timeout = timeout
        self.clock = clock
        self.discovery_reply = discovery_reply  # what to answer a "who is there?" broadcast with
        self.secret = secret  # when set, only packets signed with this key are accepted
        self.slots = [Slot() for _ in range(max_slots)]

    def create_pads(self) -> None:
        for i, slot in enumerate(self.slots):
            if slot.pad is None:
                slot.pad = self.pad_factory(i)

    def handle(self, data: bytes, addr: tuple) -> bytes | None:
        """Process one datagram; returns a reply to send back (ack or discovery), or None."""
        if self.discovery_reply is not None and is_discover(data):
            log.debug("discovery request from %s:%d", *addr[:2])
            return self.discovery_reply
        pkt = decode(data)
        if pkt is None or pkt.slot >= len(self.slots):
            return None
        if self.secret is not None and not pkt.verified(self.secret):
            return encode_ack(pkt.slot, pkt.seq, ACK_BAD_KEY)  # no slot, no pad, no input
        slot = self.slots[pkt.slot]

        if slot.owner is not None and slot.owner != addr:
            return encode_ack(pkt.slot, pkt.seq, ACK_BUSY)

        if slot.owner is None:
            if slot.pad is None:
                slot.pad = self.pad_factory(pkt.slot)
            slot.owner = addr
            log.info("player %d connected from %s:%d", pkt.slot + 1, *addr[:2])
        elif not is_newer(pkt.seq, slot.last_seq):
            return None  # duplicate or reordered packet

        slot.last_seq = pkt.seq
        slot.last_seen = self.clock()
        slot.pad.apply(pkt.state)
        return encode_ack(pkt.slot, pkt.seq, ACK_OK)

    def tick(self) -> None:
        """Watchdog: a silent phone gets its inputs released and its slot freed."""
        now = self.clock()
        for i, slot in enumerate(self.slots):
            if slot.owner is not None and now - slot.last_seen > self.timeout:
                slot.pad.apply(NEUTRAL)
                log.info("player %d disconnected (silent)", i + 1)
                slot.owner = None

    def close(self) -> None:
        for slot in self.slots:
            if slot.pad is not None:
                slot.pad.close()
                slot.pad = None


def serve(sock: socket.socket, server: Server, stop: threading.Event | None = None) -> None:
    sock.settimeout(POLL_INTERVAL)
    while stop is None or not stop.is_set():
        try:
            data, addr = sock.recvfrom(64)
        except socket.timeout:
            server.tick()
            continue
        ack = server.handle(data, addr)
        if ack is not None:
            sock.sendto(ack, addr)
        server.tick()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--bind", default="0.0.0.0", help="address to listen on (default: all)")
    ap.add_argument("--port", type=int, default=9876)
    ap.add_argument("--players", type=int, default=2, choices=(1, 2, 3, 4))
    ap.add_argument("--timeout", type=float, default=0.5, help="seconds of silence before release")
    ap.add_argument("--name", default=None, help="device name shown to the system")
    ap.add_argument(
        "--label",
        default=socket.gethostname(),
        help="name shown to phones that search for this PC (default: the hostname)",
    )
    ap.add_argument(
        "--eager",
        action="store_true",
        help="create all virtual pads at startup instead of on a phone's first packet",
    )
    ap.add_argument(
        "--secret-file",
        type=pathlib.Path,
        default=DEFAULT_SECRET_FILE,
        help="shared key; when this file exists only phones that know the key are accepted "
        f"(default: {DEFAULT_SECRET_FILE})",
    )
    ap.add_argument(
        "--new-secret",
        action="store_true",
        help="generate a key, store it in the secret file, print it for the phones, and exit",
    )
    ap.add_argument(
        "--key-length",
        type=int,
        default=KEY_LENGTH,
        help=f"digits for --new-secret, {MIN_KEY_LENGTH}-{MAX_KEY_LENGTH} (default: {KEY_LENGTH})",
    )
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(message)s")

    if args.new_secret:
        if not MIN_KEY_LENGTH <= args.key_length <= MAX_KEY_LENGTH:
            ap.error(f"--key-length must be between {MIN_KEY_LENGTH} and {MAX_KEY_LENGTH}")
        key = new_secret(args.secret_file, args.key_length)
        print(f"New key saved to {args.secret_file}")
        print(f"Enter this on each phone (Settings > Key): {key}")
        if args.key_length < 8:
            print("Note: a short key only keeps accidental or casual senders out; use --key-length 12 for more.")
        print("Restart the server for it to take effect. Delete the file to turn the key off.")
        return 0
    secret = load_secret(args.secret_file)

    if not os.access("/dev/uinput", os.W_OK):
        print(
            "error: cannot write to /dev/uinput. Load the module (`sudo modprobe uinput`) "
            "and make sure your user may access it (e.g. a udev rule or the `input` group).",
            file=sys.stderr,
        )
        return 1

    from pad import DEFAULT_NAME, VirtualPad

    name = args.name or DEFAULT_NAME
    server = Server(
        lambda slot: VirtualPad(name),
        max_slots=args.players,
        timeout=args.timeout,
        discovery_reply=encode_discover_reply(args.port, args.players, args.label),
        secret=secret,
    )
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.bind, args.port))
    try:
        if args.eager:
            server.create_pads()
        log.info(
            "listening on %s:%d for %d player(s), key %s",
            args.bind,
            args.port,
            args.players,
            "required" if secret else "off (anyone on the network may play; see --new-secret)",
        )
        serve(sock, server)
    except KeyboardInterrupt:
        pass
    finally:
        server.close()
        sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
