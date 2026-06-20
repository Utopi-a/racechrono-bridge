# RaceChrono Bridge

Android bridge app for reading Subaru SSM2 over CAN through an iCar Pro 2S BLE ELM327 adapter and streaming calculated telemetry to RaceChrono DIY TCP/IP.

The app does not modify RaceChrono. It exposes a local RaceChrono DIY RC3 TCP stream on `127.0.0.1:9876`.

Important: enable the RaceChrono DIY `RC2/RC3` API only. Do not enable `NMEA 0183`, because that makes the DIY device behave like a GPS receiver and can conflict with RaceChrono's internal GPS receiver.

## Current MVP

- Offline fake telemetry mode for RaceChrono connection testing without the car.
- BLE scan/connect for likely ELM327 adapters such as `Android-Vlink`, `V-LINK`, `vLink`, `Vgate`, and `iCar`.
- Paired Bluetooth adapters are listed before scan results and are connected through Classic Bluetooth SPP first.
- BLE serial characteristic discovery by write/notify capability instead of hard-coded UUIDs.
- ELM327 initialization for SSM2 over CAN:
  - `ATZ`
  - `ATE0`
  - `ATL0`
  - `ATS0`
  - `ATH0`
  - `ATSP6`
  - `ATCAF1`
  - `ATSH7E0`
- SSM2 polling for coolant, boost, RPM high, and RPM low.
- Calculated telemetry:
  - `rpm = ((high << 8) + low) / 4.0`
  - `boostKpa = (boostRaw - 128.0) * 37.0 / 255.0 * 6.89476`
  - `coolantC = coolantRaw - 40.0`
- Debug log display and clipboard copy.

## App Flow

### Offline RaceChrono test

1. Open RaceChrono Bridge.
2. Tap `Start TCP server`.
3. Tap `Start fake telemetry`.
4. In RaceChrono, connect a RaceChrono DIY TCP/IP device to `127.0.0.1:9876`.
5. Enable `RC2/RC3`; keep `NMEA 0183` off.

### iCar Pro 2S / SSM2 test

1. Turn RaceChrono OBD-II reader off. The iCar Pro 2S should be connected only by this bridge app.
2. Tap `Start TCP server`.
3. Tap `Scan BLE devices`.
4. Select the iCar Pro 2S device from the `BLE devices` list. A `paired` device uses Classic Bluetooth SPP first, then falls back to BLE GATT if SPP fails.
5. Tap `Initialize ELM327`.
6. Tap `Start SSM2 polling`.
7. Connect RaceChrono DIY TCP/IP to `127.0.0.1:9876`.
8. Enable `RC2/RC3`; keep `NMEA 0183` off.

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

| RC3 field | Value |
|---|---|
| `rpm/d1` | RPM |
| `a1` | Boost kPa |
| `a2` | Coolant deg C |

RaceChrono DIY RC3 format and checksum are based on the official RaceChrono DIY device documentation: https://racechrono.com/article/2572

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

## Known Limits

- Real iCar Pro 2S UUIDs have not been captured yet, so BLE uses discovery fallback by characteristic properties.
- Real car verification is still required for `E8 xx` response timing and polling rate.
- RPM high/low are read as separate SSM2 requests, so fast RPM changes can produce a small mismatch.
- Keep the app foreground during recording; Android background BLE/network behavior is not handled yet.
