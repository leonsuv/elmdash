# Corsa-e Dash — Android Auto OBD2 Telemetry Dashboard

<p align="center">
  <strong>Native Android &amp; Android Auto OBD2 Dashboard for the Opel Corsa Electric (Stellantis e-CMP, 50 kWh 108s2p)</strong><br/>
  Built with Kotlin, Jetpack Compose, Android Auto Car App Library (<code>NavigationTemplate</code> + hardware-accelerated <code>Canvas</code>), and a custom ELM327 v2.3 / UDS (ISO 14229) / ISO-TP engine — designed for <strong>sideloading without a Google Play Developer account</strong>.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20%2B%20Android%20Auto-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android + Android Auto"/>
  <img src="https://img.shields.io/badge/Language-Kotlin%202.4-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Protocol-ELM327%20v2.3%20%7C%20UDS%200x22%20%7C%20ISO--TP-00B4D8?style=flat-square" alt="OBD2 UDS"/>
  <img src="https://img.shields.io/badge/Adapter-Vgate%20iCar%20Pro%202S%20(BLE%20%2B%20SPP)-10B981?style=flat-square" alt="Vgate iCar Pro 2S"/>
</p>

---

## Screenshots (AVD + Live ELM327 Simulator)

| Driving Dashboard (`Fahren`) | 108s2p Battery Cell Spectrum (`Akku`) | Unified Widget Configurator (52 Sensors) |
| :---: | :---: | :---: |
| <img src="docs/screenshots/01_dash.png" width="270" alt="Driving Dashboard"/> | <img src="docs/screenshots/05_battery_cells.png" width="270" alt="108s2p Cell Spectrum"/> | <img src="docs/screenshots/06_widget_editor.png" width="270" alt="Widget Editor"/> |

| Concurrent Trip Tracker & Gap Detection | Multi-ECU UDS `0x19` Diagnostics | BLE / SPP / TCP Adapter Connection |
| :---: | :---: | :---: |
| <img src="docs/screenshots/02_trips.png" width="270" alt="Trips"/> | <img src="docs/screenshots/03_diagnose.png" width="270" alt="Diagnostics"/> | <img src="docs/screenshots/04_adapter.png" width="270" alt="Adapter"/> |

---

## Highlights & Features

- **Full-Screen Custom Android Auto Canvas:** Uses `androidx.car.app.category.NAVIGATION` + `NavigationTemplate` with `SurfaceCallback` to render a custom 12×6 virtual grid inside `stableArea` (tested at Corsa-e reference `1280×720 @ 160 dpi`).
- **Modular Widget Engine:**
  - `ArcGaugeWidget` — 250° EV powermeter with regen/drive zones, ticks, glow arc, needle, and auto-scaling.
  - `BatteryBarWidget` — 10-segment SoC bar with live battery temperature badge.
  - `SparklineWidget` — smooth cubic time-series graph + dedicated **108s2p cell voltage bar spectrum** with min/max highlights.
  - `BigNumberWidget` — crisp high-contrast numeric telemetry tiles.
- **Interactive Dashboard Builder (Phone Companion App):**
  - Tap to configure, drag-and-drop to reposition on the 12×6 grid, and horizontal fullscreen preview (`16:9` fit-height).
  - Multiple customizable tabs (`Fahren`, `Laden`, `Akku`, plus custom tabs), optional **Endless Scroll** ribbon in Android Auto, and **My Dashboards / My Cards** template library.
  - Instant live hot-reload from Phone Companion App to Android Auto.
- **63 Telemetry Channels (47 Verified UDS PIDs + 16 Computed EV Metrics):**
  - Covers 4 Stellantis e-CMP ECUs (`BMS 0x6B4→0x694`, `VCU 0x6A2→0x682`, `MCU/Inverter 0x6A6→0x686`, `OBC/DC-DC 0x590→0x58F`): HV voltage/current/power (kW BMS = V×A), E-Motor kW/torque/RPM, SoC (calibrated & raw), SoH, available energy (kWh), **108s2p cell voltages** (`22 D440`), **108-cell per-cell SoH %** (`22 D4B5`), **54 battery temperature probes** (`22 D442`), battery/inverter/motor/DC-DC/OBC/coolant temperatures, 12V aux voltage & DC-DC current/power, AC grid voltage, charge/discharge 10s power limits, HV isolation resistance (+/-), **Total kWh IN & OUT** (`0xD42E`), **HV contactor closes / starts** (`0xD416`), **time above 55 °C** (`0xE082`), drive mode (`0xD819`), gear selector (`0xD4E7`), BMS manufacture date/HW/SW (`0xF18B`/`F193`/`F195`), drivetrain efficiency, and power losses.
  - Single Source of Truth catalog in [`docs/corsa_e_pids.json`](docs/corsa_e_pids.json) (shared by the Android app, the built-in Demo Car, and the Python ELM327 simulator).
- **Full Battery Health Suite & 2-Page PDF Battery Certificate (`Diagnose` Tab):**
  - **6-Point Battery Health Rating (`GOOD` / `ATTENTION` / `CHECK`)** following *EscoEvObdScanner*, *Midtronics 2025*, *UNECE R100 (500 Ω/V)*, and *Stellantis Warranty (70% / 8 yr / 160,000 km)*: Pack SoH, Cell Spread at Rest (`≤ 5 A`), **Cells Under Load (`mV / 100 A` internal resistance sag)**, Temperature Spread, HV Insulation, and BMS Faults.
  - Interactive **108-Cell Voltage Heatmap (12×9)**, **108-Cell SoH % Heatmap (12×9)**, **54-Probe Temperature Heatmap (9×6)**, **Live DC/AC Charging Curve**, wear rate (`%/10,000 km` & `%/year`), and **2-Page A4 PDF Battery Report** export & share.
  - **Built-in Demo Car (`Demo-Auto`)** — test the entire app, heatmaps, under-load sag, DTCs, and PDF report with 1 tap without any OBD2 adapter.
  - **Safe Read-Only 8-ECU Link Test** (`0x3E` / `0x22 F186`), **Live CSV Telemetry Recording** (2 Hz export), **Electricity Cost Estimator (`€/kWh` & `€/100 km`)**, and **Stellantis e-CMP DTC Dictionary** (`stellantis_ecmp_dtc.json`).
- **High-Throughput ELM327 v2.3 Engine:**
  - Fast-Wake (`\r` / `ATI` before `ATZ`), Corsa-e CAN init (`ATE0`, `ATL0`, `ATS0`, `ATSP6`, `ATCAF1`, `ATCFC1`, `ATFCSD300000`, `ATFCSM1`, `ATAT2`, `ATST0A`), per-ECU `ATSH`/`ATFCSH`/`ATCRA` switching (required for Stellantis e-CMP's non-standard RX IDs like `6B4→694`), expected-frame-count hints, and tiered polling (`FAST` / `MEDIUM` / `SLOW`).
  - Dual-mode Bluetooth support for **Vgate iCar Pro 2S** (**BLE GATT** `18F0 / 2AF0 / 2AF1` + MTU negotiation & **Classic SPP/RFCOMM**), plus **TCP** transport for local simulator testing.
- **Always-Running Concurrent Trips:** Unlimited parallel trips with automatic persistence, real consumption (`kWh/100 km`), recuperation %, estimated real-world range, and automatic **odometer data-gap detection**.
- **Multi-ECU UDS Diagnostics:** Vehicle Health Score (0–100%), UDS `0x19 02 FF` DTC scanning & `0x14 FF FF FF` clearing across ECUs, plus hidden Developer Tools (Raw-OBD-Log & interactive AT/UDS console).
- **12V Battery Protection & Zero-Blank UI:** Immediately displays cached values on startup until live frames arrive; stops polling and closes the `connectedDevice` foreground service on Android Auto disconnect (`Session.onDestroy`) or car Bluetooth disconnect (`ACTION_ACL_DISCONNECTED`).

---

## Verified Stellantis e-CMP PIDs (`docs/corsa_e_pids.json`)

[`docs/corsa_e_pids.json`](docs/corsa_e_pids.json) contains **47 community-verified Stellantis e-CMP UDS PIDs** (Opel Corsa-e / Mokka-e, Peugeot e-208 / e-2008, Citroën ë-C4, DS 3 E-Tense) cross-checked against:
- **[EsaHub / EscoEvObdScanner](https://github.com/EsaHub/EscoEvObdScanner)** (`stellantis-ecmp.json`, CC BY 4.0 by Esa Pelttari — PIDs, 6-point battery ratings & PDF report methodology)
- **[OBDb / Peugeot-e-208](https://github.com/OBDb/Peugeot-e-208)** (signal definitions & scaling)
- **[dalathegreat / Battery-Emulator](https://github.com/dalathegreat/Battery-Emulator)** (`stellantis_ecmp_dtc.json` DTC dictionary & BMS bench tests)
- **[nickn17 / evDash](https://github.com/nickn17/evDash)** (e-208 decoder, charging curve & 12V SoC logic)
- **[meatpiHQ / wican-fw](https://github.com/meatpiHQ/wican-fw)** (e-208 / Mokka-e profiles)

| ECU | TX Header (`ATSH` / `ATFCSH`) | RX Filter (`ATCRA`) | Key Verified UDS DIDs |
| :--- | :---: | :---: | :--- |
| **BMS** (Battery Management) | `6B4` | `694` | `D815` (V), `D816` (A), `D410`/`D810` (SoC), `D860` (SoH), `D865` (kWh), `D817`/`D877`/`D87D` (Temp), `D86F`/`D870`/`D440` (108 Cells), `D871`/`D873` (Limits), `D42E` (Lifetime kWh), `D822` (12V) |
| **VCU** (Vehicle Control) | `6A2` | `682` | `D402` (Speed), `D8D9` (Motor kW), `D8D0` (Torque), `D8CF` (RPM), `D8CD`/`D8CE`/`D8E1`/`D8F9`/`D8FB` (Temps), `D434` (Ambient), `D49C` (Odometer) |
| **MCU** (Inverter EMR3) | `6A6` | `686` | `D407` (Accel Pedal), `DC6A` (Inverter Temp), `DC06` (DC Link V) |
| **OBC** (Charger & DC-DC) | `590` | `58F` | `D82B` (AC Grid V), `D829` (12V DC-DC A), `D854` (Charge Cable) |

> **Note:** Because Stellantis e-CMP uses non-standard CAN response IDs (`rx ≠ tx + 8`), `Elm327Session` automatically configures `ATFCSD300000`, `ATFCSM1`, and per-ECU `ATFCSH<tx>` so ISO-TP multi-frame queries (like `22 D440` for all 108 cells) work out of the box. You can also inspect live traffic anytime via **Diagnose → Entwickler-Tools (Raw-OBD-Log & AT/UDS-Konsole)**.

---

## Installation (Sideloading for Android Auto)

Because Android Auto hides apps that were not installed via Google Play unless **Unknown Sources** is enabled and the installer package is set to `com.android.vending`, install the release APK from the [Releases](../../releases) page via ADB:

```bash
adb install -r -i "com.android.vending" app-release.apk
```

Then on your phone:
1. Open **Android Auto Settings** → tap **Version** 10 times to enable **Developer Mode**.
2. Open the top-right `⋮` menu → **Developer Settings** → enable **Unknown Sources** (*Unbekannte Quellen*).
3. Open **Corsa-e Dash**, connect your **Vgate iCar Pro 2S** (BLE or SPP) in the **Adapter** tab, and launch **Corsa-e Dash** in Android Auto.

---

## Local Development & Testing (macOS / Linux)

No real car is needed for development or UI testing. The repository includes a pure Python 3 (`asyncio`, zero external dependencies) ELM327 v2.3 + UDS + ISO-TP multi-frame simulator driven directly by `docs/corsa_e_pids.json`.

### 1. Start the Python ELM327 Simulator

```bash
python3 tools/corsa_elm_sim.py --mode drive --port 35000
```

Available modes: `drive`, `dc-charge`, `isotp-multiframe`, `chaos` (simulates timeouts & reconnects).

### 2. Build & Run Unit Tests

```bash
./gradlew assembleDebug testDebugUnitTest
```

### 3. Test on AVD + Android Auto Desktop Head Unit (DHU)

- In the app's **Adapter** tab, select **TCP Sim** → `AVD (10.0.2.2):35000` (or `USB (127.0.0.1):35000` with `adb reverse tcp:35000 tcp:35000`).
- Launch DHU with the Corsa-e display profile (`1280×720`, `160 dpi`, `60 fps`):

```bash
adb forward tcp:5277 tcp:5277
"$ANDROID_HOME/extras/google/auto/desktop-head-unit" -c tools/headunit_corsa.ini
```

---

## Project Architecture

```text
elmdash/
├── app/                     # Android application, CarAppService, ForegroundService & Jetpack Compose Phone UI
├── core/                    # ELM327 session, BLE/SPP/TCP transports, ISO-TP assembler, UDS & DTC parser, TripTracker
├── ui/                      # Canvas SurfaceRenderer, 12x6 GridLayoutEngine, DashboardTheme & modular widgets
├── docs/
│   ├── corsa_e_pids.json    # Single Source of Truth PID & ECU catalog (39 UDS PIDs)
│   └── screenshots/         # AVD showcase screenshots
└── tools/
    ├── corsa_elm_sim.py     # Pure Python 3 ELM327 + UDS + ISO-TP multi-frame simulator
    ├── headunit_corsa.ini   # Android Auto DHU config (1280x720 @ 160 dpi)
    └── dev_up.sh            # Local macOS dev launcher helper
```

---

## License

MIT License — feel free to fork, adapt for other Stellantis e-CMP vehicles (e-208, e-2008, Mokka-e, DS 3 E-Tense), and contribute verified PIDs!
