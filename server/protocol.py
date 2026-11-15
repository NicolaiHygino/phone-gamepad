"""Wire format shared by the phone app and the PC server (see PROTOCOL.md)."""

import enum
import hashlib
import hmac
import re
import struct
from dataclasses import dataclass

MAGIC = 0xC7

# magic, slot, seq, buttons, lx, ly, lt, rt
_PACKET = struct.Struct("<BBHHhhBB")
# magic, slot, seq, status
_ACK = struct.Struct("<BBHB")

PACKET_SIZE = _PACKET.size  # 12
ACK_SIZE = _ACK.size  # 5
TAG_SIZE = 8  # truncated HMAC-SHA256 appended when a shared key is used
AUTH_PACKET_SIZE = PACKET_SIZE + TAG_SIZE  # 20

ACK_OK = 0
ACK_BUSY = 1  # another phone already owns this slot
ACK_BAD_KEY = 2  # the server needs a key and this packet's tag is missing or wrong


class Btn(enum.IntFlag):
    A = 1 << 0
    B = 1 << 1
    X = 1 << 2
    Y = 1 << 3
    LB = 1 << 4
    RB = 1 << 5
    START = 1 << 6
    SELECT = 1 << 7
    MODE = 1 << 8
    L3 = 1 << 9
    R3 = 1 << 10
    DPAD_UP = 1 << 11
    DPAD_DOWN = 1 << 12
    DPAD_LEFT = 1 << 13
    DPAD_RIGHT = 1 << 14


ALL_BUTTONS = sum(b.value for b in Btn)


@dataclass(frozen=True)
class State:
    """Full controller state. Stick axes follow evdev: +x = right, +y = down."""

    buttons: int = 0
    lx: int = 0  # int16
    ly: int = 0  # int16
    lt: int = 0  # 0..255
    rt: int = 0  # 0..255


NEUTRAL = State()


@dataclass(frozen=True)
class Packet:
    slot: int
    seq: int
    state: State
    raw: bytes = b""  # the 12 bytes the tag covers
    tag: bytes | None = None

    def verified(self, secret: bytes) -> bool:
        """True if the packet carries a correct tag for `secret`."""
        return self.tag is not None and hmac.compare_digest(self.tag, sign(secret, self.raw))


@dataclass(frozen=True)
class Ack:
    slot: int
    seq: int
    status: int


def normalize_key(text: str) -> str:
    """Keys are typed on a phone, so case, dashes and spaces are ignored."""
    return re.sub(r"[^A-Za-z0-9]", "", text).upper()


def sign(secret: bytes, packet: bytes) -> bytes:
    return hmac.new(secret, packet, hashlib.sha256).digest()[:TAG_SIZE]


def encode(slot: int, seq: int, state: State, secret: bytes | None = None) -> bytes:
    """A state packet; with `secret` the 8-byte tag is appended (20 bytes instead of 12)."""
    packet = _PACKET.pack(
        MAGIC, slot, seq & 0xFFFF, state.buttons, state.lx, state.ly, state.lt, state.rt
    )
    return packet if secret is None else packet + sign(secret, packet)


def decode(data: bytes) -> Packet | None:
    """Parse a client packet (12 bytes, or 20 with a tag); None if it is not a valid one.

    The tag is only carried along here; whether it is acceptable is `Packet.verified`'s job.
    """
    if len(data) not in (PACKET_SIZE, AUTH_PACKET_SIZE):
        return None
    magic, slot, seq, buttons, lx, ly, lt, rt = _PACKET.unpack_from(data)
    if magic != MAGIC:
        return None
    return Packet(
        slot,
        seq,
        State(buttons & ALL_BUTTONS, lx, ly, lt, rt),
        raw=bytes(data[:PACKET_SIZE]),
        tag=bytes(data[PACKET_SIZE:]) or None,
    )


# --- Discovery: a phone finds the server on the LAN without typing an address ---------------

DISCOVER_REQUEST = 0xD0
DISCOVER_REPLY = 0xD1
MAX_NAME = 32  # bytes of UTF-8

_DISCOVER = bytes([MAGIC, DISCOVER_REQUEST, 1])  # magic, type, version
# magic, type, port, players, name length; the name bytes follow
_DISCOVER_REPLY = struct.Struct("<BBHBB")


@dataclass(frozen=True)
class Discovered:
    port: int
    players: int
    name: str


def encode_discover() -> bytes:
    return _DISCOVER


def is_discover(data: bytes) -> bool:
    """A discovery request: magic, type 0xD0, then a version byte (extra bytes are ignored)."""
    return len(data) >= 3 and data[0] == MAGIC and data[1] == DISCOVER_REQUEST


def encode_discover_reply(port: int, players: int, name: str) -> bytes:
    raw = name.encode("utf-8")[:MAX_NAME].decode("utf-8", "ignore").encode("utf-8")  # no split characters
    return _DISCOVER_REPLY.pack(MAGIC, DISCOVER_REPLY, port, players, len(raw)) + raw


def decode_discover_reply(data: bytes) -> Discovered | None:
    if len(data) < _DISCOVER_REPLY.size:
        return None
    magic, kind, port, players, n = _DISCOVER_REPLY.unpack_from(data)
    if magic != MAGIC or kind != DISCOVER_REPLY or len(data) != _DISCOVER_REPLY.size + n:
        return None
    return Discovered(port, players, data[_DISCOVER_REPLY.size :].decode("utf-8", "replace"))


def encode_ack(slot: int, seq: int, status: int) -> bytes:
    return _ACK.pack(MAGIC, slot, seq & 0xFFFF, status)


def decode_ack(data: bytes) -> Ack | None:
    if len(data) != ACK_SIZE:
        return None
    magic, slot, seq, status = _ACK.unpack(data)
    if magic != MAGIC:
        return None
    return Ack(slot, seq, status)
