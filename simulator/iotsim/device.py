"""Dispositivo virtual: identidad, secuencia, modelos y estado lógico."""
from __future__ import annotations

import random
from dataclasses import dataclass, field

from . import contract
from .config import Config
from .model import HumidityModel, TemperatureModel


def device_ids(prefix: str, count: int, offset: int = 0) -> list[str]:
    """IDs únicos y estables: esp32-sim-00001 ... (5 dígitos alcanzan para 99.999)."""
    width = max(5, len(str(offset + count)))
    return [f"{prefix}{offset + i:0{width}d}" for i in range(1, count + 1)]


@dataclass
class VirtualDevice:
    device_id: str
    temperature: TemperatureModel
    humidity: HumidityModel | None
    rng: random.Random
    sequence: int = 0
    online: bool = False
    _topic: str = field(init=False, repr=False)
    _status_topic: str = field(init=False, repr=False)

    def __post_init__(self) -> None:
        self._topic = contract.telemetry_topic(self.device_id)
        self._status_topic = contract.status_topic(self.device_id)

    @classmethod
    def create(cls, device_id: str, cfg: Config, rng: random.Random) -> "VirtualDevice":
        hum = HumidityModel(cfg.humidity, rng) if cfg.humidity.enabled else None
        return cls(device_id, TemperatureModel(cfg.temperature, cfg.behavior, rng), hum, rng)

    @property
    def topic(self) -> str:
        return self._topic

    @property
    def status_topic(self) -> str:
        return self._status_topic

    def next_telemetry(self) -> dict:
        """Avanza los modelos y la secuencia. Primera medición: sequence=1."""
        self.sequence += 1
        hum = self.humidity.next() if self.humidity else None
        return contract.telemetry(self.device_id, self.temperature.next(), hum, self.sequence)

    def status_payload(self, value: str) -> bytes:
        return contract.encode(contract.status(self.device_id, value))

    def will_payload(self) -> bytes:
        # El LWT se fija al conectar: sin timestamp (quedaría viejo).
        return contract.encode(contract.status(self.device_id, "offline", with_timestamp=False))
