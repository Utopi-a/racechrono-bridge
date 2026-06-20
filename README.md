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
