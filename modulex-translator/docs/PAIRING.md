# Connecting two phones

Each person installs ModuleX Translator and the language packs for **their own** language
(translation engine + speech engine). The speaking phone does the translating.

## Start (host phone)
1. **Live Conversation** → enter your name, *I speak*, *Show me* (usually the same language).
2. **Start a conversation** tab → choose a connection:
   - **Same Wi-Fi or hotspot**: both phones on the same network, or the guest joins the host's
     mobile hotspot. Mobile data can be off.
   - **Wi-Fi Direct**: no router at all. The host creates a private Wi-Fi Direct group.
   - **Bluetooth**: within roughly 10 m, no Wi-Fi needed.
   - **Internet**: phones far apart. Requires a connection server in Settings (`wss://…`, see
     `signaling/README.md`) and optionally a TURN server.
3. Tap **Start a conversation** and allow the requested permissions. A QR code appears (plus a pairing
   code in Internet mode).

## Join (guest phone)
1. **Live Conversation** → name and languages → **Join a conversation**.
2. **Scan the other phone's code**, or for Internet mode type the pairing code.
3. Both phones show a **6-digit number**. Check that they match and tap **Numbers match**. If they
   differ, tap **They don't match**: the connection ends because something is intercepting it.

## Talking
- **Hands-free**: just speak. Each phrase is translated when you pause.
- **Hold to talk**: hold the button while speaking.
- **Type**: send text.
- **Read translations aloud** speaks incoming translations; the microphone pauses meanwhile.
- **Share my original words** controls whether your untranslated words are also sent.
- The bar shows the active mode (offline or internet) and the measured one-way latency.
- If the connection drops, both phones show *Reconnecting…*. Messages sent meanwhile are delivered
  once the link is back, without duplicates.
- **End conversation** ends it on both phones. A notification is shown while a conversation runs;
  its **End** action also ends it.
