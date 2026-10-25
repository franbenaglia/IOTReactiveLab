#!/usr/bin/env python3
"""Borra mensajes retained de status (publicando payload vacío retained).

Las pruebas y ensayos dejan estados retained de dispositivos que ya no
existen; el backend los vería como dispositivos registrados.

    python tools/clear_retained.py --prefix lwtcheck-      # solo esos
    python tools/clear_retained.py --prefix esp32-sim-     # todos los simulados
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import paho.mqtt.client as mqtt

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from iotsim.config import resolve_password  # noqa: E402


def collect(host: str, port: int, password: str, prefix: str, wait: float) -> list[str]:
    topics: list[str] = []
    c = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2, protocol=mqtt.MQTTv5)
    c.username_pw_set("backend", password)
    c.on_connect = lambda cl, u, f, rc, p: cl.subscribe("iot/devices/+/status", qos=1)
    c.on_message = lambda cl, u, m: topics.append(m.topic) if m.retain and m.payload and m.topic.split("/")[2].startswith(prefix) else None
    c.connect(host, port, 30)
    c.loop_start()
    time.sleep(wait)
    c.loop_stop()
    c.disconnect()
    return sorted(set(topics))


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--prefix", required=True, help="prefijo de deviceId a limpiar")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=1883)
    p.add_argument("--wait", type=float, default=3.0, help="segundos recolectando retained")
    a = p.parse_args()
    env = HERE.parent.parent / ".env"
    topics = collect(a.host, a.port, resolve_password(None, "MQTT_BACKEND_PASSWORD", env), a.prefix, a.wait)
    if not topics:
        print("nada que limpiar")
        return 0
    c = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2, protocol=mqtt.MQTTv5)
    c.username_pw_set("simulator", resolve_password(None, "MQTT_SIMULATOR_PASSWORD", env))
    c.connect(a.host, a.port, 30)
    c.loop_start()
    infos = [c.publish(t, b"", qos=1, retain=True) for t in topics]
    for i in infos:
        i.wait_for_publish(10)
    c.loop_stop()
    c.disconnect()
    print(f"limpiados {len(topics)} status retained con prefijo '{a.prefix}'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
