# Contrato WebSocket — `/ws/telemetry`

Frontera entre el backend (Pipelines B y C) y el dashboard. Mensajes de texto
JSON con un campo `type`. Igual que con el contrato MQTT, cualquier cambio se
hace **primero aquí** y después en `backend/` y `frontend/`.

## Semántica

- **Tiempo real, no historial.** Lo que se pierde no se reenvía: un cliente lento recibe menos actualizaciones, nunca un atraso creciente. El historial completo está en la API REST.
- **Sin suscripción no hay datos.** Al conectar, el cliente no recibe nada hasta enviar `subscribe`.
- **Una suscripción activa por conexión.** Un `subscribe` nuevo **reemplaza** al anterior (en el servidor, `switchMap`).
- **Agregación.** El servidor no reenvía cada medición. Cada `intervalMs` envía, por dispositivo suscripto, **la última** medición de la ventana. Con un solo dispositivo es `sample()`; con varios, una ventana que conserva la última por dispositivo.

## Cliente → servidor

### `subscribe`
```json
{ "type": "subscribe", "devices": ["esp32-sim-00001", "esp32-real-001"], "intervalMs": 1000 }
{ "type": "subscribe", "devices": "*", "intervalMs": 2000 }
```
| Campo | Regla |
|---|---|
| `devices` | Lista de `deviceId` (1 a 500) o `"*"` (todos) |
| `intervalMs` | Entre 250 y 60000. Por defecto, 1000 |

Respuesta: `subscribed`, seguido de un `snapshot` con la última medición
conocida de cada dispositivo suscripto. Así la vista se llena sin esperar al
primer intervalo.

### `ping`
```json
{ "type": "ping" }
```
Respuesta: `pong`. Sirve para que el cliente detecte una conexión muerta.

## Servidor → cliente

| `type` | Cuándo | Campos |
|---|---|---|
| `subscribed` | Tras un `subscribe` válido | `devices` (`"*"` o lista), `intervalMs` |
| `snapshot` | Inmediatamente después de `subscribed` | `items`: lista de telemetría (formato abajo) |
| `telemetry` | Cada `intervalMs`, si hubo mediciones nuevas | `items`: última medición por dispositivo en la ventana |
| `status` | Cuando un dispositivo suscripto cambia de estado | `deviceId`, `status` (`ONLINE`/`OFFLINE`), `at` |
| `alert` | Cuando se dispara o se resuelve una alerta de un dispositivo suscripto | ver abajo |
| `pong` | Respuesta a `ping` | — |
| `error` | Mensaje del cliente inválido. **No cierra la conexión** | `message` |

### Telemetría (dentro de `items`)
```json
{ "deviceId": "esp32-sim-00001", "temperature": 24.8, "humidity": null,
  "measuredAt": "2026-10-25T12:30:00.123Z", "receivedAt": "2026-10-25T12:30:00.130Z",
  "sequence": 125, "source": "SIMULATED" }
```

### Alerta (Pipeline C)
```json
{ "type": "alert", "deviceId": "esp32-sim-00042", "kind": "HIGH_TEMPERATURE",
  "state": "RAISED", "temperature": 37.2, "threshold": 35.0, "at": "2026-10-25T12:30:05Z" }
```
| Campo | Valores |
|---|---|
| `kind` | `HIGH_TEMPERATURE` |
| `state` | `RAISED` (se disparó) o `CLEARED` (se resolvió) |

**Antirrebote:** una alerta se dispara con `raise-after` mediciones seguidas
por encima del umbral, y se resuelve recién cuando la temperatura baja de
`umbral − histéresis`. Mientras sigue fuera de rango no se repite. Las alertas
activas también están en `GET /api/alerts`.

## Ejemplo de sesión
```text
→ {"type":"subscribe","devices":["esp32-sim-00001"],"intervalMs":1000}
← {"type":"subscribed","devices":["esp32-sim-00001"],"intervalMs":1000}
← {"type":"snapshot","items":[{...}]}
← {"type":"telemetry","items":[{...}]}            cada segundo
← {"type":"alert","deviceId":"esp32-sim-00001","state":"RAISED",...}
→ {"type":"subscribe","devices":"*","intervalMs":2000}    reemplaza la anterior
← {"type":"subscribed","devices":"*","intervalMs":2000}
```
