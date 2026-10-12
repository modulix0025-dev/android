# Conversation protocol (v1)

Implementation: `core/src/main/kotlin/com/modulex/translator/core/{protocol,security,conversation,transport}`.
Tests: `core/src/test/...` (`ProtocolTest`, `ReliabilityTest`, `PairingTest`, `TlsTransportTest`,
`ConversationSessionTest`, `LoopbackLatencyTest`).

## Layers

1. **Link**: TCP (LAN / Wi-Fi Direct), BLE L2CAP (Bluetooth) or a WebRTC data channel (Internet).
2. **Encryption**: TLS 1.3 (JSSE `SSLEngine` over the stream, any transport) or DTLS (WebRTC).
   Each phone has an EC P-256 key in the Android Keystore with a self-signed certificate.
3. **Framing**: 4-byte big-endian length + UTF-8 JSON, 1 B to 64 KiB (WebRTC uses message boundaries).
4. **Session**: authenticated handshake, then sequenced reliable messages with acknowledgements.

## Pairing payload (QR)

`modulex://pair?v=1&s=<sessionId>&k=<secret>&t=<lan|p2p|bt|net>&n=<host name>&e=<expiry ms>&...`

| Key | Meaning |
|-----|---------|
| `s` | session id, 128-bit random, base64url |
| `k` | 256-bit session secret, base64url. **Never leaves the two phones** except via the QR code |
| `f` | SHA-256 of the host's TLS certificate (LAN/P2P/BT): the guest pins it |
| `a`,`p` | host IPv4 address and port (LAN/P2P) |
| `wn`,`wp` | Wi-Fi Direct group name and passphrase (P2P) |
| `u`,`r` | signaling URL (must be `wss://`) and room (Internet) |
| `e` | expiry (QR codes are valid for 15 minutes) |

Typed Internet pairing codes are 10 Crockford base32 characters (50 bits), shown as `XXXXX-XXXXX`.
`PBKDF2-HMAC-SHA256(code, 120 000 iterations)` derives the 256-bit secret and, separately, the room
id, so the relay never learns the secret.

## Handshake

```
guest → host  HELLO      {sessionId, senderId, displayName, sourceLang, targetLang, ackSeq,
                          proof = HMAC(secret, "modulex-join-v1", sessionId, guestId, guestFp, hostFp)}
host  → guest HELLO_ACK  {displayName, languages, ackSeq,
                          proof = HMAC(secret, "modulex-host-v1", sessionId, hostId, guestId, guestFp, hostFp)}
```
- `guestFp`/`hostFp` are the certificate fingerprints *of this connection* (TLS certificates, or DTLS
  fingerprints from the SDPs). Every field is length-prefixed inside the HMAC.
- Wrong secret, wrong fingerprints (man in the middle), wrong session or a different guest id after the
  first join → `ERROR` and close. The host keeps listening; an intruder does not end the session.
- Both phones show a 6-digit **short authentication string**,
  `HMAC(secret, "modulex-sas-v1", sessionId, hostFp, guestFp) mod 10^6`, and the users confirm it matches.
- `ackSeq` lets each side drop what the peer already has and replay the rest after a reconnect.

## Messages

`WireMessage` (unknown fields are rejected): `v, type, sessionId, messageId, senderId, seq,
timestamp, sourceLang, targetLang, originalText?, translatedText?, displayName?, proof?, ackSeq?,
nonce?, errorCode?`.

| Type | Reliable | Purpose |
|------|----------|---------|
| `hello`, `hello_ack` | handshake | authentication, see above |
| `utterance` | yes | translated text (+ original if the sender allows it), languages |
| `lang` | yes | participant changed languages |
| `bye` | yes | orderly end |
| `ack` | no | cumulative: highest contiguous seq received |
| `ping` / `pong` | no | every 5 s; RTT → latency indicator; 15 s silence ⇒ link dead |
| `error` | no | fatal error code, then close |

Validation, applied to every inbound message: protocol version, session id, sender id (after join),
id formats, `seq ≥ 1` only on reliable types, language code format, text ≤ 8,000 chars, name ≤ 64,
the required fields per type, and no unexpected text fields. More than five violations close the link.

## Reliability
- The sender assigns `seq` (1, 2, …) to reliable messages and keeps them until a cumulative ack
  arrives. It retransmits after 3 s; after 5 attempts the link is treated as broken and the whole
  queue is replayed on reconnection. The outbound queue is bounded (128). When full, sending fails
  visibly ("Not delivered").
- The receiver delivers exactly once and in order. Duplicates (replays) are acked and dropped;
  out-of-order messages are buffered (up to 64) until the gap closes. A too-far-ahead sequence is
  rejected.
- Delivery status in the UI: Sending… → Delivered (on ack) or Not delivered.

## Internet signaling
See `signaling/README.md`. The client joins `wss://<server>/v1/rooms/<room>`. The conversation
host sends the WebRTC offer when the peer is present; ICE candidates trickle as
`{"t":"ice","mid","idx","c"}`, SDP as `{"t":"offer"|"answer","sdp"}`. The data channel is
negotiated (`id 0`, ordered, reliable). The relay connection closes once the channel is open.
