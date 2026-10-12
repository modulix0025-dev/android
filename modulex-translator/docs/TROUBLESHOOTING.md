# Troubleshooting

| Symptom | Cause | What to do |
|---|---|---|
| "This phone's processor can't run the offline engines" | CPU lacks ARMv8.2 dot-product/FP16, or the device is 32-bit | Not supported by this build. A baseline ARMv8.0 build is possible by changing `GGML_CPU_ARM_ARCH` (slower). |
| "The Offline Translation Engine isn't installed" | No usable translation pack | Language Packs → install, or Import from file. |
| Pack shows "Not yet available for download" | Pack has no pinned hash yet | Release engineer: run `tools/pin_assets.py`. Users: Import from file. |
| "The translation engine couldn't start" | Wrong or corrupt GGUF; architecture mismatch; low memory | Verify files; check with `tools/check_gguf.py`; close other apps. |
| GGUF fails with unknown tensor type | Vendor low-bit (STQ) model | Use a standard k-quant GGUF (Q4_K_M etc.). |
| "Download interrupted" | Network drop | Tap Install again; it resumes from the partial file. |
| "didn't match its fingerprint" | Corrupted download or a changed upstream file | The file was discarded automatically. Retry; if it persists the upstream file changed: re-pin. |
| Voice: "No speech detected" | Too quiet, or noise only | Speak closer; reduce background noise. |
| Spoken output unavailable | No offline system voice for that language | Install offline voice data in Android TTS settings, or use the Piper build. |
| Camera: little or no text read | Glare, blur, tiny or decorative fonts; known gaps in fast Arabic data | Fill the frame, avoid glare, retake. See BENCHMARKS.md for known Arabic OCR limits. |
| Conversation: "Connect both phones to the same Wi-Fi network or hotspot" | Host has no local IPv4 address | Join a Wi-Fi network or enable hotspot, or use Wi-Fi Direct/Bluetooth. |
| Guest stuck on "Connecting…" (LAN) | AP client isolation (guest Wi-Fi networks often block peer traffic) | Use a hotspot or Wi-Fi Direct. |
| Wi-Fi Direct unavailable / fails | Vendor restrictions, location/nearby permission denied | Grant Nearby devices; turn Wi-Fi on; try hotspot mode. |
| Bluetooth: never connects | BT off, permissions denied, or the phone doesn't support LE advertising/L2CAP CoC | Turn on Bluetooth, grant permissions, use another mode. |
| "Couldn't verify the other phone" | Scanned an old or other session's QR, or a man in the middle | Scan the current code again. If it repeats, do not continue. |
| SAS numbers differ | Connection intercepted or wrong session | Tap "They don't match"; reconnect on a trusted network. |
| Internet: "Couldn't reach the connection server" | Wrong URL, not `wss://`, server down | Check Settings and `/healthz` on the server. |
| Internet connects then fails | Symmetric NAT, no TURN | Add a TURN server in Settings. |
| Phone warm, translation slower | Thermal policy reducing threads | Expected. At "too hot" translation pauses until the phone cools. |
