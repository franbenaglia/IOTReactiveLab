"""Conexión MQTT de paho-mqtt integrada al event loop de asyncio (ADR-008).

paho no es asyncio nativo, pero expone callbacks de socket. Registrándolos,
el propio event loop avisa cuando el socket es legible/escribible y llama a
loop_read/loop_write: un solo hilo atiende miles de conexiones, sin un hilo
`loop_start()` por cliente. Es el patrón del ejemplo oficial loop_asyncio.py.

Lo único bloqueante es connect()/reconnect() (DNS + TCP): se ejecutan en un
executor, limitados por un semáforo compartido.
"""
from __future__ import annotations

import asyncio
import logging
import threading
import time
from collections.abc import Callable

import paho.mqtt.client as mqtt
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.reasoncodes import ReasonCode

from .config import BrokerConfig, ConnectionConfig
from .stats import Stats

log = logging.getLogger(__name__)

BACKOFF_INITIAL = 1.0
BACKOFF_MAX = 30.0
CONNECT_TIMEOUT = 10.0


class AsyncMqttConnection:
    def __init__(
        self,
        client_id: str,
        broker: BrokerConfig,
        conn_cfg: ConnectionConfig,
        stats: Stats,
        connect_semaphore: asyncio.Semaphore,
        will: tuple[str, bytes] | None = None,
    ):
        self.client_id = client_id
        self._broker = broker
        self._cfg = conn_cfg
        self._stats = stats
        self._sem = connect_semaphore
        self._loop = asyncio.get_running_loop()
        self._loop_thread = threading.get_ident()
        self._misc_task: asyncio.Task | None = None
        self._reconnect_task: asyncio.Task | None = None
        self._connack: asyncio.Future | None = None
        self._intentional = False      # desconexión pedida: no reconectar solo
        self._ever_connected = False
        self.connected = asyncio.Event()
        self.disconnected = asyncio.Event()
        self.disconnected.set()
        # Callback del dueño tras cada reconexión (p. ej. republicar "online").
        self.on_reconnected: Callable[[], None] | None = None
        # mid -> (instante de envío, es_telemetría). Acotado por max_queued.
        self._pending: dict[int, tuple[float, bool]] = {}

        c = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=client_id,
            protocol=mqtt.MQTTv5,
        )
        if broker.username:
            c.username_pw_set(broker.username, broker.password)
        if will:
            c.will_set(will[0], will[1], qos=1, retain=True)
        c.max_inflight_messages_set(conn_cfg.max_inflight)
        c.max_queued_messages_set(conn_cfg.max_queued)
        c.on_connect = self._on_connect
        c.on_disconnect = self._on_disconnect
        c.on_publish = self._on_publish
        c.on_socket_open = self._on_socket_open
        c.on_socket_close = self._on_socket_close
        c.on_socket_register_write = self._on_socket_register_write
        c.on_socket_unregister_write = self._on_socket_unregister_write
        self._client = c

    # ------------------------------------------------------------ sockets
    def _in_loop(self, fn: Callable, *args) -> None:
        # Durante connect() estos callbacks llegan desde el hilo del executor;
        # el event loop solo se puede tocar desde su propio hilo.
        if threading.get_ident() == self._loop_thread:
            fn(*args)
        else:
            self._loop.call_soon_threadsafe(fn, *args)

    def _on_socket_open(self, client, userdata, sock) -> None:
        def register():
            self._loop.add_reader(sock, client.loop_read)
            if self._misc_task is None or self._misc_task.done():
                self._misc_task = self._loop.create_task(self._misc_loop())
        self._in_loop(register)

    def _on_socket_close(self, client, userdata, sock) -> None:
        def unregister():
            self._loop.remove_reader(sock)
            self._loop.remove_writer(sock)
        self._in_loop(unregister)

    def _on_socket_register_write(self, client, userdata, sock) -> None:
        self._in_loop(self._loop.add_writer, sock, client.loop_write)

    def _on_socket_unregister_write(self, client, userdata, sock) -> None:
        self._in_loop(self._loop.remove_writer, sock)

    async def _misc_loop(self) -> None:
        # loop_misc envía PINGREQ (keepalive) y detecta conexiones muertas.
        try:
            while self._client.loop_misc() == mqtt.MQTT_ERR_SUCCESS:
                self._expire_pending()
                await asyncio.sleep(1)
        except asyncio.CancelledError:
            pass

    # ------------------------------------------------------- callbacks MQTT
    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if self._connack is not None and not self._connack.done():
            self._connack.set_result(reason_code)
        if reason_code.is_failure:
            return
        self._stats.connections_active += 1
        self.connected.set()
        self.disconnected.clear()

    def _on_disconnect(self, client, userdata, flags, reason_code, properties) -> None:
        if self.connected.is_set():
            self._stats.connections_active -= 1
        self.connected.clear()
        self.disconnected.set()
        if self._connack is not None and not self._connack.done():
            self._connack.set_result(reason_code)
        if not self._intentional and self._ever_connected:
            log.debug("%s: conexión perdida (%s), reconectando", self.client_id, reason_code)
            self._schedule_reconnect()

    def _on_publish(self, client, userdata, mid, reason_code, properties) -> None:
        entry = self._pending.pop(mid, None)
        if entry is None:
            return  # ya expirado
        sent_at, is_telemetry = entry
        if is_telemetry:
            self._stats.record_ack(time.monotonic() - sent_at)

    def _expire_pending(self) -> None:
        if not self._pending:
            return
        limit = time.monotonic() - self._cfg.ack_timeout_seconds
        expired = [mid for mid, (t, _) in self._pending.items() if t < limit]
        for mid in expired:
            _, is_telemetry = self._pending.pop(mid)
            if is_telemetry:
                self._stats.telemetry_unconfirmed += 1

    # ------------------------------------------------------------ API
    async def connect(self) -> bool:
        """Conecta (bloqueante en executor) y espera el CONNACK."""
        async with self._sem:
            self._connack = self._loop.create_future()
            try:
                await self._loop.run_in_executor(
                    None, lambda: self._client.connect(self._broker.host, self._broker.port, self._broker.keepalive)
                )
                rc = await asyncio.wait_for(self._connack, CONNECT_TIMEOUT)
            except (OSError, asyncio.TimeoutError) as exc:
                log.debug("%s: fallo de conexión: %r", self.client_id, exc)
                self._stats.connect_failures += 1
                return False
        if rc.is_failure:
            log.warning("%s: el broker rechazó la conexión: %s", self.client_id, rc)
            self._stats.connect_failures += 1
            return False
        self._ever_connected = True
        self._intentional = False
        return True

    async def connect_with_retry(self, stop: asyncio.Event) -> bool:
        delay = BACKOFF_INITIAL
        while not stop.is_set():
            if await self.connect():
                return True
            await asyncio.sleep(delay)
            delay = min(delay * 2, BACKOFF_MAX)
        return False

    def _schedule_reconnect(self) -> None:
        if self._reconnect_task is None or self._reconnect_task.done():
            self._reconnect_task = self._loop.create_task(self._reconnect_loop())

    async def _reconnect_loop(self) -> None:
        delay = BACKOFF_INITIAL
        while not self._intentional:
            await asyncio.sleep(delay)
            if self._intentional:
                return
            async with self._sem:
                self._connack = self._loop.create_future()
                try:
                    await self._loop.run_in_executor(None, self._client.reconnect)
                    rc = await asyncio.wait_for(self._connack, CONNECT_TIMEOUT)
                    ok = not rc.is_failure
                except (OSError, asyncio.TimeoutError):
                    ok = False
            if ok:
                self._stats.reconnections += 1
                if self.on_reconnected:
                    self.on_reconnected()
                return
            self._stats.connect_failures += 1
            delay = min(delay * 2, BACKOFF_MAX)

    def publish(self, topic: str, payload: bytes, retain: bool = False, telemetry: bool = True) -> bool:
        """Encola un mensaje. True si paho lo aceptó (SOLICITADO); la
        confirmación llega después, con el PUBACK."""
        info = self._client.publish(topic, payload, qos=self._broker.qos, retain=retain)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            if telemetry:
                self._stats.publish_rejected += 1
            return False
        if telemetry:
            self._stats.telemetry_requested += 1
        if self._broker.qos == 0:
            if telemetry:
                self._stats.record_ack(0.0)  # QoS 0 no tiene confirmación
        else:
            self._pending[info.mid] = (time.monotonic(), telemetry)
        return True

    @property
    def pending(self) -> int:
        return len(self._pending)

    async def drain(self, timeout: float) -> None:
        """Espera a que lleguen los PUBACK pendientes (o vence el plazo)."""
        end = time.monotonic() + timeout
        while self._pending and self.connected.is_set() and time.monotonic() < end:
            await asyncio.sleep(0.05)

    async def disconnect_with_will(self) -> None:
        """Desconexión que el broker trata como caída: publica el LWT.
        MQTT 5, reason code 0x04 "Disconnect with Will Message"."""
        self._intentional = True
        rc = ReasonCode(PacketTypes.DISCONNECT, identifier=4)
        self._client.disconnect(reasoncode=rc)
        await self._wait_disconnected()

    async def close(self) -> None:
        """Desconexión ordenada (el broker NO publica el LWT)."""
        self._intentional = True
        if self._reconnect_task:
            self._reconnect_task.cancel()
        if self.connected.is_set():
            self._client.disconnect()
            await self._wait_disconnected()
        if self._misc_task:
            self._misc_task.cancel()

    async def _wait_disconnected(self, timeout: float = 5.0) -> None:
        try:
            await asyncio.wait_for(self.disconnected.wait(), timeout)
        except asyncio.TimeoutError:
            log.warning("%s: no confirmó la desconexión", self.client_id)
