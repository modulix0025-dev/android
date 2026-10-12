# Offline verification procedure

Record the device, app version, pack versions and the state of every radio for each run.
Mark each step PASS/FAIL with evidence (screenshots, `adb` output).

## Single device
1. Install the APK. Install **Offline Translation Engine**, **Speech Recognition Engine** and any OCR packs.
2. Language Packs → each installed pack → **Verify files**. All must show "Verified".
3. Enable **airplane mode**, then check: Wi-Fi OFF, Bluetooth OFF, mobile data OFF.
   ```bash
   adb shell settings get global airplane_mode_on      # 1
   adb shell dumpsys wifi | grep -m1 "Wi-Fi is"         # disabled
   adb shell dumpsys bluetooth_manager | grep -m1 "enabled:"
   ```
4. Text translator: ar→en and en→ar with corpus sentences (`engines/src/test/resources/corpus.tsv`).
5. Voice translator: speak 5 Arabic and 5 English phrases; note the displayed latency.
6. Camera: photograph Arabic and English printed text; translate.
7. Spoken output: play a translation (needs an offline system voice for that language).
8. Look for hidden network attempts while repeating 4–7:
   ```bash
   adb logcat -c; <repeat steps>; adb logcat -d | grep -iE "UnknownHost|SocketTimeout|ECONNREFUSED|network.*fail" | grep -i modulex
   adb shell cat /proc/$(adb shell pidof com.modulex.translator.debug)/net/tcp6   # expect no ESTABLISHED remote sockets
   ```
   Also: Settings → Network → Data usage for the app should stay at 0 B.

## Two devices, no internet
Use two phones, both with **mobile data OFF** and no internet uplink.
1. **Wi-Fi Direct**: Wi-Fi ON (not connected to any AP), Bluetooth OFF. Host → Wi-Fi Direct → guest scans. Exchange 20 messages each way (voice and text).
2. **Hotspot**: host enables its hotspot with mobile data OFF; guest joins it. Mode "Same Wi-Fi or hotspot".
3. **Bluetooth**: airplane mode ON, then Bluetooth ON only. Mode Bluetooth.
4. For each: confirm the SAS matches, measure latency (shown in the top bar), toggle the guest's
   Wi-Fi/Bluetooth off for 10 s and back on (expect *Reconnecting…*, then delivery of queued messages
   without duplicates), and end from either side.

Single-device offline and two-device offline are separate results: record them separately.

## Automated evidence available without a device
- `./gradlew :core:test`: protocol, auth, TLS, reconnect/replay over real loopback TCP.
- `./gradlew :engines:test`: native OCR + translation pipeline through JNI on the host.
- `nm -D build/native-host/libmx_*.so | grep -wE "socket|connect|getaddrinfo"`: no output expected.
  For device builds run the same with the NDK's `llvm-nm` on `app/build/intermediates/merged_native_libs/**/libmx_*.so`.
