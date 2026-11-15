# Wire protocol (v1)

UDP, default server port **9876**. All integers little-endian. Reference
implementation: `server/protocol.py` (keep this file and that one in sync).

## Phone -> server: state packet (12 bytes)

Every packet carries the **full** controller state, so a lost packet can never
leave a button stuck.

| Offset | Type   | Field   | Notes |
|-------:|--------|---------|-------|
| 0      | u8     | magic   | `0xC7`. Anything else is dropped |
| 1      | u8     | slot    | `0` = Player 1, `1` = Player 2 |
| 2      | u16    | seq     | Increments per packet, wraps at 65535. Older/duplicate packets are dropped |
| 4      | u16    | buttons | Bitmask, see below |
| 6      | i16    | lx      | Left stick X, -32768..32767, +right |
| 8      | i16    | ly      | Left stick Y, -32768..32767, **+down** (evdev convention) |
| 10     | u8     | lt      | Left trigger 0..255 |
| 11     | u8     | rt      | Right trigger 0..255 |

### Button bits

| Bit | Button | Bit | Button | Bit | Button |
|----:|--------|----:|--------|----:|--------|
| 0 | A | 5 | RB | 10 | R3 |
| 1 | B | 6 | Start | 11 | D-pad up |
| 2 | X | 7 | Select/Back | 12 | D-pad down |
| 3 | Y | 8 | Mode/Guide | 13 | D-pad left |
| 4 | LB | 9 | L3 | 14 | D-pad right |

Unknown bits are ignored. D-pad bits become the hat axes (left+right cancel).

## Server -> phone: ack (5 bytes)

Sent back to the source address for every accepted (or refused) packet.

| Offset | Type | Field  | Notes |
|-------:|------|--------|-------|
| 0      | u8   | magic  | `0xC7` |
| 1      | u8   | slot   | Echo |
| 2      | u16  | seq    | Echo of the packet being acked, for RTT measurement |
| 4      | u8   | status | `0` OK, `1` BUSY (another phone owns this slot), `2` BAD_KEY (see Authentication) |

Duplicate/stale packets and malformed packets get no ack.

## Discovery (finding the server without typing its address)

A phone broadcasts a request to the server port (`255.255.255.255` and each subnet's broadcast
address); every server answers the sender directly. A discovery request never creates a pad or
claims a slot.

Request (3 bytes): `C7 D0 01` = magic, type `0xD0`, version `1`. Extra trailing bytes are ignored,
so later versions can add data.

Reply:

| Offset | Type | Field | Notes |
|-------:|------|-------|-------|
| 0 | u8 | magic | `0xC7` |
| 1 | u8 | type | `0xD1` |
| 2 | u16 | port | Port to send state packets to |
| 4 | u8 | players | Number of slots |
| 5 | u8 | name length | Bytes, at most 32 |
| 6 | bytes | name | UTF-8, the server's label (default: the hostname) |

## Authentication (optional shared key)

If the server has a key (`server.py --new-secret` stores one in `~/.config/android-controller/secret`),
it only accepts state packets that carry a tag:

- The key is typed on the phone, so it is **normalised** first: keep only `A-Z a-z 0-9`, upper-case it
  (`ab-12` and `AB12` are the same key). The normalised text, as UTF-8, is the HMAC key.
- The packet grows from 12 to **20 bytes**: the usual 12 bytes, then the first 8 bytes of
  `HMAC-SHA256(key, those 12 bytes)`.
- A packet that is unsigned or has a wrong tag gets an ack with status `2` (`BAD_KEY`) and has no other
  effect: no pad is created, no slot is claimed. The phone shows "the PC wants a key".
- A server without a key accepts both sizes and ignores the tag, so a phone that has a key still works.
- Discovery requests and replies are never signed, and acks are not signed either.

What it protects against: other devices on the same Wi-Fi sending input to your PC by mistake, or
casually. What it does not: someone who can read your Wi-Fi traffic can replay a packet they captured
while the real phone is silent (the effect is one stale input lasting at most half a second).

How strong it is depends on the key's length, because the tag travels on the wire and anyone who
captures one packet can try every possible key offline. The default key from `--new-secret` is **4
digits** (10,000 possibilities), which is broken in well under a second, so it only keeps accidental
and casual senders out. `--new-secret --key-length 12` gives about 40 bits, which is far harder to
guess. It is meant for a home network, not for the open internet.

## Slot ownership

- The first phone to send to a slot owns it, identified by its source `ip:port`.
- A different `ip:port` on an owned slot gets `BUSY` and is ignored.
- If an owner sends nothing for **0.5 s** the server releases all its inputs
  (neutral state) and frees the slot. The virtual pad stays alive.
- Consequence for the app: keep **one UDP socket** for the whole session. A new
  socket means a new source port, which is `BUSY` for up to 0.5 s after the old
  one stops.

## Sending rules for the app

- Send immediately on any state change, plus a heartbeat of about 60 Hz while
  connected (the heartbeat is also what keeps the slot owned).
- Send a neutral state when the app is paused or loses focus.
