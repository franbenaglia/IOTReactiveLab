#!/usr/bin/env python3
"""Simulador de dispositivos ESP32 — IoT Reactive Lab.

    python simulator.py --devices 10
    python simulator.py --devices 100 --interval 2
    python simulator.py --devices 1000 --interval 1
    python simulator.py --devices 100 --duration 300
    python simulator.py --devices 5000 --connection-mode B --mode load --rate 2000

La contraseña MQTT se toma de --password, de MQTT_SIMULATOR_PASSWORD o del
.env de la raíz del repo. Ver README.md.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from iotsim.config import load_config, resolve_password
from iotsim.runner import Simulation

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Simulador de múltiples ESP32 sobre MQTT")
    p.add_argument("--config", default=str(HERE / "config.yaml"), help="archivo YAML (por defecto config.yaml)")
    g = p.add_argument_group("broker")
    g.add_argument("--host")
    g.add_argument("--port", type=int)
    g.add_argument("--username")
    g.add_argument("--password")
    g.add_argument("--qos", type=int, choices=(0, 1))
    g = p.add_argument_group("simulación")
    g.add_argument("--devices", type=int)
    g.add_argument("--interval", type=float, help="segundos entre mediciones por dispositivo")
    g.add_argument("--duration", type=float, help="segundos; 0 = hasta Ctrl+C")
    g.add_argument("--mode", choices=("normal", "random", "burst", "load"))
    g.add_argument("--rate", type=float, help="modo load: mensajes/s totales")
    g.add_argument("--id-prefix")
    g.add_argument("--id-offset", type=int)
    g = p.add_argument_group("conexiones")
    g.add_argument("--connection-mode", choices=("A", "B"))
    g.add_argument("--devices-per-connection", type=int)
    g = p.add_argument_group("comportamiento")
    g.add_argument("--disconnects", action="store_true", help="desconexiones aleatorias")
    g.add_argument("--invalid-pct", type=float, help="activa mensajes inválidos con este %%")
    g.add_argument("--spikes", action="store_true", help="picos de temperatura")
    g.add_argument("--humidity", action="store_true", help="incluir humedad simulada")
    g = p.add_argument_group("salida")
    g.add_argument("--stats-interval", type=float)
    g.add_argument("--summary-json", help="escribir el resumen final en este archivo")
    g.add_argument("--seed", type=int, help="semilla para resultados reproducibles")
    g.add_argument("-v", "--verbose", action="store_true")
    return p.parse_args(argv)


def overrides_from(a: argparse.Namespace) -> dict:
    def pick(**kw):
        return {k: v for k, v in kw.items() if v is not None}
    behavior = {}
    if a.disconnects:
        behavior["random_disconnects"] = True
    if a.invalid_pct is not None:
        behavior.update(invalid_messages=a.invalid_pct > 0, invalid_percentage=a.invalid_pct)
    if a.spikes:
        behavior["temperature_spikes"] = True
    return {
        "broker": pick(host=a.host, port=a.port, username=a.username, qos=a.qos),
        "simulation": pick(devices=a.devices, interval_seconds=a.interval, duration_seconds=a.duration,
                           mode=a.mode, load_rate=a.rate, id_prefix=a.id_prefix, id_offset=a.id_offset),
        "connection": pick(mode=a.connection_mode, devices_per_connection=a.devices_per_connection),
        "behavior": behavior,
        "humidity": {"enabled": True} if a.humidity else {},
        "stats": pick(interval_seconds=a.stats_interval),
        "seed": a.seed,
    }


def main(argv: list[str] | None = None) -> int:
    a = parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        cfg = load_config(a.config, overrides_from(a))
    except (OSError, ValueError) as exc:
        print(f"Error de configuración: {exc}", file=sys.stderr)
        return 2
    cfg.broker.password = resolve_password(a.password, "MQTT_SIMULATOR_PASSWORD", REPO_ROOT / ".env")
    if cfg.broker.username and not cfg.broker.password:
        print("Falta la contraseña MQTT: --password, MQTT_SIMULATOR_PASSWORD o .env", file=sys.stderr)
        return 2

    stats = asyncio.run(Simulation(cfg).run())
    summary = stats.summary()
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if a.summary_json:
        Path(a.summary_json).write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return 0 if stats.connect_failures == 0 and stats.telemetry_unconfirmed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
