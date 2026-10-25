"""Pruebas contra un Mosquitto real: docker compose up -d mosquitto.

Se saltan solas si el broker no responde o faltan contraseñas en .env.
"""
import asyncio
import json
import socket
import threading
import time
from pathlib import Path

import paho.mqtt.client as mqtt
import pytest

from iotsim.config import load_config, resolve_password
from iotsim.connection import AsyncMqttConnection
from iotsim.runner import Simulation
from iotsim.stats import Stats

pytestmark = pytest.mark.integration

DOTENV = Path(__file__).resolve().parents[2] / ".env"
SIM_PASS = resolve_password(None, "MQTT_SIMULATOR_PASSWORD", DOTENV)
BACKEND_PASS = resolve_password(None, "MQTT_BACKEND_PASSWORD", DOTENV)


def broker_up() -> bool:
    try:
        socket.create_connection(("localhost", 1883), timeout=1).close()
        return True
    except OSError:
        return False


if not (broker_up() and SIM_PASS and BACKEND_PASS):
    pytest.skip("Mosquitto no disponible o faltan contraseñas en .env", allow_module_level=True)


class Subscriber:
    """Suscriptor de verificación (hilo propio de paho, independiente del simulador)."""

    def __init__(self, topic: str):
        self.messages: list[tuple[str, bytes, bool]] = []
        self._lock = threading.Lock()
        self._ready = threading.Event()
        c = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                        client_id=f"test-sub-{time.monotonic_ns()}", protocol=mqtt.MQTTv5)
        c.username_pw_set("backend", BACKEND_PASS)
        c.on_connect = lambda cl, u, f, rc, p: cl.subscribe(topic, qos=1)
        c.on_subscribe = lambda cl, u, mid, rcs, p: self._ready.set()
        c.on_message = self._on_message
        self._c = c

    def _on_message(self, c, u, msg):
        with self._lock:
            self.messages.append((msg.topic, msg.payload, msg.retain))

    def __enter__(self):
        self._c.connect("localhost", 1883, 30)
        self._c.loop_start()
        assert self._ready.wait(5)
        return self

    def __exit__(self, *exc):
        self._c.loop_stop()
        self._c.disconnect()

    def live(self, prefix: str):
        with self._lock:
            return [m for m in self.messages if m[0].startswith(prefix) and not m[2]]


@pytest.fixture(scope="module", autouse=True)
def clear_test_retained():
    """Los status retained de los dispositivos de prueba no deben quedar en
    el broker: el backend los vería como dispositivos registrados."""
    yield
    import subprocess
    import sys
    tool = Path(__file__).resolve().parents[1] / "tools" / "clear_retained.py"
    subprocess.run([sys.executable, str(tool), "--prefix", "itest-", "--wait", "2"], check=False)


def cfg_for(prefix: str, **sim):
    cfg = load_config(None, {
        "simulation": {"id_prefix": prefix, **sim},
        "stats": {"interval_seconds": 60},
    })
    cfg.broker.password = SIM_PASS
    return cfg


async def run_sim(cfg) -> Stats:
    return await Simulation(cfg).run(printer=lambda *_: None, install_signals=False)


async def test_mode_a_publishes_and_every_message_is_confirmed_and_received():
    prefix = f"itest-{int(time.time()) % 100000}a-"
    cfg = cfg_for(prefix, devices=5, interval_seconds=0.2, duration_seconds=2)
    with Subscriber(f"iot/devices/+/telemetry") as sub:
        stats = await run_sim(cfg)
        await asyncio.sleep(0.5)
        received = sub.live(f"iot/devices/{prefix}")
    assert stats.telemetry_requested > 0
    assert stats.telemetry_confirmed == stats.telemetry_requested
    assert stats.errors == 0
    assert len(received) == stats.telemetry_confirmed
    ids = {json.loads(p)["deviceId"] for _, p, _ in received}
    assert len(ids) == 5


async def test_mode_b_shares_connections_and_keeps_device_ids():
    prefix = f"itest-{int(time.time()) % 100000}b-"
    cfg = cfg_for(prefix, devices=20, interval_seconds=0.5, duration_seconds=2)
    cfg.connection.mode = "B"
    cfg.connection.devices_per_connection = 5
    sim = Simulation(cfg)
    with Subscriber("iot/devices/+/telemetry") as sub:
        stats = await sim.run(printer=lambda *_: None, install_signals=False)
        await asyncio.sleep(0.5)
        received = sub.live(f"iot/devices/{prefix}")
    assert stats.connections_expected == 4
    assert stats.telemetry_confirmed == stats.telemetry_requested > 0
    ids = {json.loads(p)["deviceId"] for _, p, _ in received}
    assert len(ids) == 20
    for topic, payload, _ in received:
        assert json.loads(payload)["deviceId"] == topic.split("/")[2]


async def test_lwt_offline_is_published_when_device_drops():
    dev = f"itest-{int(time.time()) % 100000}-lwt"
    topic = f"iot/devices/{dev}/status"
    cfg = cfg_for("x-")
    stats = Stats()
    conn = AsyncMqttConnection(dev, cfg.broker, cfg.connection, stats, asyncio.Semaphore(1),
                               will=(topic, b'{"deviceId":"%s","status":"offline"}' % dev.encode()))
    with Subscriber(topic) as sub:
        assert await conn.connect()
        await conn.disconnect_with_will()
        await asyncio.sleep(1)
        statuses = [json.loads(p)["status"] for _, p, _ in sub.live(topic)]
    assert statuses == ["offline"]


async def test_reconnects_automatically_after_connection_loss():
    cfg = cfg_for("x-")
    stats = Stats()
    conn = AsyncMqttConnection(f"itest-{int(time.time()) % 100000}-rc", cfg.broker, cfg.connection, stats,
                               asyncio.Semaphore(1))
    assert await conn.connect()
    conn._client.socket().shutdown(socket.SHUT_RDWR)  # corte abrupto, sin DISCONNECT
    for _ in range(50):
        await asyncio.sleep(0.1)
        if stats.reconnections:
            break
    assert stats.reconnections == 1
    assert conn.connected.is_set()
    await conn.close()


async def test_wrong_password_is_rejected():
    cfg = cfg_for("x-")
    cfg.broker.password = "incorrecta"
    stats = Stats()
    conn = AsyncMqttConnection("itest-badpass", cfg.broker, cfg.connection, stats, asyncio.Semaphore(1))
    assert not await conn.connect()
    assert stats.connect_failures == 1


async def test_invalid_messages_are_published_and_violate_contract():
    from iotsim.contract import validate_telemetry
    prefix = f"itest-{int(time.time()) % 100000}i-"
    cfg = cfg_for(prefix, devices=3, interval_seconds=0.1, duration_seconds=1.5)
    cfg.behavior.invalid_messages = True
    cfg.behavior.invalid_percentage = 100
    with Subscriber("iot/devices/+/telemetry") as sub:
        stats = await run_sim(cfg)
        await asyncio.sleep(0.5)
        received = sub.live(f"iot/devices/{prefix}")
    assert stats.invalid_sent == stats.telemetry_requested > 0
    assert received and all(validate_telemetry(t, p) for t, p, _ in received)
