# OV Watch Sync Android APP

This is a minimal Android client for the OV Watch firmware Bluetooth serial protocol.

## Protocol

The phone sends:

```text
OV+SEND
```

The watch replies with one line:

```text
OVDATA,DATE=2026-05-06,TIME=14:30:20,HR=75,SPO2=98,TEMP=26,HUMI=55,STEP=1200,BAT=80,FALL=0,ALERT=0
```

The phone can clear a fall event with:

```text
OV+FALLCLR
```

## Build Locally

Use Gradle with Android Gradle Plugin 8.13.2 and JDK 17:

```bash
gradle :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
