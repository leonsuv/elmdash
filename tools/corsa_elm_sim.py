#!/usr/bin/env python3
"""
ELM327 v2.3 + UDS (0x22) + ISO-TP TCP Simulator for Opel Corsa Electric (e-CMP).

Pure Python 3 (asyncio, stdlib only). Reads PID definitions, CAN headers, and
scaling formulas directly from docs/corsa_e_pids.json (Single Source of Truth).

Usage:
    python3 tools/corsa_elm_sim.py --mode drive --port 35000
    python3 tools/corsa_elm_sim.py --mode dc-charge --port 35000 --latency-ms 20
    python3 tools/corsa_elm_sim.py --mode isotp-multiframe --port 35000 --verbose
    python3 tools/corsa_elm_sim.py --mode chaos --port 35000
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import math
import random
import signal
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

LOG = logging.getLogger("corsa_elm_sim")

DEFAULT_PIDS_PATH = Path(__file__).resolve().parent.parent / "docs" / "corsa_e_pids.json"


@dataclass
class EcuConfig:
    key: str
    name: str
    tx_header: str
    rx_header: str


@dataclass
class PidConfig:
    id: str
    name: str
    ecu: str
    service: str
    did: str
    tier: str
    expected_frames: int
    value_type: str
    byte_offset: int
    byte_length: int
    endianness: str
    signed: bool
    scale: float
    offset: float
    unit: str
    min_val: float
    max_val: float
    element_count: int = 1
    verified: bool = False


class PidCatalog:
    """Loads ECU headers and UDS PIDs from corsa_e_pids.json."""

    def __init__(self, path: Path) -> None:
        raw = json.loads(path.read_text(encoding="utf-8"))
        self.vehicle: str = raw.get("vehicle", "Unknown EV")
        self.ecus: dict[str, EcuConfig] = {}
        self.tx_to_ecu: dict[str, EcuConfig] = {}

        for key, ecu_raw in raw.get("ecus", {}).items():
            cfg = EcuConfig(
                key=key,
                name=ecu_raw.get("name", key),
                tx_header=ecu_raw["txHeader"].upper(),
                rx_header=ecu_raw.get("rxHeader", "7E8").upper(),
            )
            self.ecus[key] = cfg
            self.tx_to_ecu[cfg.tx_header] = cfg

        self.pids: dict[str, PidConfig] = {}
        # Lookup by (tx_header, service, did) -> list of PidConfigs sharing the DID (e.g., 0xD42E)
        self.by_request: dict[tuple[str, str, str], list[PidConfig]] = {}

        for p in raw.get("pids", []):
            pid = PidConfig(
                id=p["id"],
                name=p["name"],
                ecu=p["ecu"],
                service=p["service"].upper(),
                did=p["did"].upper(),
                tier=p.get("tier", "MEDIUM").upper(),
                expected_frames=int(p.get("expectedFrames") or 1),
                value_type=p.get("valueType", "scalar"),
                byte_offset=int(p.get("byteOffset", 0)),
                byte_length=int(p.get("byteLength", 2)),
                endianness=p.get("endianness", "BIG").upper(),
                signed=bool(p.get("signed", False)),
                scale=float(p.get("scale", 1.0)),
                offset=float(p.get("offset", 0.0)),
                unit=p.get("unit", ""),
                min_val=float(p.get("min", -1e9)),
                max_val=float(p.get("max", 1e9)),
                element_count=int(p.get("elementCount", 1)),
                verified=bool(p.get("verified", False)),
            )
            self.pids[pid.id] = pid
            ecu_cfg = self.ecus.get(pid.ecu)
            if ecu_cfg is not None:
                self.by_request.setdefault((ecu_cfg.tx_header, pid.service, pid.did), []).append(pid)


class TelemetryModel:
    """Generates dynamic, physically plausible Corsa-e EV telemetry over time."""

    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.start_time = time.monotonic()
        self.base_odometer_km = 24815.0
        self.soh_pct = 95.40
        self.soc_pct = 18.5 if mode == "dc-charge" else 78.2
        self._last_tick = self.start_time

    def snapshot(self) -> dict[str, Any]:
        now = time.monotonic()
        elapsed = now - self.start_time
        dt = max(0.0, now - self._last_tick)
        self._last_tick = now

        if self.mode == "dc-charge":
            return self._dc_charge_snapshot(elapsed, dt)
        if self.mode == "isotp-multiframe":
            return self._multiframe_snapshot(elapsed, dt)
        # drive & chaos share the driving telemetry model
        return self._drive_snapshot(elapsed, dt)

    def _drive_snapshot(self, elapsed: float, dt: float) -> dict[str, Any]:
        # Smooth city + country road cycle (period ~60 s)
        phase = elapsed * (2.0 * math.pi / 60.0)
        speed = max(0.0, 52.0 + 42.0 * math.sin(phase) + 14.0 * math.sin(phase * 2.7))
        accel = math.cos(phase) * 42.0 + math.cos(phase * 2.7) * 37.8

        # Power: cruise drag + acceleration/regeneration (-35 kW to +95 kW)
        cruise_kw = 2.0 + 0.0018 * (speed ** 2)
        power_kw = max(-38.0, min(98.0, cruise_kw + accel * 0.95))

        # Slowly integrate energy (50 kWh gross / ~46 kWh usable)
        self.soc_pct = max(5.0, min(100.0, self.soc_pct - (power_kw * dt / 3600.0) / 46.0 * 100.0))
        self.base_odometer_km += speed * (dt / 3600.0)

        # Open-circuit pack voltage (108s NMC) + internal resistance sag
        ocv = 345.0 + (self.soc_pct / 100.0) * 65.0
        hv_voltage = max(310.0, min(425.0, ocv - power_kw * 0.18))
        hv_current = (power_kw * 1000.0) / hv_voltage
        battery_temp = 24.0 + 5.0 * math.sin(elapsed / 120.0) + min(4.0, abs(power_kw) * 0.03)

        # Physically plausible auxiliaries + inverter + motor split
        hvac_kw = 0.85 + 0.35 * math.sin(elapsed / 45.0)
        ptc_kw = 0.0
        dcdc_w = 320.0 + 90.0 * math.sin(elapsed / 15.0)
        aux_total_kw = hvac_kw + ptc_kw + (dcdc_w / 1000.0)
        inverter_kw = power_kw - aux_total_kw
        # ~92% drive efficiency, ~89% regen efficiency
        motor_kw = inverter_kw * 0.92 if inverter_kw >= 0 else inverter_kw * 1.11
        motor_rpm = max(0.0, min(14000.0, speed * 86.5))
        motor_torque = (motor_kw * 9549.0 / motor_rpm) if motor_rpm > 50.0 else (motor_kw * 18.0)
        motor_torque = max(-150.0, min(260.0, motor_torque))
        accel_pedal = max(0.0, min(100.0, (power_kw / 100.0) * 100.0)) if power_kw > 0 else 0.0
        brake_bar = max(0.0, min(120.0, (-power_kw - 12.0) * 1.4)) if power_kw < -12.0 else 0.0

        cells = self._generate_cells(hv_voltage, elapsed, spread_v=0.018)
        return {
            "hv_power": power_kw,
            "hv_voltage": hv_voltage,
            "hv_current": hv_current,
            "inverter_power": inverter_kw,
            "motor_power": motor_kw,
            "motor_torque": motor_torque,
            "motor_rpm": motor_rpm,
            "speed": speed,
            "accel_pedal": accel_pedal,
            "brake_pressure": brake_bar,
            "soc": self.soc_pct,
            "soc_raw": max(3.0, min(97.0, self.soc_pct * 0.94 + 3.0)),
            "available_energy": max(0.0, 46.3 * (self.soh_pct / 100.0) * (self.soc_pct / 100.0)),
            "battery_temp": battery_temp,
            "battery_temp_min": battery_temp - 1.5,
            "battery_temp_max": battery_temp + 2.0,
            "battery_temp_delta": 3.5,
            "max_charge_power": 100.0 if self.soc_pct < 35.0 else max(18.0, 100.0 - (self.soc_pct - 35.0) * 1.3),
            "max_discharge_power": 158.0 if self.soc_pct > 15.0 else 85.0,
            "inverter_temp": 36.0 + abs(inverter_kw) * 0.35,
            "dc_link_voltage": hv_voltage - 0.8,
            "motor_temp": 44.0 + abs(motor_kw) * 0.42,
            "dcdc_temp": 34.0,
            "aux_12v_voltage": 14.35,
            "aux_12v_current": dcdc_w / 14.35,
            "charge_power": 0.0,
            "charge_cable_state": 0.0,
            "ac_voltage": 0.0,
            "ac_current": 0.0,
            "dcdc_power": dcdc_w,
            "obc_temp": 29.0,
            "hvac_power": hvac_kw,
            "ptc_heater_power": ptc_kw,
            "coolant_temp_hv": battery_temp - 2.0,
            "coolant_temp_heater": 38.0,
            "cabin_temp": 21.5,
            "ambient_temp": 14.5,
            "soh": self.soh_pct,
            "hv_isolation_kohm": 4850.0,
            "total_energy_charged": 4120.5,
            "total_energy_discharged": 3890.2,
            "cell_voltage_min": min(cells),
            "cell_voltage_max": max(cells),
            "cell_voltage_avg": sum(cells) / len(cells),
            "odometer": self.base_odometer_km,
            "cell_voltages": cells,
        }

    def _dc_charge_snapshot(self, elapsed: float, dt: float) -> dict[str, Any]:
        # Tapered DC fast-charging curve (100 kW up to ~35%, tapering toward 24 kW at 85%)
        if self.soc_pct < 35.0:
            charge_kw = 98.0 + 1.5 * math.sin(elapsed * 0.3)
        elif self.soc_pct < 60.0:
            charge_kw = 76.0 - (self.soc_pct - 35.0) * 0.9
        elif self.soc_pct < 80.0:
            charge_kw = 52.0 - (self.soc_pct - 60.0) * 1.1
        else:
            charge_kw = max(12.0, 28.0 - (self.soc_pct - 80.0) * 1.2)

        # Speed up SoC progression slightly (8x) so charging curve is fun to watch in DHU
        self.soc_pct = min(98.0, self.soc_pct + (charge_kw * dt * 8.0 / 3600.0) / 46.0 * 100.0)
        if self.soc_pct >= 98.0:
            self.soc_pct = 18.5

        ocv = 345.0 + (self.soc_pct / 100.0) * 68.0
        hv_voltage = min(426.0, ocv + charge_kw * 0.14)
        # Negative HV power/current indicates energy flowing into the battery
        hv_power = -charge_kw
        hv_current = (hv_power * 1000.0) / hv_voltage
        battery_temp = min(41.0, 22.0 + (self.soc_pct - 18.0) * 0.25)

        cells = self._generate_cells(hv_voltage, elapsed, spread_v=0.024)
        return {
            "hv_power": hv_power,
            "hv_voltage": hv_voltage,
            "hv_current": hv_current,
            "inverter_power": 0.0,
            "motor_power": 0.0,
            "motor_torque": 0.0,
            "motor_rpm": 0.0,
            "speed": 0.0,
            "accel_pedal": 0.0,
            "brake_pressure": 0.0,
            "soc": self.soc_pct,
            "soc_raw": max(3.0, min(97.0, self.soc_pct * 0.94 + 3.0)),
            "available_energy": max(0.0, 46.3 * (self.soh_pct / 100.0) * (self.soc_pct / 100.0)),
            "battery_temp": battery_temp,
            "battery_temp_min": battery_temp - 1.5,
            "battery_temp_max": battery_temp + 2.5,
            "battery_temp_delta": 4.0,
            "max_charge_power": charge_kw + 2.0,
            "max_discharge_power": 158.0,
            "inverter_temp": 28.0,
            "dc_link_voltage": hv_voltage,
            "motor_temp": 30.0,
            "dcdc_temp": 36.0,
            "aux_12v_voltage": 14.4,
            "aux_12v_current": 18.0,
            "charge_power": charge_kw,
            "charge_cable_state": 1.0,
            "ac_voltage": 0.0,
            "ac_current": 0.0,
            "dcdc_power": 260.0,
            "obc_temp": 35.0,
            "hvac_power": 1.4,
            "ptc_heater_power": 0.0,
            "coolant_temp_hv": battery_temp - 4.0,
            "coolant_temp_heater": 28.0,
            "cabin_temp": 21.0,
            "ambient_temp": 14.5,
            "soh": self.soh_pct,
            "hv_isolation_kohm": 4820.0,
            "total_energy_charged": 4145.0,
            "total_energy_discharged": 3890.2,
            "cell_voltage_min": min(cells),
            "cell_voltage_max": max(cells),
            "cell_voltage_avg": sum(cells) / len(cells),
            "odometer": self.base_odometer_km,
            "cell_voltages": cells,
        }

    def _multiframe_snapshot(self, elapsed: float, dt: float) -> dict[str, Any]:
        snap = self._drive_snapshot(elapsed, dt)
        # Exaggerate cell spread slightly so multi-frame cell array changes are obvious
        cells = self._generate_cells(snap["hv_voltage"], elapsed, spread_v=0.055)
        snap["cell_voltages"] = cells
        snap["cell_voltage_min"] = min(cells)
        snap["cell_voltage_max"] = max(cells)
        return snap

    @staticmethod
    def _generate_cells(hv_voltage: float, elapsed: float, spread_v: float, count: int = 108) -> list[float]:
        avg_cell = hv_voltage / 108.0
        cells: list[float] = []
        for idx in range(count):
            # Subtle per-module (18 modules x 6s) + per-cell variation
            module_idx = idx // 6
            wave = 0.65 * math.sin(elapsed * 0.4 + module_idx * 0.55) + 0.35 * math.sin(idx * 1.3 + elapsed * 0.2)
            cell_v = avg_cell + (wave * spread_v * 0.5)
            cells.append(max(2.5, min(4.5, cell_v)))
        return cells


def encode_value(pid: PidConfig, value: Any) -> bytes:
    """Encodes a simulated physical scalar or array value into raw UDS data bytes."""
    byteorder = "big" if pid.endianness == "BIG" else "little"

    def _encode_one(v: float) -> bytes:
        clamped = max(pid.min_val, min(pid.max_val, float(v)))
        raw_int = int(round((clamped - pid.offset) / pid.scale))
        if pid.signed:
            min_int = -(1 << (pid.byte_length * 8 - 1))
            max_int = (1 << (pid.byte_length * 8 - 1)) - 1
        else:
            min_int = 0
            max_int = (1 << (pid.byte_length * 8)) - 1
        raw_int = max(min_int, min(max_int, raw_int))
        return raw_int.to_bytes(pid.byte_length, byteorder=byteorder, signed=pid.signed)

    prefix = b"\x00" * pid.byte_offset
    if pid.value_type == "array":
        items = value if isinstance(value, list) else [value] * pid.element_count
        encoded = b"".join(_encode_one(v) for v in items[: pid.element_count])
        return prefix + encoded
    return prefix + _encode_one(float(value))


@dataclass
class ElmSessionState:
    echo: bool = True
    linefeeds: bool = True
    spaces: bool = True
    headers: bool = False
    can_auto_format: bool = True
    can_flow_control: bool = True
    adaptive_timing: int = 1
    timeout_hex: str = "32"
    protocol: str = "6"
    tx_header: str = "7DF"
    rx_filter: str | None = None
    request_count: int = 0
    last_command: str = ""
    chaos_disconnect_at: int = field(default_factory=lambda: random.randint(25, 40))
    # Simulated per-ECU UDS DTC records: (b0, b1, ftb, status)
    dtc_store: dict[str, list[tuple[int, int, int, int]]] = field(
        default_factory=lambda: {
            "6B4": [],  # BMS healthy by default
            "6A2": [(0xD2, 0x08, 0x00, 0x04)],  # VCU: U1208-00 Pending (sporadic CAN message)
            "590": [(0xD1, 0x13, 0x87, 0x09)],  # OBC: U1113-87 Active+Confirmed (wallbox handshake)
        }
    )

    def reset(self) -> None:
        self.echo = True
        self.linefeeds = True
        self.spaces = True
        self.headers = False
        self.can_auto_format = True
        self.can_flow_control = True
        self.adaptive_timing = 1
        self.timeout_hex = "32"
        self.protocol = "6"
        self.tx_header = "7DF"
        self.rx_filter = None


class Elm327ClientHandler:
    """Handles one connected TCP client speaking the ELM327 v2.3 protocol."""

    def __init__(
        self,
        catalog: PidCatalog,
        model: TelemetryModel,
        mode: str,
        latency_ms: int,
        verbose: bool,
    ) -> None:
        self.catalog = catalog
        self.model = model
        self.mode = mode
        self.latency_ms = latency_ms
        self.verbose = verbose

    async def handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        peer = writer.get_extra_info("peername")
        LOG.info("Client connected: %s (mode=%s)", peer, self.mode)
        state = ElmSessionState()

        try:
            buf = bytearray()
            while True:
                chunk = await reader.read(1024)
                if not chunk:
                    break
                buf.extend(chunk)

                while True:
                    idx_r = buf.find(b"\r")
                    idx_n = buf.find(b"\n")
                    if idx_r == -1 and idx_n == -1:
                        break
                    if idx_r != -1 and (idx_n == -1 or idx_r < idx_n):
                        cut = idx_r
                        consume = 2 if (idx_r + 1 < len(buf) and buf[idx_r + 1] == 0x0A) else 1
                    else:
                        cut = idx_n
                        consume = 1

                    raw_line = bytes(buf[:cut]).decode("ascii", errors="ignore")
                    del buf[: cut + consume]

                    cmd = raw_line.strip()
                    if self.verbose:
                        LOG.info("RX [%s]: %r", peer, cmd)

                    should_drop = await self._process_command(cmd, state, writer, peer)
                    if should_drop:
                        LOG.warning("CHAOS: Dropping TCP connection for %s", peer)
                        return
        except (ConnectionResetError, BrokenPipeError, asyncio.CancelledError):
            pass
        finally:
            LOG.info("Client disconnected: %s", peer)
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def _process_command(
        self,
        cmd: str,
        state: ElmSessionState,
        writer: asyncio.StreamWriter,
        peer: Any,
    ) -> bool:
        eol = "\r\n" if state.linefeeds else "\r"

        # Fast-wake: empty CR returns prompt immediately (no reset needed)
        if cmd == "":
            out = f"{eol}>" if state.echo else ">"
            writer.write(out.encode("ascii"))
            await writer.drain()
            return False

        upper = cmd.upper().replace(" ", "")
        echo_prefix = f"{cmd}{eol}" if state.echo else ""

        if upper.startswith("AT"):
            response_lines, delay_s = self._handle_at(upper, state)
            if delay_s > 0:
                await asyncio.sleep(delay_s)
            body = eol.join(response_lines)
            out = f"{echo_prefix}{body}{eol}{eol}>"
            if self.verbose:
                LOG.info("TX [%s]: %r", peer, out)
            writer.write(out.encode("ascii"))
            await writer.drain()
            return False

        # OBD / UDS hex command
        state.request_count += 1
        state.last_command = upper

        # Chaos mode fault injection
        if self.mode == "chaos":
            if state.request_count >= state.chaos_disconnect_at:
                return True
            roll = random.random()
            if roll < 0.08:
                await asyncio.sleep(random.uniform(0.25, 1.1))
                out = f"{echo_prefix}NO DATA{eol}{eol}>"
                writer.write(out.encode("ascii"))
                await writer.drain()
                return False
            if roll < 0.12:
                out = f"{echo_prefix}STOPPED{eol}{eol}>"
                writer.write(out.encode("ascii"))
                await writer.drain()
                return False
            if roll < 0.22:
                await asyncio.sleep(random.uniform(0.25, 0.85))

        if self.latency_ms > 0:
            jitter = random.uniform(0.8, 1.25)
            await asyncio.sleep((self.latency_ms / 1000.0) * jitter)

        response_lines = self._handle_uds(upper, state)
        body = eol.join(response_lines)
        out = f"{echo_prefix}{body}{eol}{eol}>"
        if self.verbose:
            LOG.info("TX [%s]: %r", peer, out)
        writer.write(out.encode("ascii"))
        await writer.drain()
        return False

    def _handle_at(self, cmd: str, state: ElmSessionState) -> tuple[list[str], float]:
        if cmd == "ATZ":
            state.reset()
            return (["", "ELM327 v2.3"], 0.12)
        if cmd == "ATWS":
            state.reset()
            return (["ELM327 v2.3"], 0.03)
        if cmd == "ATI":
            return (["ELM327 v2.3"], 0.0)
        if cmd == "ATD":
            state.reset()
            return (["OK"], 0.0)
        if cmd == "ATE0":
            state.echo = False
            return (["OK"], 0.0)
        if cmd == "ATE1":
            state.echo = True
            return (["OK"], 0.0)
        if cmd == "ATL0":
            state.linefeeds = False
            return (["OK"], 0.0)
        if cmd == "ATL1":
            state.linefeeds = True
            return (["OK"], 0.0)
        if cmd == "ATS0":
            state.spaces = False
            return (["OK"], 0.0)
        if cmd == "ATS1":
            state.spaces = True
            return (["OK"], 0.0)
        if cmd == "ATH0":
            state.headers = False
            return (["OK"], 0.0)
        if cmd == "ATH1":
            state.headers = True
            return (["OK"], 0.0)
        if cmd == "ATCAF0":
            state.can_auto_format = False
            return (["OK"], 0.0)
        if cmd == "ATCAF1":
            state.can_auto_format = True
            return (["OK"], 0.0)
        if cmd == "ATCFC0":
            state.can_flow_control = False
            return (["OK"], 0.0)
        if cmd == "ATCFC1":
            state.can_flow_control = True
            return (["OK"], 0.0)
        if cmd in ("ATAT0", "ATAT1", "ATAT2"):
            state.adaptive_timing = int(cmd[-1])
            return (["OK"], 0.0)
        if cmd.startswith("ATST") and len(cmd) >= 6:
            state.timeout_hex = cmd[4:]
            return (["OK"], 0.0)
        if cmd.startswith("ATSP") and len(cmd) >= 5:
            state.protocol = cmd[4:]
            return (["OK"], 0.0)
        if cmd.startswith("ATSH") and len(cmd) >= 7:
            state.tx_header = cmd[4:].upper()
            return (["OK"], 0.0)
        if cmd.startswith("ATFCSH") or cmd.startswith("ATFCSD") or cmd.startswith("ATFCSM"):
            return (["OK"], 0.0)
        if cmd == "ATCRA":
            state.rx_filter = None
            return (["OK"], 0.0)
        if cmd.startswith("ATCRA") and len(cmd) >= 8:
            state.rx_filter = cmd[5:].upper()
            return (["OK"], 0.0)
        if cmd == "ATRV":
            return (["14.2V"], 0.0)
        if cmd == "ATDP":
            return (["ISO 15765-4 (CAN 11/500)"], 0.0)
        if cmd == "ATDPN":
            return ([f"A{state.protocol}"], 0.0)
        return (["?"], 0.0)

    def _handle_uds(self, cmd: str, state: ElmSessionState) -> list[str]:
        # Validate hex
        if not cmd or any(c not in "0123456789ABCDEF" for c in cmd):
            return ["?"]

        # UDS Service 0x19 ReadDTCInformation (sub-function 0x02 reportDTCByStatusMask)
        if cmd.startswith("1902") and len(cmd) >= 6:
            rx_hdr = self._rx_header(state)
            records = state.dtc_store.get(state.tx_header, [])
            payload = bytearray([0x59, 0x02, 0xFF])
            for b0, b1, ftb, st in records:
                payload.extend([b0, b1, ftb, st])
            if len(payload) <= 7:
                return self._format_single_frame(bytes(payload), state, rx_header=rx_hdr)
            return self._format_multiframe(bytes(payload), state, rx_header=rx_hdr)

        # UDS Service 0x14 ClearDiagnosticInformation (14FFFFFF)
        if cmd.startswith("14") and len(cmd) >= 8:
            rx_hdr = self._rx_header(state)
            state.dtc_store[state.tx_header] = []
            return self._format_single_frame(bytes([0x54]), state, rx_header=rx_hdr)

        # UDS Service 0x22 ReadDataByIdentifier:
        # Format: 22<DID:4hex>[expectedFrames:1hex]
        if cmd.startswith("22") and len(cmd) in (6, 7):
            service = "22"
            did = cmd[2:6]
            pid_list = self.catalog.by_request.get((state.tx_header, service, did))
            if not pid_list:
                # Negative Response: 7F 22 31 (requestOutOfRange)
                return self._format_single_frame(bytes([0x7F, 0x22, 0x31]), state, rx_header=self._rx_header(state))

            ecu = self.catalog.ecus[pid_list[0].ecu]
            if state.rx_filter and state.rx_filter != ecu.rx_header:
                return ["NO DATA"]

            snap = self.model.snapshot()
            if len(pid_list) == 1:
                pid = pid_list[0]
                if pid.id not in snap:
                    return ["NO DATA"]
                data_bytes = encode_value(pid, snap[pid.id])
            else:
                buf = bytearray()
                for pid in pid_list:
                    if pid.id not in snap:
                        continue
                    part = encode_value(pid, snap[pid.id])
                    if len(part) > len(buf):
                        buf.extend(b"\x00" * (len(part) - len(buf)))
                    start = pid.byte_offset
                    buf[start : len(part)] = part[start:]
                if not buf:
                    return ["NO DATA"]
                data_bytes = bytes(buf)

            payload = bytes([0x62]) + bytes.fromhex(did) + data_bytes

            if len(payload) <= 7:
                return self._format_single_frame(payload, state, rx_header=ecu.rx_header)
            return self._format_multiframe(payload, state, rx_header=ecu.rx_header)

        return ["NO DATA"]

    def _rx_header(self, state: ElmSessionState) -> str:
        ecu = self.catalog.tx_to_ecu.get(state.tx_header)
        return ecu.rx_header if ecu else "7E8"

    def _fmt_bytes(self, data: bytes, spaces: bool) -> str:
        sep = " " if spaces else ""
        return sep.join(f"{b:02X}" for b in data)

    def _format_single_frame(
        self,
        payload: bytes,
        state: ElmSessionState,
        rx_header: str,
    ) -> list[str]:
        sep = " " if state.spaces else ""
        if state.can_auto_format:
            body = self._fmt_bytes(payload, state.spaces)
        else:
            pci = bytes([len(payload) & 0x0F])
            frame = (pci + payload).ljust(8, b"\xAA")
            body = self._fmt_bytes(frame, state.spaces)

        if state.headers:
            return [f"{rx_header}{sep}{body}"]
        return [body]

    def _format_multiframe(
        self,
        payload: bytes,
        state: ElmSessionState,
        rx_header: str,
    ) -> list[str]:
        """
        Splits a payload (>7 bytes) into ISO-TP frames.
        - Raw ISO-TP PCI format (10xx..., 21..., 22...) when ATCAF0, ATH1, or mode==isotp-multiframe.
        - ELM327 auto-formatted multi-frame (0xx / 0:... / 1:...) when ATCAF1 + ATH0 in other modes.
        """
        total_len = len(payload)
        sep = " " if state.spaces else ""
        use_raw_pci = (not state.can_auto_format) or state.headers or (self.mode == "isotp-multiframe")

        lines: list[str] = []
        if use_raw_pci:
            # First Frame: 10 LL <6 bytes>
            ff_pci = bytes([0x10 | ((total_len >> 8) & 0x0F), total_len & 0xFF])
            ff_data = payload[:6]
            ff_frame = ff_pci + ff_data
            prefix = f"{rx_header}{sep}" if state.headers else ""
            lines.append(f"{prefix}{self._fmt_bytes(ff_frame, state.spaces)}")

            # Consecutive Frames: 21..2F, 20..2F <up to 7 bytes>
            offset = 6
            seq = 1
            while offset < total_len:
                chunk = payload[offset : offset + 7]
                cf_pci = bytes([0x20 | (seq & 0x0F)])
                cf_frame = (cf_pci + chunk).ljust(8, b"\xAA")
                lines.append(f"{prefix}{self._fmt_bytes(cf_frame, state.spaces)}")
                offset += 7
                seq = (seq + 1) & 0x0F
            return lines

        # Standard ELM327 ATCAF1 + ATH0 multi-frame output:
        #   023
        #   0: 62 D8 30 ...
        #   1: ...
        lines.append(f"{total_len:03X}")
        offset = 0
        line_idx = 0
        first_chunk = payload[:6]
        lines.append(f"{line_idx:X}:{sep}{self._fmt_bytes(first_chunk, state.spaces)}" if state.spaces else f"{line_idx:X}:{self._fmt_bytes(first_chunk, False)}")
        offset = 6
        line_idx = 1
        while offset < total_len:
            chunk = payload[offset : offset + 7]
            if state.spaces:
                lines.append(f"{line_idx:X}: {self._fmt_bytes(chunk, True)}")
            else:
                lines.append(f"{line_idx:X}:{self._fmt_bytes(chunk, False)}")
            offset += 7
            line_idx = (line_idx + 1) & 0x0F
        return lines


async def run_server(args: argparse.Namespace) -> None:
    pids_path = Path(args.pids)
    if not pids_path.exists():
        LOG.error("PID catalog not found: %s", pids_path)
        sys.exit(1)

    catalog = PidCatalog(pids_path)
    model = TelemetryModel(mode=args.mode)
    handler = Elm327ClientHandler(
        catalog=catalog,
        model=model,
        mode=args.mode,
        latency_ms=args.latency_ms,
        verbose=args.verbose,
    )

    server = await asyncio.start_server(handler.handle_client, args.host, args.port)
    addrs = ", ".join(str(sock.getsockname()) for sock in (server.sockets or []))
    LOG.info(
        "Corsa-e ELM327 v2.3 Simulator listening on %s | mode=%s | latency=%dms | pids=%d (%s)",
        addrs,
        args.mode,
        args.latency_ms,
        len(catalog.pids),
        catalog.vehicle,
    )

    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            pass

    async with server:
        await stop_event.wait()
    LOG.info("Simulator stopped.")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="ELM327 v2.3 + UDS (0x22) + ISO-TP TCP Simulator for Opel Corsa Electric"
    )
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=35000, help="TCP port (default: 35000)")
    parser.add_argument(
        "--mode",
        choices=("drive", "dc-charge", "isotp-multiframe", "chaos"),
        default="drive",
        help="Simulation profile (default: drive)",
    )
    parser.add_argument(
        "--latency-ms",
        type=int,
        default=15,
        help="Simulated ECU round-trip latency in ms (default: 15)",
    )
    parser.add_argument(
        "--pids",
        default=str(DEFAULT_PIDS_PATH),
        help=f"Path to corsa_e_pids.json (default: {DEFAULT_PIDS_PATH})",
    )
    parser.add_argument("--verbose", action="store_true", help="Log every RX/TX ELM327 frame")
    return parser.parse_args(argv)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    args = parse_args()
    try:
        asyncio.run(run_server(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
