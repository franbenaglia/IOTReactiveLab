"""Contadores del simulador.

Distingue publicación SOLICITADA (se llamó a publish y paho la aceptó) de
publicación CONFIRMADA (llegó el PUBACK del broker, QoS 1). La diferencia
entre ambas, en un instante dado, son los mensajes en vuelo.
"""
from __future__ import annotations

import time
from dataclasses import asdict, dataclass, field


@dataclass
class Stats:
    devices: int = 0
    connections_expected: int = 0
    connections_active: int = 0
    telemetry_requested: int = 0     # aceptados por paho para enviar
    telemetry_confirmed: int = 0     # con PUBACK
    telemetry_unconfirmed: int = 0   # sin PUBACK dentro de ack_timeout
    publish_rejected: int = 0        # paho los rechazó localmente (cola llena, sin conexión)
    skipped_offline: int = 0         # el dispositivo estaba desconectado: no publicó
    invalid_sent: int = 0            # mensajes inválidos a propósito (subconjunto de requested)
    status_sent: int = 0
    connect_failures: int = 0
    reconnections: int = 0
    simulated_disconnects: int = 0
    ack_latency_sum: float = 0.0
    ack_latency_max: float = 0.0
    started_at: float = field(default_factory=time.monotonic)

    # Ventana del último reporte, para tasas.
    _last_t: float = field(default=0.0, repr=False)
    _last_confirmed: int = field(default=0, repr=False)
    _last_requested: int = field(default=0, repr=False)
    _win_lat_sum: float = field(default=0.0, repr=False)
    _win_lat_n: int = field(default=0, repr=False)
    _win_lat_max: float = field(default=0.0, repr=False)

    def record_ack(self, latency: float) -> None:
        self.telemetry_confirmed += 1
        self.ack_latency_sum += latency
        self.ack_latency_max = max(self.ack_latency_max, latency)
        self._win_lat_sum += latency
        self._win_lat_n += 1
        self._win_lat_max = max(self._win_lat_max, latency)

    @property
    def errors(self) -> int:
        return self.telemetry_unconfirmed + self.publish_rejected + self.connect_failures

    @property
    def in_flight(self) -> int:
        return self.telemetry_requested - self.telemetry_confirmed - self.telemetry_unconfirmed

    @property
    def elapsed(self) -> float:
        return time.monotonic() - self.started_at

    def report_line(self) -> str:
        now = time.monotonic()
        dt = (now - self._last_t) if self._last_t else self.elapsed
        dt = max(dt, 1e-6)
        conf_rate = (self.telemetry_confirmed - self._last_confirmed) / dt
        req_rate = (self.telemetry_requested - self._last_requested) / dt
        lat = (self._win_lat_sum / self._win_lat_n * 1000) if self._win_lat_n else 0.0
        line = (
            f"[{self.elapsed:7.1f}s] disp={self.devices} conn={self.connections_active}/{self.connections_expected}"
            f" | telemetría pedidos={self.telemetry_requested} confirmados={self.telemetry_confirmed}"
            f" enVuelo={self.in_flight}"
            f" | {req_rate:8.1f} pedidos/s {conf_rate:8.1f} conf/s"
            f" | ack avg={lat:6.1f}ms max={self._win_lat_max * 1000:6.1f}ms"
            f" | errores={self.errors} reconex={self.reconnections}"
        )
        extras = []
        if self.invalid_sent:
            extras.append(f"inválidos={self.invalid_sent}")
        if self.simulated_disconnects:
            extras.append(f"descSimuladas={self.simulated_disconnects}")
        if self.skipped_offline:
            extras.append(f"omitidosOffline={self.skipped_offline}")
        if extras:
            line += " | " + " ".join(extras)
        self._last_t = now
        self._last_confirmed = self.telemetry_confirmed
        self._last_requested = self.telemetry_requested
        self._win_lat_sum, self._win_lat_n, self._win_lat_max = 0.0, 0, 0.0
        return line

    def summary(self) -> dict:
        d = {k: v for k, v in asdict(self).items() if not k.startswith("_") and k != "started_at"}
        d["elapsed_seconds"] = round(self.elapsed, 2)
        d["errors"] = self.errors
        d["in_flight"] = self.in_flight
        d["ack_latency_avg_ms"] = round(self.ack_latency_sum / self.telemetry_confirmed * 1000, 2) if self.telemetry_confirmed else None
        d["ack_latency_max_ms"] = round(self.ack_latency_max * 1000, 2)
        d["confirmed_per_second"] = round(self.telemetry_confirmed / max(self.elapsed, 1e-6), 1)
        d.pop("ack_latency_sum")
        d.pop("ack_latency_max")
        return d
