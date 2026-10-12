# Security and privacy

## Data handled

| Data | Where it goes | Retention |
|------|---------------|-----------|
| Microphone audio | In memory → local speech model | Discarded after recognition. Never written to disk, never transmitted. |
| Camera images / imported photos | In memory → local OCR | Discarded on retake or leaving the screen. Not saved. |
| Typed / recognised text | Local translation model | Only in history if history is enabled (on by default for text, voice and camera; off for conversations). |
| Conversation messages | The other phone only, encrypted | Local history only if "Save conversation transcripts" is on. |
| Original words in a conversation | Sent only if "Share my original words" is on | as above |
| Device identity key | Android Keystore (non-exportable EC P-256) | Until app data is cleared |
| ICE/TURN credentials | Preferences, encrypted with a Keystore AES-256-GCM key | Until changed |
| Language packs | `noBackupFilesDir` | Until deleted |

- `allowBackup=false` and data-extraction rules exclude everything from cloud backup and device transfer.
- Cleartext traffic is disabled (`network_security_config.xml`).
- No analytics, crash reporting, ads or remote config SDKs. Logs never contain user text (native code
  logs only warnings from the runtimes).

## Network behaviour (by design)
- **Offline features (text, voice, camera, packs already installed)**: no network calls. The native
  libraries import no socket functions (`nm -D` on `libmx_ai.so` and `libmx_ocr.so`, see the
  verification report). Tesseract is built without curl, and llama.cpp without OpenSSL/networking.
- **Pack downloads**: only on a user action, after a consent dialog showing the size; HTTPS only;
  only URLs from the bundled manifest; each file is checked for size and SHA-256 before it is moved into place.
- **LAN / Wi-Fi Direct / Bluetooth conversations**: traffic stays on the local link between the two phones.
- **Internet conversations**: WebSocket (`wss://` only) to the signaling server you configure, plus
  STUN/TURN servers you configure. No server is hard-coded. The relay sees IP addresses, timing,
  room ids and SDP/ICE (incl. DTLS fingerprints); it does not see message content.

Verify on a device with `docs/OFFLINE_TESTING.md` (airplane mode plus checking for unexpected sockets).

## Threat model and mitigations

| Threat | Mitigation |
|--------|-----------|
| Nearby attacker connects to a hosted session | Needs the 256-bit secret from the QR code, which is checked by the HMAC join proof before any message is processed. The QR expires after 15 min. After the first guest joins, other guest ids are rejected. |
| Man in the middle on LAN/Wi-Fi Direct/BLE | Guest pins the host certificate fingerprint from the QR. Proofs bind both certificate fingerprints. Users compare the 6-digit SAS. |
| Malicious signaling relay | Cannot read DTLS data. Cannot splice its own DTLS endpoints, because proofs bind both DTLS fingerprints and need the secret. With QR pairing the secret has 256 bits. With a typed code it has 50 bits (PBKDF2, 120k iterations): an operator who records the SDPs could brute-force offline in principle. Use QR pairing when that matters; the relay also rate-limits joins per IP. |
| Malformed or oversized messages | Hard limits before parsing (64 KiB frames). Strict JSON (unknown fields rejected). Per-type validation. Bounded queues and buffers. Disconnect after repeated violations. |
| Replay / duplicate delivery | Sequence numbers per sender, cumulative acks, duplicates dropped; messages are bound to the session id. |
| Tampered or substituted model files | Pinned SHA-256 and size for downloads. Atomic install. Architecture check from the GGUF header before native load. "Verify files" re-hashes on demand. User-imported unpinned files are labelled "Imported by you", and the user must confirm a warning first. |
| Zip-slip in voice data | Canonical-path check on every entry during extraction. |
| Native crash surface | Inputs are size-checked in Kotlin and again in JNI (e.g. pixel buffer bounds). Unsupported CPUs are detected before loading. Only JNI symbols are exported from each library. |

## Cryptography used (platform implementations only)
TLS 1.3 (JSSE/Conscrypt), DTLS (WebRTC), HMAC-SHA256, SHA-256, PBKDF2-HMAC-SHA256, AES-256-GCM
(Android Keystore), EC P-256 (Android Keystore), `SecureRandom`. No custom ciphers or key exchanges.

## Known limitations
- Session secrets are kept in memory for the session's lifetime (needed for reconnection).
- The SAS check relies on users actually comparing the numbers. A mismatch ends the session.
- BLE advertisements reveal that a ModuleX session is nearby (an 8-byte tag derived from the session
  id), not who is in it.
- The internet relay operator learns participants' IP addresses (inherent to WebRTC signaling). Use a
  TURN relay to hide peer IPs from each other.
