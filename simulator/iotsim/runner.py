"""Orquesta la simulación: crea dispositivos y conexiones, corre una tarea
asyncio por dispositivo y apaga en orden.

Modo A: una conexión MQTT por dispositivo, client_id = deviceId, LWT propio.
Modo B: K conexiones compartidas por N dispositivos. Optimización del
generador de carga: NO reproduce dispositivos independientes (un LWT por
conexión, no por dispositivo; una caída afecta a todos los de esa conexión).
"""
from __future__ import annotations

import asyncio
import logging
import random
import resource
import signal
import time

from . import contract
from .config import Config
from .connection import AsyncMqttConnection
from .device import VirtualDevice, device_ids
from .stats import Stats
from .timing import burst_due, initial_offset, next_delay

log = logging.getLogger(__name__)

DRAIN_TIMEOUT = 10.0


def ensure_fd_limit(needed: int) -> None:
    """Una conexión = un descriptor. Sube el límite blando si hace falta."""
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft >= needed:
        return
    target = needed if hard == resource.RLIM_INFINITY else min(needed, hard)
    resource.setrlimit(resource.RLIMIT_NOFILE, (target, hard))
    if target < needed:
        log.warning("límite de descriptores %d < %d necesarios: subir 'ulimit -n'", target, needed)


class Simulation:
    def __init__(self, cfg: Config, stats: Stats | None = None):
        self.cfg = cfg
        self.stats = stats or Stats()
        self.rng = random.Random(cfg.seed)
        self.stop = asyncio.Event()
        self.devices: list[VirtualDevice] = []
        self.connections: list[AsyncMqttConnection] = []
        self._conn_of: dict[str, AsyncMqttConnection] = {}
        self._started = 0.0

    # ------------------------------------------------------------ armado
    def _build(self) -> None:
        cfg = self.cfg
        sem = asyncio.Semaphore(cfg.connection.max_concurrent_connects)
        ids = device_ids(cfg.simulation.id_prefix, cfg.simulation.devices, cfg.simulation.id_offset)
        self.devices = [VirtualDevice.create(i, cfg, random.Random(self.rng.random())) for i in ids]
        if cfg.connection.mode == "A":
            for dev in self.devices:
                conn = AsyncMqttConnection(dev.device_id, cfg.broker, cfg.connection, self.stats, sem,
                                           will=(dev.status_topic, dev.will_payload()))
                conn.on_reconnected = self._make_reconnected_hook([dev], conn)
                self.connections.append(conn)
                self._conn_of[dev.device_id] = conn
        else:
            per = cfg.connection.devices_per_connection
            prefix = cfg.simulation.id_prefix.rstrip("-")
            for k in range(cfg.connection_count):
                group = self.devices[k * per:(k + 1) * per]
                conn = AsyncMqttConnection(f"{prefix}-shared-{cfg.simulation.id_offset + k + 1:04d}",
                                           cfg.broker, cfg.connection, self.stats, sem)
                conn.on_reconnected = self._make_reconnected_hook(group, conn)
                self.connections.append(conn)
                for dev in group:
                    self._conn_of[dev.device_id] = conn
        self.stats.devices = len(self.devices)
        self.stats.connections_expected = len(self.connections)

    def _make_reconnected_hook(self, devs: list[VirtualDevice], conn: AsyncMqttConnection):
        def hook() -> None:
            for d in devs:
                if d.online:
                    self._publish_status(d, conn, "online")
        return hook

    # --------------------------------------------------------- publicación
    def _publish_status(self, dev: VirtualDevice, conn: AsyncMqttConnection, value: str) -> None:
        if conn.publish(dev.status_topic, dev.status_payload(value), retain=True, telemetry=False):
            self.stats.status_sent += 1

    def _publish_telemetry(self, dev: VirtualDevice, conn: AsyncMqttConnection) -> None:
        if not conn.connected.is_set():
            self.stats.skipped_offline += 1
            dev.next_telemetry()  # el dispositivo midió igual: la secuencia avanza
            return
        msg = dev.next_telemetry()
        b = self.cfg.behavior
        if b.invalid_messages and self.rng.random() * 100 < b.invalid_percentage:
            _, payload = contract.invalid_payload(msg, self.rng)
            if conn.publish(dev.topic, payload):
                self.stats.invalid_sent += 1
            return
        conn.publish(dev.topic, contract.encode(msg))

    # ------------------------------------------------------- comportamiento
    async def _simulate_disconnect(self, dev: VirtualDevice, conn: AsyncMqttConnection) -> None:
        lo, hi = self.cfg.behavior.disconnect_seconds
        self.stats.simulated_disconnects += 1
        if self.cfg.connection.mode == "A":
            # Caída "real": el broker publica el LWT offline.
            dev.online = False
            await conn.disconnect_with_will()
            await self._sleep(self.rng.uniform(lo, hi))
            if self.stop.is_set():
                return
            if await conn.connect_with_retry(self.stop):
                self.stats.reconnections += 1
                dev.online = True
                self._publish_status(dev, conn, "online")
        else:
            # Modo B: la conexión es compartida; la caída se simula con status.
            dev.online = False
            self._publish_status(dev, conn, "offline")
            await self._sleep(self.rng.uniform(lo, hi))
            if not self.stop.is_set():
                dev.online = True
                self._publish_status(dev, conn, "online")

    async def _sleep(self, seconds: float) -> None:
        try:
            await asyncio.wait_for(self.stop.wait(), max(0.0, seconds))
        except asyncio.TimeoutError:
            pass

    async def _device_loop(self, dev: VirtualDevice) -> None:
        conn = self._conn_of[dev.device_id]
        loop = asyncio.get_running_loop()
        cfg = self.cfg
        await conn.connected.wait()
        dev.online = True
        self._publish_status(dev, conn, "online")
        next_t = loop.time() + initial_offset(cfg, dev.rng)
        last_burst = 0.0
        while not self.stop.is_set():
            await self._sleep(next_t - loop.time())
            if self.stop.is_set():
                break
            b = cfg.behavior
            if b.random_disconnects and dev.rng.random() < b.disconnect_probability:
                await self._simulate_disconnect(dev, conn)
                next_t = loop.time() + next_delay(cfg, dev.rng)
                continue
            elapsed = loop.time() - self._started
            if burst_due(cfg, elapsed, last_burst):
                last_burst = elapsed
                for _ in range(cfg.simulation.burst_size):
                    self._publish_telemetry(dev, conn)
            else:
                self._publish_telemetry(dev, conn)
            next_t += next_delay(cfg, dev.rng)
            # Si el loop se atrasó (sobrecarga), no acumular publicaciones
            # "debidas": se reprograma desde ahora.
            now = loop.time()
            if next_t < now:
                next_t = now

    async def _stats_loop(self, printer) -> None:
        while not self.stop.is_set():
            await self._sleep(self.cfg.stats.interval_seconds)
            printer(self.stats.report_line())

    # --------------------------------------------------------------- run
    async def run(self, printer=print, install_signals: bool = True) -> Stats:
        loop = asyncio.get_running_loop()
        if install_signals:
            for sig in (signal.SIGINT, signal.SIGTERM):
                loop.add_signal_handler(sig, self.stop.set)
        ensure_fd_limit(self.cfg.connection_count + 256)
        self._build()
        self._started = loop.time()
        self.stats.started_at = time.monotonic()
        c = self.cfg
        printer(f"Simulador: {len(self.devices)} dispositivos, modo conexión {c.connection.mode} "
                f"({len(self.connections)} conexiones), modo {c.simulation.mode}, "
                f"intervalo {c.effective_interval:.3f}s, broker {c.broker.host}:{c.broker.port}")

        stats_task = loop.create_task(self._stats_loop(printer))
        connect_tasks = [loop.create_task(conn.connect_with_retry(self.stop)) for conn in self.connections]
        device_tasks = [loop.create_task(self._device_loop(d)) for d in self.devices]

        duration = c.simulation.duration_seconds
        if duration > 0:
            await self._sleep(duration)
            self.stop.set()
        else:
            await self.stop.wait()

        printer("Deteniendo: publicando offline y esperando confirmaciones...")
        for t in device_tasks + connect_tasks:
            t.cancel()
        await asyncio.gather(*device_tasks, *connect_tasks, return_exceptions=True)
        for dev in self.devices:
            conn = self._conn_of[dev.device_id]
            if conn.connected.is_set():
                dev.online = False
                self._publish_status(dev, conn, "offline")
        await asyncio.gather(*(conn.drain(DRAIN_TIMEOUT) for conn in self.connections))
        await asyncio.gather(*(conn.close() for conn in self.connections), return_exceptions=True)
        stats_task.cancel()
        printer(self.stats.report_line())
        return self.stats
