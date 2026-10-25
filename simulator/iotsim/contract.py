"""Contrato MQTT (docs/mqtt-contract.md) del lado Python: topics, armado de
mensajes válidos e inválidos, y validación. La usan el simulador, el contador
de mensajes y las pruebas: un único lugar que refleja el contrato."""
from __future__ import annotations

import json
import random
import re
from datetime import datetime, timezone
from typing import Any

DEVICE_ID_RE = re.compile(r"^[a-z0-9-]{3,64}$")
TELEMETRY_TOPIC = "iot/devices/{device_id}/telemetry"
STATUS_TOPIC = "iot/devices/{device_id}/status"
TELEMETRY_FIELDS = ("deviceId", "temperature", "humidity", "timestamp", "sequence", "source")
SOURCES = ("PHYSICAL", "SIMULATED")

INVALID_KINDS = ("malformed_json", "missing_field", "out_of_range", "wrong_type", "id_mismatch")


def telemetry_topic(device_id: str) -> str:
    return TELEMETRY_TOPIC.format(device_id=device_id)


def status_topic(device_id: str) -> str:
    return STATUS_TOPIC.format(device_id=device_id)


def utc_now_iso() -> str:
    """ISO-8601 UTC con milisegundos y sufijo Z: 2026-10-25T12:30:00.123Z."""
    now = datetime.now(timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}Z"


def telemetry(device_id: str, temperature: float, humidity: float | None,
              sequence: int, timestamp: str | None = None, source: str = "SIMULATED") -> dict[str, Any]:
    return {
        "deviceId": device_id,
        "temperature": temperature,
        "humidity": humidity,
        "timestamp": timestamp or utc_now_iso(),
        "sequence": sequence,
        "source": source,
    }


def status(device_id: str, value: str, with_timestamp: bool = True) -> dict[str, Any]:
    msg: dict[str, Any] = {"deviceId": device_id, "status": value}
    if with_timestamp:
        msg["timestamp"] = utc_now_iso()
    return msg


def encode(msg: dict[str, Any]) -> bytes:
    return json.dumps(msg, separators=(",", ":")).encode()


def invalid_payload(valid: dict[str, Any], rng: random.Random, kind: str | None = None) -> tuple[str, bytes]:
    """Degrada un mensaje válido de una de varias formas. Devuelve (tipo, bytes)."""
    kind = kind or rng.choice(INVALID_KINDS)
    msg = dict(valid)
    if kind == "malformed_json":
        raw = encode(msg)
        return kind, raw[: max(1, len(raw) // 2)]
    if kind == "missing_field":
        msg.pop("temperature")
    elif kind == "out_of_range":
        msg["temperature"] = 999.9
    elif kind == "wrong_type":
        msg["temperature"] = "caliente"
    elif kind == "id_mismatch":
        msg["deviceId"] = msg["deviceId"] + "-x"
    else:
        raise ValueError(f"tipo de mensaje inválido desconocido: {kind}")
    return kind, encode(msg)


def validate_telemetry(topic: str, payload: bytes, temp_range: tuple[float, float] = (-40.0, 125.0)) -> list[str]:
    """Devuelve la lista de violaciones al contrato (vacía si es válido)."""
    parts = topic.split("/")
    if len(parts) != 4 or parts[0] != "iot" or parts[1] != "devices" or parts[3] != "telemetry":
        return [f"topic inválido: {topic}"]
    topic_id = parts[2]
    try:
        msg = json.loads(payload)
    except (ValueError, UnicodeDecodeError):
        return ["JSON malformado"]
    if not isinstance(msg, dict):
        return ["el payload no es un objeto JSON"]
    errors: list[str] = []
    for f in TELEMETRY_FIELDS:
        if f not in msg and f != "humidity":
            errors.append(f"falta {f}")
    dev = msg.get("deviceId")
    if isinstance(dev, str):
        if not DEVICE_ID_RE.match(dev):
            errors.append("deviceId con formato inválido")
        if dev != topic_id:
            errors.append("deviceId distinto al del topic")
    elif "deviceId" in msg:
        errors.append("deviceId no es string")
    t = msg.get("temperature")
    if "temperature" in msg:
        if not isinstance(t, (int, float)) or isinstance(t, bool):
            errors.append("temperature no es numérico")
        elif not temp_range[0] <= t <= temp_range[1]:
            errors.append("temperature fuera de rango")
    h = msg.get("humidity")
    if h is not None and (not isinstance(h, (int, float)) or isinstance(h, bool) or not 0 <= h <= 100):
        errors.append("humidity inválida")
    seq = msg.get("sequence")
    if "sequence" in msg and (not isinstance(seq, int) or isinstance(seq, bool) or seq < 0):
        errors.append("sequence inválido")
    ts = msg.get("timestamp")
    if "timestamp" in msg:
        if not isinstance(ts, str) or not ts.endswith("Z"):
            errors.append("timestamp no es ISO-8601 UTC con Z")
        else:
            try:
                datetime.fromisoformat(ts[:-1] + "+00:00")
            except ValueError:
                errors.append("timestamp no parseable")
    if "source" in msg and msg.get("source") not in SOURCES:
        errors.append("source inválido")
    return errors
