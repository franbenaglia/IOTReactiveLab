# Contrato MQTT

Único contrato para dispositivos físicos y simulados. Origen: main.md §4.
Cualquier cambio se hace **primero aquí** y después en firmware, simulador y
backend (ver `CLAUDE.md` §3).

## Topics

| Topic | Dirección | QoS | Retained | Contenido |
|---|---|---|---|---|
| `iot/devices/{deviceId}/telemetry` | dispositivo → broker | 1 | no | Medición (JSON abajo) |
| `iot/devices/{deviceId}/status` | dispositivo → broker | 1 | **sí** | Estado `online` / `offline` |

Suscripciones del backend: `iot/devices/+/telemetry` y `iot/devices/+/status`.

`deviceId`: `[a-z0-9-]{3,64}`. Convención: `esp32-real-NNN` (físico),
`esp32-sim-NNNNN` (simulado). Debe coincidir con el segmento del topic; si no
coincide, el mensaje es inválido.

## Telemetría

```json
{
  "deviceId": "esp32-real-001",
  "temperature": 24.8,
  "humidity": 41.0,
  "timestamp": "2026-10-25T12:30:00Z",
  "sequence": 125,
  "source": "PHYSICAL"
}
```

| Campo | Tipo | Obligatorio | Regla |
|---|---|---|---|
| `deviceId` | string | sí | Igual al del topic |
| `temperature` | number | sí | °C. Rango de validación del backend: configurable (por defecto -40 a 125) |
| `humidity` | number \| null | no | % HR, 0-100. El DHT11 la envía; un sensor solo-temperatura manda `null` |
| `timestamp` | string | sí | ISO-8601 UTC con `Z`, momento de la **medición** |
| `sequence` | integer ≥ 0 | sí | Creciente por dispositivo |
| `source` | string | sí | `PHYSICAL` o `SIMULATED` |

## Estado

Payload JSON, retained:

```json
{ "deviceId": "esp32-real-001", "status": "online", "timestamp": "2026-10-25T12:30:00Z" }
```

- Al conectar, el dispositivo publica `online` (retained).
- **LWT** configurado en el CONNECT: mismo topic, `{"deviceId":"…","status":"offline"}`, QoS 1, retained. El LWT no lleva `timestamp` (se fija al conectar y quedaría viejo); el backend usa la hora de recepción.
- Desconexión ordenada: publicar `offline` antes de `DISCONNECT` (el broker no envía el LWT en ese caso).

## Garantías y deduplicación

- QoS 1 = **al menos una vez**: puede haber duplicados (reenvío tras PUBACK perdido). El backend deduplica por `(deviceId, sequence)` con restricción única en la base.
- **Abierto (P7):** `sequence` se reinicia si el dispositivo reinicia (ESP32 sin persistencia, simulador relanzado), y una restricción única pura descartaría mediciones legítimas. Opciones: persistir `sequence` en NVS en el firmware; incluir `measured_at` en la clave; o agregar un campo `bootId`. Decidir en fase 2-3 con un ADR.
- Mensajes con JSON malformado o campos inválidos se cuentan como rechazados (métrica) y no detienen el flujo.

## Ejemplos para pruebas manuales

```bash
docker compose exec mosquitto mosquitto_sub -u <usuario> -P <clave> -t 'iot/devices/+/telemetry' -v
docker compose exec mosquitto mosquitto_pub -u <usuario> -P <clave> -q 1 \
  -t iot/devices/esp32-sim-00001/telemetry \
  -m '{"deviceId":"esp32-sim-00001","temperature":24.1,"humidity":null,"timestamp":"2026-10-25T12:30:00Z","sequence":1,"source":"SIMULATED"}'
```
