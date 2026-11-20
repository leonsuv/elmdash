# AGENTS.md — Corsa-e Android Auto OBD2 Dashboard

## Projektziel
Native Android-App (Kotlin, Gradle, Coroutines/Flow), die Live-Telemetrie eines **Opel Corsa Electric (Stellantis e-CMP)** über einen **Vgate iCar Pro 2S** ausliest und als frei gestaltbare Dashboards, Graphen und modulare Widgets direkt in **Android Auto** rendert — ohne Google-Play-Developer-Lizenz (Sideloading) und mit voller lokaler Testbarkeit auf macOS (AVD + DHU + Python ELM327 TCP-Simulator).

---

## Hardware- & Protokoll-Spezifikation (Fixe Constraints)

### OBD2-Adapter: Vgate iCar Pro 2S
- **Chipsatz/Protokoll:** ELM327 v2.3 kompatibel, Bluetooth 5.3 Dual-Mode (**BLE + Classic RFCOMM/SPP**).
- **BLE GATT UUIDs:**
  - Service: `000018f0-0000-1000-8000-00805f9b34fb`
  - Read / Notify: `00002af0-0000-1000-8000-00805f9b34fb`
  - Write: `00002af1-0000-1000-8000-00805f9b34fb`
- **Fahrzeug-Bus (Corsa-e / e-CMP):** ISO 15765-4 CAN (11-bit ID, 500 kbaud) → **`ATSP6`**.
- **Diagnose-Protokoll:** UDS (ISO 14229) **Service `0x22`** (ReadDataByIdentifier) über ISO-TP (Single-Frame & Multi-Frame). Standard-OBD2 Mode `01` ist für EV-Daten unbrauchbar.

### STRIKTE REGEL: Keine geratenen PIDs
- **NIEMALS** CAN-Header (`ATSH`), UDS-DIDs oder Byte-Skalierungsformeln im Kotlin-Code hardcoden oder halluzinieren.
- Einzige Single Source of Truth ist **`docs/corsa_e_pids.json`** (wird sowohl von der Android-App als auch vom Python-Simulator geladen).
- Platzhalter/Beispiel-PIDs in der initialen JSON müssen klar als `"verified": false` markiert sein, damit der Nutzer sie später gegen echte Fahrzeug-Logs/Listen abgleichen kann.

### Maximaler Durchsatz (ELM327-Tuning)
1. **Fast-Wake statt `ATZ`:** Beim Connect zuerst `\r` oder `ATI` senden. Nur wenn kein Prompt `>` kommt, `ATZ` ausführen (spart ~1 s).
2. **Init-Sequenz:** `ATE0`, `ATL0`, `ATS0` (keine Leerzeichen), `ATSP6`, `ATCAF1`, `ATAT2`, `ATST0A`.
3. **Expected-Frame-Count:** Bei Abfragen erwartete Frame-Anzahl anhängen (z. B. `22D8101`), damit der Adapter sofort nach Empfang antwortet statt auf `ATST` zu warten.
4. **ECU-Grouping:** Alle PIDs desselben Steuergeräts (`ATSH`) gebündelt abfragen, bevor der Header gewechselt wird.
5. **Tiered Polling:**
   - `FAST` (200–500 ms): Leistung (kW), Strom (A), Spannung (V), Geschwindigkeit
   - `MEDIUM` (2–5 s): SoC (%), Akkutemperatur, Ladeleistung
   - `SLOW` (30–60 s): SoH (%), Min/Max-Zellspannung, Odometer

---

## Android Auto & UI-Architektur

### Sideloading & Custom Graphics
- **Kategorie im Manifest:** `androidx.car.app.category.NAVIGATION` (zwingend für freien Canvas-Zugriff).
- **Template:** `NavigationTemplate` mit `AppManager.setSurfaceCallback()` für ein vollbildfähiges, hardwarebeschleunigtes Android `Canvas` (30–60 FPS).
- **Sideload-Installation:** Immer mit Play-Store-Installer-Spoofing testen/dokumentieren:
  `adb install -r -i "com.android.vending" app/build/outputs/apk/debug/app-debug.apk`

### Instant-Connect & Hintergrundbetrieb
- **Kein Bluetooth-Scan beim Start:** Gespeicherte MAC-Adresse aus `DataStore` direkt per `getRemoteDevice(mac)` verbinden.
- **Auto-Start:** `CarAppService` + `BroadcastReceiver` auf `BluetoothDevice.ACTION_ACL_CONNECTED` (konfigurierbares Auto-Infotainment-BT-Gerät).
- **Foreground Service:** `foregroundServiceType="connectedDevice"` hält die Verbindung stabil, auch wenn in Android Auto zu Maps/Spotify gewechselt wird.
- **Zero-Blank-UI:** Letzte gecachte Werte sofort beim Start anzeigen (leicht gedimmt + Verbindungsindikator), bis Live-Frames eintreffen.

### Modulares Widget-System
- **Datenfluss:** `ObdPollingEngine` → `TelemetryRepository` (`StateFlow<TelemetrySnapshot>`) → `SurfaceRenderer`.
- **Virtuelles Grid:** 12×6 Zellen, automatisch skaliert auf Display-Auflösung & DPI (Corsa-Referenz: `1280x720`, `160 dpi`).
- **Widget-Vertrag:**
  ```kotlin
  interface DashboardWidget {
      val id: String
      val requiredPids: Set<String>
      fun draw(canvas: Canvas, bounds: RectF, state: TelemetrySnapshot, theme: DashboardTheme)
  }
  ```
- **Layout-Config:** JSON-basiert (`dashboard_layout.json`) mit mehreren Seiten (z. B. „Fahren“, „Laden“, „Akku-Details“), umschaltbar über die `ActionStrip`-Buttons des `NavigationTemplate`.
- **Basis-Widgets:** `BigNumberWidget`, `ArcGaugeWidget`, `SparklineWidget`, `BatteryBarWidget`.

---

## Lokale Entwicklung & Testing (macOS)

- **Transport-Abstraktion:** `ObdTransport` Interface mit 3 Implementierungen:
  1. `BleObdTransport` (Vgate BLE GATT + MTU Negotiation 185+)
  2. `SppObdTransport` (Vgate Classic RFCOMM)
  3. `TcpObdTransport` (Default im Debug/Emulator: `10.0.2.2:35000` für AVD → Mac `localhost:35000`, oder `127.0.0.1:35000` via `adb reverse`)
- **Kein Mock im App-Protokoll-Stack:** Tests laufen über `TcpObdTransport` gegen den lokalen Python-Simulator `tools/corsa_elm_sim.py` (echter Byte-Stream, echtes ELM327/UDS/ISO-TP).
- **Python-Simulator (`tools/corsa_elm_sim.py`):** Reines Python 3 (`asyncio`, keine externen Pip-Dependencies), lädt `docs/corsa_e_pids.json`, unterstützt Modi: `drive`, `dc-charge`, `isotp-multiframe`, `chaos` (Reconnect/Timeout-Test).

---

## Coding- & Arbeitsregeln für den Agenten
- **Sprache:** Code, Kommentare und technische Logs auf Englisch; Kommunikation mit dem Nutzer auf Deutsch.
- **Clean & Lightweight:** Keine unnötigen Third-Party-Libraries. Standard AndroidX, Car App Library, Kotlinx Serialization, Coroutines, DataStore.
- **Verifikation:** Nach jedem Meilenstein `./gradlew assembleDebug` und Unit-Tests (`./gradlew testDebugUnitTest`) ausführen. Fehler selbstständig fixen, bevor der Meilenstein als erledigt markiert wird.
- **Schrittweise vorgehen:** Strikt nach `PLAN.md` arbeiten. Keine Features aus späteren Meilensteinen vorziehen.
- **Keine Sub-Agents:** Alle Aufgaben direkt und sequenziell selbst ausführen. **Niemals** Sub-Agents, Background-Agents oder parallele Agent-Tasks spawnen/starten.

## Kritische Android-Auto- & OBD2-Constraints
- **Manifest-Pflichten:** `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`, `android.permission.INTERNET` (für TCP-Sim), Android 12+ BT-Permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` mit `neverForLocation`), `FOREGROUND_SERVICE_CONNECTED_DEVICE` sowie `automotive_app_desc.xml` (`<uses name="template" />`).
- **HostValidator:** `CorsaCarAppService.createHostValidator()` gibt `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` zurück (zwingend für DHU & Sideloading).
- **Safe Area:** `GridLayoutEngine` rendert ausschließlich innerhalb des `stableArea`-Rects aus `SurfaceCallback.onStableAreaChanged`, damit nichts vom Android-Auto-UI verdeckt wird.
- **ISO-TP Multi-Frame:** In der ELM327-Init `ATCFC1` setzen und in `corsa_e_pids.json` optionales `rxHeader` (`ATCRA`) pro ECU unterstützen.
- **12V-Batterie-Schutz:** Bei Trennung von Android Auto (`Session.onDestroy`) oder Auto-BT (`ACTION_ACL_DISCONNECTED`) sofort Polling stoppen, Verbindung schließen und Foreground-Service beenden (kein Hintergrund-Reconnect bei ausgeschaltetem Fahrzeug).
- **Keine Sub-Agents:** Alle Aufgaben direkt und sequenziell selbst ausführen. **Niemals** Sub-Agents, Background-Agents oder parallele Agent-Tasks spawnen/starten.
