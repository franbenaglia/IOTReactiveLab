#!/usr/bin/env python3
"""Se suscribe a iot/devices/+/telemetry y cuenta lo que llega AL RECEPTOR.

Es la contraparte del simulador: él dice cuántos mensajes confirmó el broker;
esto dice cuántos recibió un suscriptor, si cumplen el contrato, cuántos
dispositivos distintos se vieron y si hubo duplicados (deviceId, sequence).

    python tools/count_messages.py --duration 70 --summary-json /tmp/rx.json
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from collections import Counter
from pathlib import Path

import paho.mqtt.client as mqtt

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from iotsim.config import resolve_password  # noqa: E402
from iotsim.contract import validate_telemetry  # noqa: E402


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=1883)
    p.add_argument("--username", default="backend")
    p.add_argument("--password")
    p.add_argument("--topic", default="iot/devices/+/telemetry")
    p.add_argument("--duration", type=float, default=60)
    p.add_argument("--interval", type=float, default=5)
    p.add_argument("--prefix", default="", help="contar solo deviceIds con este prefijo")
    p.add_argument("--summary-json")
    a = p.parse_args()
    password = resolve_password(a.password, "MQTT_BACKEND_PASSWORD", HERE.parent.parent / ".env")

    lock = threading.Lock()
    total = valid = 0
    invalid = Counter()
    seen: set[tuple[str, int]] = set()
    duplicates = 0
    devices: set[str] = set()
    ready = threading.Event()

    def on_connect(c, u, flags, rc, props):
        if rc.is_failure:
            print(f"conexión rechazada: {rc}", file=sys.stderr)
            return
        c.subscribe(a.topic, qos=1)

    def on_subscribe(c, u, mid, rcs, props):
        ready.set()

    def on_message(c, u, msg):
        nonlocal total, valid, duplicates
        if not msg.topic.split("/")[2].startswith(a.prefix):
            return  # tráfico ajeno a esta medición
        errs = validate_telemetry(msg.topic, msg.payload)
        with lock:
            total += 1
            devices.add(msg.topic.split("/")[2])
            if errs:
                invalid[errs[0]] += 1
                return
            valid += 1
            body = json.loads(msg.payload)
            key = (body["deviceId"], body["sequence"])
            if key in seen:
                duplicates += 1
            else:
                seen.add(key)

    c = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                    client_id=f"count-{int(time.time())}", protocol=mqtt.MQTTv5)
    c.username_pw_set(a.username, password)
    c.on_connect, c.on_subscribe, c.on_message = on_connect, on_subscribe, on_message
    c.connect(a.host, a.port, 30)
    c.loop_start()
    if not ready.wait(10):
        print("no se pudo suscribir", file=sys.stderr)
        return 2
    print(f"suscripto a {a.topic} por {a.duration:.0f}s", flush=True)
    start = last = time.monotonic()
    last_total = 0
    while (now := time.monotonic()) - start < a.duration:
        time.sleep(min(a.interval, a.duration - (now - start)))
        with lock:
            t, v, d, nd = total, valid, duplicates, len(devices)
        now = time.monotonic()
        print(f"[{now - start:6.1f}s] recibidos={t} válidos={v} inválidos={t - v} duplicados={d} "
              f"dispositivos={nd} {(t - last_total) / (now - last):8.1f} msg/s", flush=True)
        last, last_total = now, t
    c.loop_stop()
    c.disconnect()
    summary = {"received": total, "valid": valid, "invalid": total - valid, "invalid_by_reason": dict(invalid),
               "duplicates": duplicates, "devices_seen": len(devices),
               "received_per_second": round(total / a.duration, 1)}
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if a.summary_json:
        Path(a.summary_json).write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
