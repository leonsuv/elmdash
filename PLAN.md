# PLAN.md — Umsetzungsplan

## Ziel-Projektstruktur
```text
corsa-aa-dash/
├── AGENTS.md
├── PLAN.md
├── docs/
│   └── corsa_e_pids.json          # Single Source of Truth (UDS PIDs, Header, Formeln)
├── tools/
│   ├── corsa_elm_sim.py           # Python asyncio ELM327 v2.3 + UDS + ISO-TP TCP-Simulator
│   └── headunit_corsa.ini         # Android Auto DHU Config (1280x720, 160 dpi, 60 fps)
├── core/
│   ├── obd/                       # ObdTransport (BLE, SPP, TCP), Elm327Session, FastInit
│   ├── protocol/                  # PidRegistry (JSON), UdsParser, IsoTpAssembler
│   └── telemetry/                 # TieredScheduler, TelemetryRepository, TelemetryCache
├── ui/
│   ├── engine/                    # GridLayoutEngine, SurfaceRenderer, DashboardTheme, LayoutLoader
│   └── widgets/                   # BigNumberWidget, ArcGaugeWidget, SparklineWidget, BatteryBarWidget
└── app/
    └── src/main/
        ├── assets/
        │   ├── corsa_e_pids.json
        │   └── dashboard_layout.json
        └── java/.../
            ├── car/               # CorsaCarAppService, CorsaCarSession, DashboardScreen
            ├── service/           # ObdForegroundService, AutoConnectReceiver
            └── phone/             # Minimal Companion UI (Transport-Wahl, MAC, TCP-Host, Raw-Log)
```

---

## Milestone 1: Projekt-Skeleton, Simulator & DHU-Verifikation
- [x] Gradle-Projekt (Kotlin DSL, AndroidX, Car App Library, Kotlinx Serialization, Coroutines, DataStore) sauber aufsetzen.
- [x] `docs/corsa_e_pids.json` mit realistischer Starter-Struktur (SoC, Power, Voltage, Current, Speed, Battery Temp, SoH, Cell Min/Max, inkl. Multi-Frame-Beispiel) anlegen (`"verified": false` markiert).
- [x] `tools/corsa_elm_sim.py` implementieren:
  - TCP-Server auf Port `35000` (`--port`, `--mode drive|dc-charge|isotp-multiframe|chaos`, `--latency-ms`).
  - Volles ELM327 v2.3 AT-Command-Handling (`ATZ`, `ATI`, `ATE0`, `ATL0`, `ATS0`, `ATSP6`, `ATSH`, `ATCAF1`, `ATAT2`, `ATST`, Expected-Frame-Count).
  - Dynamische EV-Telemetrie-Simulation + echtes ISO-TP Multi-Frame-Splitting (`10..`, `21..`, `22..`).
- [x] `tools/headunit_corsa.ini` erstellen.
- [x] `CorsaCarAppService` + `NavigationTemplate` + `SurfaceCallback` mit Test-Canvas („Corsa-e Dash Ready“ + FPS-Counter).
- [x] **Verifikation:** `./gradlew assembleDebug` grün, Simulator startet fehlerfrei, App rendert im AVD + DHU nach `adb install -r -i "com.android.vending"`.

## Milestone 2: OBD-Core, ISO-TP & TCP-End-to-End
- [x] `ObdTransport` Interface + `TcpObdTransport` (`10.0.2.2:35000` Default für Emulator) implementieren.
- [x] `Elm327Session` mit Fast-Wake (`\r`/`ATI` vor `ATZ`), optimierter Init-Sequenz, Prompt-Parser (`>`) und Reconnect-Logik.
- [x] `IsoTpAssembler` (Single-Frame + Multi-Frame Reassembly) + `UdsParser` + `PidRegistry` (lädt `corsa_e_pids.json`).
- [x] `TieredScheduler` (Fast/Medium/Slow + ECU-Header-Grouping) + `TelemetryRepository` (`StateFlow`).
- [x] Unit-Tests für `IsoTpAssembler`, `UdsParser` und `Elm327Session`.
- [x] **Verifikation:** App im AVD/DHU verbindet sich per TCP mit `corsa_elm_sim.py` und zeigt Live-Rohwerte auf dem Car-Canvas.

## Milestone 3: Modulares Widget- & Grid-UI-System
- [x] `GridLayoutEngine` (12×6 virtuelles Grid, DPI-aware, Padding/Safe-Area-Handling für Android Auto).
- [x] `DashboardTheme` (Auto Day/Night-Mode über `CarContext.isDarkMode`).
- [x] 4 Basis-Widgets:
  - `BigNumberWidget` (Wert, Einheit, Label, dynamische Farbschwellen)
  - `ArcGaugeWidget` (z. B. Leistung -45 kW bis +100 kW mit Reku-Bereich)
  - `SparklineWidget` (Ringpuffer-Verlaufskurve für Leistung/Ladekurve)
  - `BatteryBarWidget` (SoC-Balken + Temperatur-Farbcodierung)
- [x] `dashboard_layout.json` mit 3 Seiten („Fahren“, „Laden“, „Akku-Details“) + Seitenwechsel über `NavigationTemplate` ActionStrip.
- [x] `SurfaceRenderer` mit Dirty-Flag / max. 30–60 FPS Loop.
- [x] **Verifikation:** Alle 3 Seiten im DHU mit `drive` und `dc-charge` Modus des Simulators testen, inkl. `day`/`night` Umschaltung in der DHU-Konsole.

## Milestone 4: Vgate iCar Pro 2S (BLE/SPP), Instant-Connect & Phone Companion
- [x] `BleObdTransport` (GATT UUIDs `18f0` / `2af0` / `2af1`, MTU 185+) und `SppObdTransport` (RFCOMM) implementieren.
- [x] `ObdForegroundService` (`connectedDevice`) + `TelemetryCache` (Zero-Blank-UI beim Start) + `AutoConnectReceiver` (`ACTION_ACL_CONNECTED`).
- [x] Minimale Phone-Companion-UI (Jetpack Compose):
  - Transport-Auswahl (BLE / SPP / TCP-Simulator)
  - Bluetooth-Pairing & MAC-Speicherung in `DataStore`
  - Live-Raw-OBD-Log-Viewer & Export (zum Verifizieren echter Corsa-e PIDs im Auto)
- [x] **Verifikation:** Chaos-Mode-Test im Simulator (Auto-Reconnect + Cache-Anzeige) und finales Release/Debug-Build für Sideloading aufs echte Smartphone.
