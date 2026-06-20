# RaceChrono Bridge

Android bridge app for reading Subaru SSM2 over CAN through an iCar Pro 2S BLE ELM327 adapter and streaming calculated telemetry to RaceChrono DIY TCP/IP.

## MVP scope

- Connect to iCar Pro 2S over BLE.
- Send ELM327 ASCII commands for SSM2 over CAN.
- Calculate RPM, boost kPa, and coolant temperature.
- Expose a RaceChrono DIY RC3 TCP stream on `127.0.0.1:9876`.
- Keep a readable debug log for car-side verification.

## Development phases

1. Offline fake telemetry and RaceChrono TCP stream.
2. BLE scan/connect and ELM327 command roundtrip.
3. Real SSM2 polling for coolant, boost, RPM high, and RPM low.
4. RaceChrono integration with calculated telemetry.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
```
