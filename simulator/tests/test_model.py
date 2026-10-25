import random

from iotsim.config import BehaviorConfig, TemperatureConfig
from iotsim.model import TemperatureModel


def make(seed=1, **kw):
    return TemperatureModel(TemperatureConfig(**kw), BehaviorConfig(), random.Random(seed))


def test_values_stay_within_limits():
    m = make(min=18, max=35, variation=3.0, reversion=0.0)
    values = [m.next() for _ in range(5000)]
    assert min(values) >= 18 and max(values) <= 35


def test_evolution_is_progressive_not_independent_noise():
    m = make(variation=0.5)
    values = [m.next() for _ in range(2000)]
    steps = [abs(b - a) for a, b in zip(values, values[1:])]
    # Paso típico ~ variation; un salto de 4σ prácticamente no ocurre.
    assert max(steps) < 0.5 * 5
    assert sum(steps) / len(steps) < 0.6


def test_reversion_keeps_series_near_base():
    m = make(variation=0.5, reversion=0.2, min=-100, max=100, initial=24, initial_spread=0)
    values = [m.next() for _ in range(5000)]
    assert abs(sum(values) / len(values) - 24) < 1.0


def test_initial_value_depends_on_device():
    starts = {round(make(seed=s).base, 3) for s in range(50)}
    assert len(starts) > 40


def test_same_seed_is_reproducible():
    a, b = make(seed=7), make(seed=7)
    assert [a.next() for _ in range(100)] == [b.next() for _ in range(100)]


def test_spikes_exceed_max_for_configured_samples():
    beh = BehaviorConfig(temperature_spikes=True, spike_probability=1.0, spike_degrees=20, spike_samples=3)
    m = TemperatureModel(TemperatureConfig(min=18, max=35), beh, random.Random(1))
    assert all(v > 35 for v in [m.next() for _ in range(3)])
