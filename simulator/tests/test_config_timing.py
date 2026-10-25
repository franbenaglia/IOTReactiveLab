import random

import pytest

from iotsim.config import Config, load_config, resolve_password
from iotsim.timing import burst_due, initial_offset, next_delay


def test_yaml_and_cli_overrides(tmp_path):
    f = tmp_path / "c.yaml"
    f.write_text("simulation:\n  devices: 7\n  interval_seconds: 3\nconnection:\n  mode: B\n")
    cfg = load_config(f, {"simulation": {"devices": 50}})
    assert cfg.simulation.devices == 50
    assert cfg.simulation.interval_seconds == 3
    assert cfg.connection.mode == "B"


def test_unknown_key_is_rejected(tmp_path):
    f = tmp_path / "c.yaml"
    f.write_text("simulation:\n  devicez: 7\n")
    with pytest.raises(ValueError, match="devicez"):
        load_config(f)


@pytest.mark.parametrize("override", [
    {"simulation": {"devices": 0}},
    {"simulation": {"mode": "turbo"}},
    {"connection": {"mode": "C"}},
    {"temperature": {"min": 40, "max": 30}},
])
def test_invalid_values_are_rejected(override):
    with pytest.raises(ValueError):
        load_config(None, override)


def test_connection_count():
    cfg = load_config(None, {"simulation": {"devices": 250}, "connection": {"mode": "B", "devices_per_connection": 100}})
    assert cfg.connection_count == 3
    cfg = load_config(None, {"simulation": {"devices": 250}})
    assert cfg.connection_count == 250


def test_password_resolution_order(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("MQTT_SIMULATOR_PASSWORD=desde-dotenv\n")
    monkeypatch.delenv("MQTT_SIMULATOR_PASSWORD", raising=False)
    assert resolve_password(None, "MQTT_SIMULATOR_PASSWORD", env) == "desde-dotenv"
    monkeypatch.setenv("MQTT_SIMULATOR_PASSWORD", "desde-env")
    assert resolve_password(None, "MQTT_SIMULATOR_PASSWORD", env) == "desde-env"
    assert resolve_password("explicita", "MQTT_SIMULATOR_PASSWORD", env) == "explicita"


def test_normal_interval_is_fixed():
    cfg = load_config(None, {"simulation": {"interval_seconds": 2}})
    rng = random.Random(1)
    assert {next_delay(cfg, rng) for _ in range(100)} == {2}


def test_random_interval_within_jitter():
    cfg = load_config(None, {"simulation": {"interval_seconds": 2, "mode": "random", "random_jitter": 0.25}})
    rng = random.Random(1)
    delays = [next_delay(cfg, rng) for _ in range(2000)]
    assert min(delays) >= 1.5 and max(delays) <= 2.5
    assert len(set(delays)) > 1000


def test_load_mode_derives_interval_from_rate():
    cfg = load_config(None, {"simulation": {"devices": 1000, "mode": "load", "load_rate": 4000}})
    assert next_delay(cfg, random.Random(1)) == pytest.approx(0.25)


def test_initial_offset_spreads_devices():
    cfg = load_config(None, {"simulation": {"interval_seconds": 5}})
    rng = random.Random(1)
    offsets = [initial_offset(cfg, rng) for _ in range(1000)]
    assert 0 <= min(offsets) and max(offsets) < 5


def test_burst_due():
    cfg = load_config(None, {"simulation": {"mode": "burst", "burst_every_seconds": 10}})
    assert not burst_due(cfg, 5, 0)
    assert burst_due(cfg, 10, 0)
    assert not burst_due(Config(), 100, 0)
