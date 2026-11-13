# BYD Auto Link v3.6.0 Protocol Audit: `OPENAIR` and Fan Speed Parameters

**Audit Target:** `com.byd.bydautolink_3.6.0-363_2arch_7dpi_23lang_216e1ffece85e57ec5c52080a448c05d_apkmirror.com.apkm` (Version 3.6.0, build 363)  
**Date:** 2026-11-13  
**Status:** Completed

---

## 1. APK Structure & Technology Stack

Inspection of the `.apkm` bundle revealed:
- **Packaging:** Split APK bundle containing `base.apk`, architecture splits (`split_config.arm64_v8a.apk`, `split_config.armeabi_v7a.apk`), and language splits.
- **Protection / Packer:** Bangcle / SecNeo hardening (`libDexHelper.so`), runtime packed container `libdatajar.so` (42.7 MB ELF) housing the Dalvik/JVM classes and data structures.
- **UI & Application Layer:** Hybrid architecture combining Flutter AOT (`libapp.so`, 20.3 MB) and native Android activities (`com.bydautolink.module_car_air.*`).
- **Flutter-Native Bridge:** Pigeon IPC bridge (`dev.flutter.pigeon.overseas_plugin_pigeon.DiAirDataNativeApi`).

---

## 2. Observed Air Conditioning & Remote Control Symbols

### 2.1 Direct `OPENAIR` & AC Command Tokens
- `OPENAIR` appears as a distinct command token in `libapp.so` (offset `0x775192`) in the Dart constant pool.
- JVM / Native symbols in `libdatajar.so`:
  - `com/bydautolink/module_car_air/airandseat/air/layout/AirMainViewModel$openAir$1;`
  - `com/bydautolink/module_car_air/airandseat/air/layout/AirMainViewModel$closeAir$1;`
  - `onOpenAirFinish`, `onCloseAirFinish`
  - `onRapidOpenAirFinish`, `onRapidHottingFinish`
  - `AirMainHelper.kt`, `AirManager.kt`

### 2.2 Pigeon Native API (`DiAirDataNativeApi`) Interface
The cross-platform AC data bridge exposes the following methods:
- `getAirTempCommand` / `saveAirTempCommand` / `saveAirTempCommandWithRapid`
- `getAirTempShowValue` / `getCommandToTempShowValue` / `getCommandToTempGear`
- `saveAirTempNumber` / `saveAirTempNumberWithRapid`
- `getCommandTimeSpanCycleModeAirStep` (querying remote AC runtime duration, internal/external air cycle mode, temperature stepping)
- `getAirConditionTempRange`
- `getTempsHiCommand` / `getTempsLoCommand`
- `getEleAirACSwitch`
- `getEleWindVolumeLevel` / `AirVolumeLevelPickerView`

---

## 3. Findings on Remote Fan Speed Support

### Does BYD Cloud `OPENAIR` support remote fan-speed parameters?
**Answer: No.**

1. **Remote Cloud Payload Scope:**
   - Remote climate initiation (`OPENAIR`) parameters in BYD Cloud accept:
     - `temperature` (or target temp code/gear)
     - `timeSpan` / `duration` (remote run timer before auto-shutdown)
     - Pre-condition modes (`rapidOpenAir` / `rapidHotting` for max cooling/heating)
   - There is **no parameter for fan speed / wind level** in remote cloud dispatch payloads. When remote AC is commanded via `OPENAIR`, the vehicle HVAC climate controller runs in automatic regulation mode based on the target temperature and interior cabin sensor readings.

2. **Where Fan Speed / Wind Level Actually Exists:**
   - `windLevel`, `airVolume`, and `getEleWindVolumeLevel` (`AirVolumeLevelPickerView`) appear exclusively in local in-car display controls and Bluetooth/electronic AC status query representations (`EleAirConditioner`), NOT as parameters passed into remote cloud command dispatch.

---

## 4. Architectural Ruling for Companion & Watch GT 4 Apps

- **Air Conditioning Remote Start:** Send `OPENAIR` with target temperature setting and default runtime duration.
- **Fan Speed Remote Setting:** Omit remote fan speed control slider/stepper from cloud remote AC command flows to conform with vehicle ECU auto-regulation behavior. Display temperature control as the primary climate adjustment.
