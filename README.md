# RaceChrono Bridge

Android bridge app for reading Subaru SSM2 over CAN through an iCar Pro 2S BLE ELM327 adapter and streaming calculated telemetry to RaceChrono DIY TCP/IP.

The app does not modify RaceChrono. It exposes a local RaceChrono DIY RC3 TCP stream on `127.0.0.1:9876`.

Important: enable the RaceChrono DIY `RC2/RC3` API only. Do not enable `NMEA 0183`, because that makes the DIY device behave like a GPS receiver and can conflict with RaceChrono's internal GPS receiver.

## Current MVP

- Offline fake telemetry mode for RaceChrono connection testing without the car.
- BLE scan/connect for likely ELM327 adapters such as `Android-Vlink`, `V-LINK`, `vLink`, `Vgate`, and `iCar`.
- Paired Bluetooth adapters are listed before scan results and are connected through Classic Bluetooth SPP first.
- BLE serial characteristic discovery by write/notify capability instead of hard-coded UUIDs.
- TCP server starts from a `connectedDevice` foreground service.
- The foreground service owns Bluetooth connection, ELM327 initialization, SSM2 polling, RaceChrono TCP streaming, logs, and scan results so the bridge keeps running while RaceChrono is foregrounded.
- The last successful Bluetooth adapter is saved and auto-selected on the next launch.
- Bluetooth connection success automatically starts ELM327 initialization and SSM2 polling.
- Per-channel `Off` / `Fast` / `Slow` settings are available in the app and are persisted locally.
- Custom SSM2 channels can be pasted or opened from a CSV/text file. A custom channel replaces one existing RC3 field such as `Analog 15`.
- A built-in standard tuning preset can load common SSM2 parameters such as A/F Correction, A/F Learning, IAM, Fine Learning Knock Correction, A/F Sensor, and Engine Load into custom RC3 analog slots.
- ELM327 initialization for SSM2 over CAN:
  - `ATZ`
  - `ATE0`
  - `ATL0`
  - `ATS0`
  - `ATH0`
  - `ATSP6`
  - `ATCAF1`
  - `ATSH7E0`
- SSM2 polling uses fast and slow channel groups. Fast channels are read every polling cycle; slow channels rotate a few at a time and keep their previous value between updates.
- Calculated telemetry:
  - `rpm = ((high << 8) + low) / 4.0`
  - `boostKpa = (boostRaw - 128.0) * 37.0 / 255.0 * 6.89476`
  - `coolantC = coolantRaw - 40.0`
  - `gear = gearRaw + 1` only when `gearRaw` is `0..7`; `0xFF` is treated as unknown and left blank
  - throttle %, accelerator %, primary WGDC %, vehicle speed, gear, intake air temperature, battery voltage, mass airflow, ignition timing, knock correction, learned ignition timing, injector pulse width, fuel pump duty, alternator duty
- Debug log display and clipboard copy.
- Startup diagnostics show the previous Android process exit reason and any captured uncaught crash in the debug log.

SSM2 value handling is aligned with public RomRaider Subaru logger definitions. Most byte values use the full `0..255` range: for example duty/position percentages intentionally allow `0xFF` as `100%`. Gear position is different because it is a discrete field; `0xFF` is treated as unknown/not available and is sent blank to RaceChrono instead of `256`.

## App Flow

### Offline RaceChrono test

1. Open RaceChrono Bridge.
2. Tap `Fake 5 Hz`.
3. In RaceChrono, connect a RaceChrono DIY TCP/IP device to `127.0.0.1:9876`.
4. Enable `RC2/RC3`; keep `NMEA 0183` off.

### iCar Pro 2S / SSM2 test

1. Turn RaceChrono OBD-II reader off. The iCar Pro 2S should be connected only by this bridge app.
2. Tap `Scan Bluetooth`.
3. Select the iCar Pro 2S device from the `BLE devices` list. A `paired` device uses Classic Bluetooth SPP first, then falls back to BLE GATT if SPP fails.
4. ELM327 initialization and SSM2 polling start automatically after Bluetooth connection succeeds.
5. Leave the `RaceChrono Bridge running` notification active, then switch to RaceChrono.
6. Connect RaceChrono DIY TCP/IP to `127.0.0.1:9876`.
7. Enable `RC2/RC3`; keep `NMEA 0183` off.

## Acquisition Modes

`取得方法` in the connection section switches between two modes. The choice is saved.

- `CAN 受信 + SSM2` (default): reads the GH8 CAN broadcast on the OBD port with `ATMA` and publishes to RaceChrono at 20 Hz. Every 1.5 s the app switches the adapter to SSM2 (`ATH0`, `ATCAF1`), reads the channels the broadcast does not carry, and switches back (`ATH1`, `ATCAF0`). When every SSM2 channel is off, `ATMA` runs without interruption.
- `SSM2 のみ`: the original request/response polling for every channel.

Built-in channels taken from CAN in `CAN 受信 + SSM2` mode: RPM, Gear, Vehicle speed, Accelerator, Coolant.

GH8 broadcast layout (from a 5-minute drive capture; IDs are 11-bit, bytes are B0-B7):

| Signal | ID / bytes | Decoding | Confidence |
|---|---|---|---|
| RPM | `410` B5 low, B6 high | rpm | high |
| Accelerator | `410` B4 | raw × 0.8 % (100 % assumed at 0x7D) | signal high, scale estimated |
| Gear | `411` B4 | 1-5; 6 = neutral or clutch pressed (sent blank) | high |
| Clutch switch | `411` B0 bit `0x40` or `0x80` | 1 = pressed | high |
| Brake switch | `411` B6 bit `0x10` (also `512` B4) | 1 = pressed | high |
| Vehicle speed | `512` B2 low, B3 high | raw × 0.05625 km/h | signal high, scale estimated |
| Wheel speeds 1-4 | `513` B0-1, B2-3, B4-5, B6-7 | raw × 0.05625 km/h; which wheel is which is unknown | signal high, scale estimated |
| Handbrake | `514` B0 bit `0x04` | 1 = applied | medium |
| Coolant | `600` B3 | raw − 40 °C | medium |

Not yet verified: the "estimated" scales were inferred from one drive without a reference reading, and the "medium" signals were matched by timing only. Compare them with SSM2 values (vehicle speed, accelerator, coolant) and with a marker capture (brake, clutch, handbrake) before relying on them.

CAN-only signals can be placed on any RC3 slot as a custom channel with `can:<signal>` as the address. Supported signals: `rpm`, `speed`, `wheel1`-`wheel4`, `accelerator`, `brake`, `clutch`, `handbrake`, `gear`, `coolant`. The `CAN 信号` preset assigns:

```csv
slot,label,unit,address,bytes,scale,offset,mode,signed
Analog 9,Brake,,can:brake,1,1,0,Fast,false
Analog 10,Clutch,,can:clutch,1,1,0,Fast,false
Analog 11,Wheel 1,km/h,can:wheel1,1,1,0,Fast,false
Analog 12,Wheel 2,km/h,can:wheel2,1,1,0,Fast,false
Analog 13,Wheel 3,km/h,can:wheel3,1,1,0,Fast,false
Analog 14,Wheel 4,km/h,can:wheel4,1,1,0,Fast,false
Analog 15,Handbrake,,can:handbrake,1,1,0,Fast,false
```

`scale` and `offset` apply on top of the decoded value. In `SSM2 のみ` mode, `can:` channels stay blank.

## CAN Capture

`CAN capture` records the passive CAN broadcast on the OBD port with ELM327 `ATMA` and saves it as CSV, for working out which ID and byte carries pedal, switch, and gear signals. GH8 broadcasts frames such as `410`, `411`, `412`, `511`-`514`, `600`, `601`, and `620` on the OBD port.

1. Connect the adapter as usual.
2. Tap `記録を開始` in the `CAN 記録` section. SSM2 polling pauses while recording.
3. Tap a marker (`アイドル`, `アクセル`, `ブレーキ`, `クラッチ`, `ギア`, `灯火類`, `走行`, `その他`) when you start each action. The CSV stores English keys (`idle`, `throttle`, `brake`, `clutch`, `gear`, `lights`, `drive`, `mark`).
4. Tap `記録を止める`. SSM2 polling resumes automatically.
5. Tap `CSV を書き出す` to save the CSV through the Android save dialog.

ELM327 adapters stop `ATMA` with `BUFFER FULL` when Bluetooth cannot keep up. The app restarts `ATMA` automatically and logs each restart, so short gaps can appear in the capture. Capture aborts after 5 consecutive `ATMA` runs without frames.

CSV format:

```csv
elapsed_ms,kind,id,data
1234,F,410,09001B0F00E702C1
1250,M,,brake
1300,S,,BUFFER FULL
```

`kind` is `F` (frame), `M` (marker), or `S` (adapter status). Monitoring uses `ATS0`, `ATH1`, and `ATCAF0`; the adapter stays silent on the bus (ELM327 default `ATCSM1`).

## Logger

The `ロガー` section records SSM2 parameters continuously to a RomRaider-style CSV (`Time (msec),<Name> (<units>),...`). The CSV can be read by the RomRaider log tools in the GH8 tuning project without conversion.

1. Connect the adapter as usual.
2. Pick a session (`A`-`F`) and type the ROM version (for example `mod3`, `revA`, or Japanese text). The ROM version goes into the file name; symbols and spaces are dropped.
3. Tap `記録を開始`. Normal telemetry and CAN capture pause while logging; RaceChrono keeps receiving the logged values (RPM, boost, throttle, and so on).
4. Tap `記録を止める`, then `CSV を書き出す`. The Android save dialog can save straight to OneDrive (for example `ECUdata/log`).

Sessions follow the GH8 measurement plan:

| Key | Purpose | Parameters (slow = every 10 samples) |
|---|---|---|
| A | Fuel headroom: injector duty and WOT fueling | P8 P12 P21 E87 E124 E88 P13 E92 P58 · slow P24 P11 P2 (+ calculated injector duty, boost, load) |
| B | Knock: timing and knock corrections | P8 E87 P10 E94 E95 E96 E88 P13 P60 · slow P24 P11 |
| C | Boost: overshoot, WGDC, turbo dynamics | P8 E88 E90 P36 E78 E79 E80 P13 · slow P24 P11 (+ calculated boost and target boost on the same basis) |
| D | Cruise: closed-loop correction/learning, MAF check | P8 P9 E87 P18 E82 E83 E61 E33 P10 · slow P2 |
| E | Circuit: heat build-up over laps | P8 E88 P10 E94 E95 E96 P13 P36 · slow P11 P2 P24 |
| F | Shift points: RPM, speed, throttle, boost | P8 P9 P13 E88 · slow P24 P11 |

Calculated columns:

- `Manifold Relative Pressure (Calculated) (kPa)` = E88 (MAP absolute) − P24 (atmospheric). The standard P25 is one byte and stops at +127 kPa, which is too low for GH8 boost spikes around 1.2 bar.
- `Target Boost Relative (Calculated) (kPa relative)` = E90 − P24, on the same basis as the boost above.
- `Injector Duty Cycle (Calculated) (%)` = P21 × rpm ÷ 1200 (P21 includes injector latency).
- `Engine Load (Calculated) (g/rev)` = P12 × 60 ÷ rpm (same as RomRaider P200).

Full throttle is judged with P13. E93 (throttle plate angle) is not guaranteed to read 100 % at full throttle.

How reading works:

- At the start the adapter is resynchronized (late responses are drained and `ATE0` must answer `OK`), the SSM2 settings are applied (each AT command must answer `OK`), and the ECU ID and capability flags are read with `AA`.
- If identification fails twice, `5104584007` (GH8 A2TB100U) is used and the file name gets `_ecu-unverified`. Standard parameters the ECU reports as unsupported are left out.
- Extended parameters live in ECU RAM. Nearby RAM addresses are read together with one `A0` block read; GH8's 1-2 byte extended parameters sit in `0xFF708C`-`0xFF70CB`, so most sessions need one block request. Standard parameters are read one byte at a time with `A8` (ELM327 can only send single-frame requests).
- A response is used only if its response code is in place and its length matches the request exactly; error lines from the adapter fail the whole response. After any failure the adapter is resynchronized before the next request, so a late response is never taken as the answer to a later request.
- If the ECU rejects a block read (`7F`), that block switches to `A8` at once. Other block failures switch only after 3 in a row. The status line shows when block reads are off. Block reads over CAN through the iCar Pro 2S still need to be confirmed on the car.
- Logging stops by itself after 10 consecutive failed samples. A failed slow read does not stop the fast columns.
- Two-byte standard values (P8, P12) are read as two `A8` requests, so a value can be off by one high-byte step for one sample when it crosses a byte boundary.

### Alarms

While logging, every sample is checked against fixed thresholds so the driver does not have to watch the screen. Alarms sound from the foreground service, so they also work while RaceChrono is in front. Severe alarms play a long high tone with two long vibrations; knock plays two short beeps.

| Alarm | Condition |
|---|---|
| `LEAN_AFR` | Throttle (P13) 90 % or more, boost 50 kPa or more, and A/F (E92) above 11.2 for two samples in a row. The stock A/F sensor pins at 11.15 when richer, so a value above 11.2 means the mixture is leaner than intended. Check the pinned value of E92 and P58 in the first fuel-session log; if E92 pins above 11.2, the threshold must be changed before relying on this alarm |
| `KNOCK` | Feedback knock correction (E94) drops 0.7 degrees or more from the previous sample (a new knock event; recovery steps do not trigger it) |
| `OVERBOOST` | Boost above 125 kPa (the GH8 boost cut is about 137 kPa). Uses the calculated boost (E88 − P24), which is not capped at 127 kPa like P25 |
| `INJECTOR_DUTY` | Calculated injector duty 95 % or more |

The same alarm does not repeat within 3 seconds. Alarms are always evaluated and written to the `Alarm` column of the CSV; the switch in the logger section only mutes the sound and vibration.

## SSM Parameter Catalog

`app/src/main/assets/ssm_catalog.tsv` is generated from the RomRaider logger definition and is the single source for SSM parameter addresses, storage types, and conversions used by the logger.

```sh
python3 scripts/generate_ssm_catalog.py --logger-xml <RomRaider logger.xml> --ecu-id 5104584007
```

- Includes every SSM standard parameter (`P…`) and the extended parameters (`E…`) of the listed ECU IDs. Extended addresses differ per ECU ID.
- Calculated parameters that depend on others (`P200` and similar) and bit switches are not included.
- One row per conversion; `conv_index` 0 is RomRaider's default. Column names in recorded CSV files use the RomRaider form `Name (units)` so existing RomRaider log tools can read them.
- The source definition version is written on the first line of the TSV. The current file comes from version 370 (2021-11-16).

## RaceChrono Setup

```text
OBD-II reader: OFF
Other device: RaceChrono DIY
Connection: TCP/IP
Host: 127.0.0.1
Port: 9876
API: RC2/RC3 ON
NMEA 0183: OFF
Internal GPS receiver: ON
```

RC3 channel mapping:

RaceChrono's RC3 data logger field names are fixed, so the app cannot rename `Analog 1`, `Analog 2`, etc. in RaceChrono's live view. Use this mapping to interpret those fixed labels.

| RC3 field | Value |
|---|---|
| `rpm/d1` | RPM |
| `d2` | Gear |
| `a1` | Boost kPa |
| `a2` | Coolant deg C |
| `a3` | Throttle % |
| `a4` | Accelerator % |
| `a5` | Primary WGDC % |
| `a6` | Vehicle speed km/h |
| `a7` | Intake air temperature C |
| `a8` | Battery voltage |
| `a9` | Mass airflow g/s |
| `a10` | Ignition timing degrees |
| `a11` | Knock correction degrees |
| `a12` | Learned ignition timing degrees |
| `a13` | Injector pulse width ms |
| `a14` | Fuel pump duty % |
| `a15` | Alternator duty % |

Custom channel import format:

Paste this text into `Custom SSM2 channels`, or tap `Open file` and select a `.csv` or `.txt` file with the same content.

```csv
slot,label,unit,address,bytes,scale,offset,mode,signed
Analog 15,Oil temp,C,0x000108,1,1,-40,Slow,false
```

Supported fields are `Digital 1/RPM`, `Digital 2`, and `Analog 1` through `Analog 15`. `address` can be an SSM2 address such as `0x000108` or a full command such as `A800000108`. `bytes` is `1` or `2`; two-byte channels read the selected address and the next address as a big-endian value. `mode` is `Off`, `Fast`, or `Slow`.

The same row can also be pasted as key/value text:

```text
slot=Analog 15,label=Oil temp,unit=C,address=0x000108,bytes=1,scale=1,offset=-40,mode=Slow,signed=false
```

Tap `Load standard tuning preset` to replace `Analog 10` through `Analog 15` with these SSM2 channels:

| RC3 field | Label | SSM2 address | Formula |
|---|---|---:|---|
| `a10` | A/F Correction #1 % | `0x000009` | `raw * 0.78125 - 100` |
| `a11` | A/F Learning #1 % | `0x00000A` | `raw * 0.78125 - 100` |
| `a12` | IAM multiplier | `0x0000F9` | `raw * 0.0625` |
| `a13` | Fine knock learn deg | `0x000199` | `raw * 0.25 - 32` |
| `a14` | A/F Sensor #1 AFR | `0x000046` | `raw * 0.11484375` |
| `a15` | Engine Load % | `0x000007` | `raw * 0.3921568627` |

These standard preset formulas are based on public RomRaider Subaru logger definitions: https://github.com/RomRaider/SubaruDefs/blob/Stable/RomRaider/logger/standard/logger.xml

RaceChrono DIY RC3 format and checksum are based on the official RaceChrono DIY device documentation: https://racechrono.com/article/2572
RaceChrono's forum notes that direct analog-channel renaming was not available for the simple RC3-style data logger channels: https://racechrono.com/forum/d/1689-1689

## Build

Requirements:

- JDK 17
- Android SDK platform 35
- Android SDK build tools 35.0.0 or a compatible installed build-tools package

Local build:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release signing:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
scripts/create-release-keystore.sh
./gradlew assembleRelease
```

Signed release APK:

```text
app/build/outputs/apk/release/app-release.apk
```

`scripts/create-release-keystore.sh` creates a private keystore under `~/.android/` and a local `release-keystore.properties` file. Both files are intentionally ignored by git. Back them up; Android app updates must be signed by the same key as the installed app.

Important signing limit: a release-signed APK is still considered an app from an unknown source when it is installed outside Google Play or another trusted distribution channel. To avoid that user-facing unknown-app flow, distribute through Google Play internal testing, closed testing, or another managed/trusted store. Android's app-signing and unknown-app behavior are documented by Android Developers:

- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/distribute/marketing-tools/alternative-distribution

## Troubleshooting App Exits

When the app opens, check the debug log for `Previous Android exit` and `Last uncaught crash`.

| Log value | Likely meaning |
|---|---|
| `REASON_CRASH` or `Last uncaught crash` | App bug or uncaught exception. Copy the debug log after reopening the app. |
| `REASON_ANR` | The app stopped responding. Copy the debug log and note what action was happening. |
| `REASON_LOW_MEMORY` or `REASON_EXCESSIVE_RESOURCE_USAGE` | Android killed the process under resource pressure. |
| `REASON_USER_REQUESTED` or `REASON_USER_STOPPED` | The app was stopped from recents/settings or by a device power-management action. |
| `Previous lifecycle before process exit: onStop` | The app had moved to the background before the process exited. |

The app starts a `connectedDevice` foreground service before Bluetooth connection work so Android keeps the process active while RaceChrono is foregrounded. The service owns Bluetooth, SSM2 polling, and the local TCP server; the activity only renders UI and sends user commands. Android 13 and newer may hide the notification if notification permission is denied, but the foreground-service task remains visible in the system task manager.

Android documents foreground service requirements, background execution limits, and BLE background communication here:

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/about/versions/oreo/background
- https://developer.android.com/develop/connectivity/bluetooth/ble/background
- https://developer.android.com/topic/performance/vitals/anr

## Known Limits

- Real iCar Pro 2S UUIDs have not been captured yet, so BLE uses discovery fallback by characteristic properties.
- RC3 does not carry custom channel metadata. A future BLE CAN-Bus style bridge could make RaceChrono-side custom channel naming possible, but this TCP RC3 bridge intentionally keeps the simple local stream.
- RC3 has a fixed number of output fields. Custom channels replace an existing `Digital` or `Analog` field; they do not add unlimited new RaceChrono channels.
- Real car verification is still required for `E8 xx` response timing and polling rate.
- RPM high/low are read as separate SSM2 requests, so fast RPM changes can produce a small mismatch.
- Some Android vendors apply aggressive battery restrictions even to foreground services. If the bridge is still killed, check `Previous Android exit` in the debug log and disable battery optimization for RaceChrono Bridge on that device.
