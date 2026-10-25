"""Configuración: config.yaml + argumentos CLI + contraseña desde el entorno."""
from __future__ import annotations

import os
from dataclasses import dataclass, field, fields, is_dataclass
from pathlib import Path
from typing import Any

import yaml


@dataclass
class BrokerConfig:
    host: str = "localhost"
    port: int = 1883
    username: str = "simulator"
    password: str | None = None
    keepalive: int = 30
    qos: int = 1


@dataclass
class SimulationConfig:
    devices: int = 10
    interval_seconds: float = 5.0
    duration_seconds: float = 0.0
    id_prefix: str = "esp32-sim-"
    id_offset: int = 0
    mode: str = "normal"  # normal | random | burst | load
    random_jitter: float = 0.3
    burst_every_seconds: float = 30.0
    burst_size: int = 20
    load_rate: float = 1000.0


@dataclass
class ConnectionConfig:
    mode: str = "A"  # A: una conexión por dispositivo · B: compartidas
    devices_per_connection: int = 100
    max_concurrent_connects: int = 50
    max_inflight: int = 100
    max_queued: int = 10000
    ack_timeout_seconds: float = 30.0


@dataclass
class TemperatureConfig:
    min: float = 18.0
    max: float = 35.0
    initial: float = 24.0
    initial_spread: float = 3.0
    variation: float = 0.5
    reversion: float = 0.05


@dataclass
class HumidityConfig:
    enabled: bool = False
    initial: float = 50.0
    variation: float = 1.0


@dataclass
class BehaviorConfig:
    random_disconnects: bool = False
    disconnect_probability: float = 0.01
    disconnect_seconds: list[float] = field(default_factory=lambda: [5.0, 20.0])
    invalid_messages: bool = False
    invalid_percentage: float = 5.0
    temperature_spikes: bool = False
    spike_probability: float = 0.005
    spike_degrees: float = 15.0
    spike_samples: int = 5


@dataclass
class StatsConfig:
    interval_seconds: float = 5.0


@dataclass
class Config:
    broker: BrokerConfig = field(default_factory=BrokerConfig)
    simulation: SimulationConfig = field(default_factory=SimulationConfig)
    connection: ConnectionConfig = field(default_factory=ConnectionConfig)
    temperature: TemperatureConfig = field(default_factory=TemperatureConfig)
    humidity: HumidityConfig = field(default_factory=HumidityConfig)
    behavior: BehaviorConfig = field(default_factory=BehaviorConfig)
    stats: StatsConfig = field(default_factory=StatsConfig)
    seed: int | None = None

    def validate(self) -> None:
        s, c, t = self.simulation, self.connection, self.temperature
        if s.devices < 1:
            raise ValueError("simulation.devices debe ser >= 1")
        if s.interval_seconds <= 0:
            raise ValueError("simulation.interval_seconds debe ser > 0")
        if s.mode not in ("normal", "random", "burst", "load"):
            raise ValueError(f"simulation.mode inválido: {s.mode}")
        if s.mode == "load" and s.load_rate <= 0:
            raise ValueError("simulation.load_rate debe ser > 0")
        if not 0 <= s.random_jitter < 1:
            raise ValueError("simulation.random_jitter debe estar en [0, 1)")
        if c.mode not in ("A", "B"):
            raise ValueError(f"connection.mode inválido: {c.mode}")
        if c.devices_per_connection < 1:
            raise ValueError("connection.devices_per_connection debe ser >= 1")
        if t.min >= t.max:
            raise ValueError("temperature.min debe ser < temperature.max")
        if self.broker.qos not in (0, 1):
            raise ValueError("broker.qos debe ser 0 o 1 (el contrato usa 1)")
        if not 0 <= self.behavior.invalid_percentage <= 100:
            raise ValueError("behavior.invalid_percentage debe estar en [0, 100]")

    @property
    def effective_interval(self) -> float:
        """Intervalo por dispositivo. En modo load se deriva del caudal total."""
        s = self.simulation
        if s.mode == "load":
            return s.devices / s.load_rate
        return s.interval_seconds

    @property
    def connection_count(self) -> int:
        if self.connection.mode == "A":
            return self.simulation.devices
        per = self.connection.devices_per_connection
        return -(-self.simulation.devices // per)  # techo


def _merge(target: Any, data: dict[str, Any], path: str = "") -> None:
    known = {f.name: f for f in fields(target)}
    for key, value in (data or {}).items():
        if key not in known:
            raise ValueError(f"clave desconocida en la configuración: {path}{key}")
        current = getattr(target, key)
        if is_dataclass(current):
            if not isinstance(value, dict):
                raise ValueError(f"{path}{key} debe ser una sección")
            _merge(current, value, f"{path}{key}.")
        else:
            setattr(target, key, value)


def load_config(path: str | Path | None, overrides: dict[str, dict[str, Any]] | None = None) -> Config:
    cfg = Config()
    if path is not None:
        with open(path, encoding="utf-8") as fh:
            _merge(cfg, yaml.safe_load(fh) or {})
    if overrides:
        _merge(cfg, {k: v for k, v in overrides.items() if v})
    cfg.validate()
    return cfg


def read_dotenv(path: Path) -> dict[str, str]:
    """Lector mínimo de .env (KEY=VALUE), para no sumar dependencias."""
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip("'\"")
    return values


def resolve_password(explicit: str | None, env_var: str, dotenv: Path) -> str | None:
    """Orden: argumento > variable de entorno > .env de la raíz del repo."""
    return explicit or os.environ.get(env_var) or read_dotenv(dotenv).get(env_var) or None
