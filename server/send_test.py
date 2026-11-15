#!/usr/bin/env python3
"""Fake phone: sends controller packets to the server, for testing without the app.

Examples:
  send_test.py press A                  tap A
  send_test.py press DPAD_DOWN,A -n 3   tap Down+A three times
  send_test.py stick 1 0 --hold 2       hold the left stick fully right for 2 s
  send_test.py --slot 1 circle          sweep the stick in circles as player 2
"""

import argparse
import math
import select
import socket
import sys
import time

from protocol import (
    ACK_BAD_KEY,
    ACK_BUSY,
    NEUTRAL,
    Btn,
    State,
    decode_ack,
    decode_discover_reply,
    encode,
    encode_discover,
    normalize_key,
)

HZ = 60


class Client:
    def __init__(self, host: str, port: int, slot: int, secret: bytes | None = None) -> None:
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setblocking(False)
        self.addr = (host, port)
        self.slot = slot
        self.secret = secret
        self.seq = 0
        self.sent = 0
        self.busy = 0
        self.bad_key = 0
        self.sent_at: dict[int, float] = {}
        self.rtts: list[float] = []

    def send(self, state: State) -> None:
        self.seq = (self.seq + 1) & 0xFFFF
        self.sent_at[self.seq] = time.monotonic()
        self.sock.sendto(encode(self.slot, self.seq, state, self.secret), self.addr)
        self.sent += 1

    def poll(self) -> None:
        while True:
            try:
                data, _ = self.sock.recvfrom(64)
            except BlockingIOError:
                return
            ack = decode_ack(data)
            if ack is None:
                continue
            if ack.status == ACK_BUSY:
                self.busy += 1
            elif ack.status == ACK_BAD_KEY:
                self.bad_key += 1
            t0 = self.sent_at.pop(ack.seq, None)
            if t0 is not None:
                self.rtts.append(time.monotonic() - t0)

    def run(self, seconds: float, state_at) -> None:
        """Send state_at(elapsed) at 60 Hz for `seconds`."""
        start = time.monotonic()
        n = 0
        while (elapsed := time.monotonic() - start) < seconds:
            self.send(state_at(elapsed))
            n += 1
            # Wait for the next tick, handling acks the moment they arrive (accurate RTT).
            while (remaining := start + n / HZ - time.monotonic()) > 0:
                if select.select([self.sock], [], [], remaining)[0]:
                    self.poll()

    def hold(self, state: State, seconds: float) -> None:
        self.run(seconds, lambda _: state)

    def summary(self) -> str:
        self.poll()
        got = len(self.rtts)
        s = f"sent {self.sent}, acked {got}"
        if self.busy:
            s += f", {self.busy} BUSY (slot owned by another phone)"
        if self.bad_key:
            s += f", {self.bad_key} BAD KEY (the server wants a different key, or one)"
        if got:
            ms = sorted(r * 1000 for r in self.rtts)
            s += f", rtt median {ms[got // 2]:.2f} ms, max {ms[-1]:.2f} ms"
        return s


def discover(port: int, host: str, wait: float = 1.5) -> int:
    """Broadcast a discovery request on the LAN (and send it to `host`), listing who answers."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.2)
    for target in ("255.255.255.255", host):
        s.sendto(encode_discover(), (target, port))
    found = {}
    end = time.monotonic() + wait
    while time.monotonic() < end:
        try:
            data, addr = s.recvfrom(128)
        except socket.timeout:
            continue
        reply = decode_discover_reply(data)
        if reply:
            found[addr[0]] = reply
    for ip, d in found.items():
        print(f"{ip}:{d.port}  {d.name!r}  ({d.players} player(s))")
    if not found:
        print("no server answered")
    return 0 if found else 1


def axis(v: float) -> int:
    return max(-32768, min(32767, round(v * 32767)))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9876)
    ap.add_argument("--slot", type=int, default=0, help="0 = player 1, 1 = player 2")
    ap.add_argument("--key", help="shared key, when the server requires one (see server.py --new-secret)")
    ap.add_argument(
        "--delay",
        type=float,
        default=0.0,
        help="seconds to wait before sending, to give you time to focus the game window",
    )
    sub =ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("press", help="tap buttons, e.g. A or DPAD_DOWN,A")
    p.add_argument("buttons")
    p.add_argument("--hold", type=float, default=0.15)
    p.add_argument("-n", type=int, default=1, help="number of taps")

    p = sub.add_parser("stick", help="hold the left stick at X Y (-1..1; +y is down)")
    p.add_argument("x", type=float)
    p.add_argument("y", type=float)
    p.add_argument("--hold", type=float, default=1.0)

    p = sub.add_parser("circle", help="sweep the left stick around a circle")
    p.add_argument("--seconds", type=float, default=5.0)

    sub.add_parser("discover", help="broadcast a discovery request and list the servers that answer")

    args = ap.parse_args()
    if args.cmd == "discover":
        return discover(args.port, args.host)
    c = Client(args.host, args.port, args.slot, normalize_key(args.key).encode() if args.key else None)

    try:
        if args.delay > 0:
            print(f"sending in {args.delay:g}s: switch to the game window now", flush=True)
            time.sleep(args.delay)
        if args.cmd == "press":
            try:
                buttons = sum(Btn[n.strip().upper()] for n in args.buttons.split(","))
            except KeyError as err:
                print(f"unknown button {err}; choose from {', '.join(b.name for b in Btn)}")
                return 2
            for _ in range(args.n):
                c.hold(State(buttons=buttons), args.hold)
                c.hold(NEUTRAL, 0.15)
        elif args.cmd == "stick":
            c.hold(State(lx=axis(args.x), ly=axis(args.y)), args.hold)
        elif args.cmd == "circle":
            c.run(
                args.seconds,
                lambda t: State(lx=axis(math.cos(t * math.tau)), ly=axis(math.sin(t * math.tau))),
            )
    except KeyboardInterrupt:
        pass
    finally:
        c.hold(NEUTRAL, 0.2)  # always release everything
    print(c.summary())
    return 0


if __name__ == "__main__":
    sys.exit(main())
