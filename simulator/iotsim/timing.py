"""Cuándo publica cada dispositivo, según el modo de simulación."""
from __future__ import annotations

import random

from .config import Config


def next_delay(cfg: Config, rng: random.Random) -> float:
    """Tiempo hasta la próxima muestra.

    normal: intervalo fijo. random: intervalo ± jitter (cada dispositivo
    deriva distinto). load: intervalo = devices / load_rate, para un caudal
    total objetivo. burst: intervalo fijo; las ráfagas las agrega el runner.
    """
    base = cfg.effective_interval
    if cfg.simulation.mode == "random":
        j = cfg.simulation.random_jitter
        return base * rng.uniform(1 - j, 1 + j)
    return base


def initial_offset(cfg: Config, rng: random.Random) -> float:
    """Desfase inicial aleatorio dentro de un intervalo: sin esto, N
    dispositivos publicarían todos en el mismo instante en cada ciclo."""
    return rng.uniform(0, cfg.effective_interval)


def burst_due(cfg: Config, elapsed: float, last_burst: float) -> bool:
    return cfg.simulation.mode == "burst" and elapsed - last_burst >= cfg.simulation.burst_every_seconds
