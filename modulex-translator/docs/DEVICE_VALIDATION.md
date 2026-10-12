# Hardware validation procedure (target: OPPO Reno, 12 GB RAM, 256 GB)

"OPPO Reno 12 GB" covers several SoCs (Snapdragon and Dimensity generations). Discover the exact
device first, and report every measurement with this header.

## 1. Device discovery
```bash
adb shell getprop ro.product.manufacturer; adb shell getprop ro.product.model
adb shell getprop ro.build.version.release; adb shell getprop ro.build.version.sdk
adb shell getprop ro.soc.manufacturer; adb shell getprop ro.soc.model; adb shell getprop ro.board.platform
adb shell getprop ro.product.cpu.abilist
adb shell cat /proc/cpuinfo | grep -m1 Features        # need asimddp (dot product) and fphp/asimdhp
adb shell cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq
adb shell dumpsys SurfaceFlinger | grep -m1 GLES        # GPU (not used by the engines yet)
adb shell cat /proc/meminfo | head -3
adb shell df -h /data
adb shell dumpsys thermalservice | head -20
```
The debug build's **Settings → Diagnostics** shows the same information plus loaded engines, PSS and
thermal state.

## 2. Model loading and translation latency
1. Diagnostics → **Run benchmark** (4 fixed sentences, ar↔en). Run it 3 times: the first run includes
   the cold load (`last load … ms`), later runs are warm.
2. Text translator: translate each `corpus.tsv` row 5× and record the debug metrics line
   (total / prefill / decode / tokens). Report the median and P95.
3. Long text: the paragraph row, and a 3,000-character article.

Also from a shell with the host-side harness (optional, same native code as the app):
```bash
./gradlew :engines:test -Pmodulex.testModel=/path/model.gguf      # on a Linux/ARM board
```

## 3. Memory
```bash
PID=$(adb shell pidof com.modulex.translator.debug)
adb shell dumpsys meminfo $PID | grep -E "TOTAL PSS|TOTAL RSS|Native Heap"
adb shell cat /proc/$PID/status | grep -E "VmRSS|VmHWM"      # VmHWM = peak RSS
```
Measure after: app start; translation engine loaded; text translation; voice translation (both engines
loaded); camera OCR + translation. Compare with the planning targets (text 2–4 GB, speech 3–6 GB,
combined 4–7 GB). These targets are estimates, not results.

## 4. Speech pipeline latency
The voice translator shows "Translated in X s" per phrase: end of speech (VAD hangover start) → translation
complete. Record 20 short phrases per direction; report median/P95. Phrase-end detection itself adds
the 0.7 s hangover by design.

## 5. Two-device transport latency
The conversation top bar shows one-way latency (RTT/2 from protocol pings). Record it for each transport
for 5 minutes. Delivery latency: compare timestamps of the debug `pipelineMs` field in history exports,
or use the loopback benchmark as the protocol-overhead lower bound (`LoopbackLatencyTest`).

## 6. Battery and thermal (15–30 min each)
```bash
adb shell dumpsys batterystats --reset
# run the scenario: continuous voice translation / repeated text / camera OCR every 20 s / conversation
adb shell dumpsys batterystats com.modulex.translator.debug > batterystats.txt
adb shell dumpsys thermalservice > thermal.txt          # status + temperatures
adb shell top -b -n 1 | grep modulex                     # CPU %
```
Watch for: thermal status ≥ SEVERE (the app reduces threads and disables hands-free), PSS growth
across 50 translations (leak check), native crashes (`adb logcat -b crash`), ANRs, process deaths.

## 7. Report template
| Measurement | Device / SoC / Android | Pack versions | Median | P95 | n | Notes |
|---|---|---|---|---|---|---|
