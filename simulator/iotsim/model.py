"""Modelos de evolución temporal de temperatura y humedad.

Cada muestra depende de la anterior (paseo aleatorio con reversión a la
media), así las series se parecen a un ambiente real y no a ruido blanco:

    x[n+1] = x[n] + N(0, variation) + reversion * (base - x[n])

`reversion` evita que el paseo se vaya contra los límites y se quede ahí; con
0 es un paseo aleatorio puro acotado por [min, max].
"""
from __future__ import annotations

import random

from .config import BehaviorConfig, HumidityConfig, TemperatureConfig


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


class TemperatureModel:
    def __init__(self, cfg: TemperatureConfig, behavior: BehaviorConfig, rng: random.Random):
        self._cfg = cfg
        self._behavior = behavior
        self._rng = rng
        start = rng.uniform(cfg.initial - cfg.initial_spread, cfg.initial + cfg.initial_spread)
        self.base = clamp(start, cfg.min, cfg.max)
        self.value = self.base
        self._spike_left = 0

    def next(self) -> float:
        c = self._cfg
        step = self._rng.gauss(0.0, c.variation) + c.reversion * (self.base - self.value)
        self.value = clamp(self.value + step, c.min, c.max)
        reading = self.value
        # Picos: superan max a propósito, para disparar alertas (Pipeline C).
        b = self._behavior
        if b.temperature_spikes:
            if self._spike_left == 0 and self._rng.random() < b.spike_probability:
                self._spike_left = max(1, b.spike_samples)
            if self._spike_left > 0:
                self._spike_left -= 1
                reading += b.spike_degrees
        return round(reading, 2)

    @property
    def in_spike(self) -> bool:
        return self._spike_left > 0


class HumidityModel:
    def __init__(self, cfg: HumidityConfig, rng: random.Random):
        self._cfg = cfg
        self._rng = rng
        self.value = clamp(rng.uniform(cfg.initial - 10, cfg.initial + 10), 0.0, 100.0)
        self.base = self.value

    def next(self) -> float:
        step = self._rng.gauss(0.0, self._cfg.variation) + 0.05 * (self.base - self.value)
        self.value = clamp(self.value + step, 0.0, 100.0)
        return round(self.value, 1)
