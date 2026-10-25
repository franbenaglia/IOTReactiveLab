# Plan de desarrollo

Fases de main.md §19, con tareas y **criterios de aceptación** verificables. Una
fase se cierra solo cuando todos sus criterios tienen evidencia en
`testing.md`. Estado: ☐ pendiente · ◐ en curso · ☑ hecha.

## Fase 0 — Harness ☑
- ☑ CLAUDE.md, agentes, docs de seguimiento, hook de inicio.
- ☑ Hardware ESP32 definido (ADR-002/003). Versiones consultadas (ADR-004).

## Fase 1 — Infraestructura y simulador ☐

| # | Tarea | Agente |
|---|---|---|
| 1.1 | `docker-compose.yml`: Mosquitto 2.x (auth, `allow_anonymous false`, listener 1883, persistencia, healthcheck) y PostgreSQL 17 (volumen, healthcheck). `.env.example`. | orquestador |
| 1.2 | `infra/mosquitto/`: `mosquitto.conf`, script para generar `passwd` (fuera del repo), ACL básica (dispositivos solo publican en `iot/devices/{su id}/#`; backend lee `iot/devices/#`). | orquestador / architect |
| 1.3 | `simulator/`: estructura, `config.yaml`, CLI (`--devices --interval --duration --mode --connection-mode`), modelo de temperatura (random walk acotado). | iot-developer |
| 1.4 | Modos A (conexión por dispositivo) y B (conexiones compartidas). | iot-developer |
| 1.5 | Modos normal, aleatorio, burst, desconexión, datos inválidos, carga. | iot-developer |
| 1.6 | Estadísticas en consola: dispositivos, conexiones activas, publicados solicitados vs confirmados (PUBACK), msg/s, errores, reconexiones, tiempo. | iot-developer |
| 1.7 | Pruebas pytest (generación, IDs únicos, intervalos, reconexión, publicación contra Mosquitto real). | qa-reviewer |
| 1.8 | Validación 10 / 100 / 1.000 dispositivos y documentación del procedimiento. | qa-reviewer |

**Criterios de aceptación**
1. `docker compose up -d mosquitto postgres` levanta ambos `healthy`.
2. Una conexión sin credenciales es rechazada por el broker.
3. `python simulator.py --devices 10` publica; `mosquitto_sub` recibe mensajes que cumplen `mqtt-contract.md`.
4. 10, 100 y 1.000 dispositivos (modo A y modo B) publican durante ≥ 60 s; publicados confirmados ≈ solicitados; resultados y condiciones en `testing.md`.
5. Status `online` retained al conectar; LWT `offline` al matar el proceso abruptamente.
6. `pytest` pasa.

## Fase 2 — Backend MQTT + Reactor ☐
Requisito previo: JDK 21 (P1), ADR de Paho v3/v5 (P5), ADR de política de entrega (P4).

- Proyecto Spring Boot 4.1.x (WebFlux, Actuator, Micrometer), paquetes según ADR-001.
- `MqttSubscriber` (Paho async, reconexión, cierre ordenado).
- `MqttReactorAdapter` con `Sinks.Many` acotado; manejo de `EmitResult`, buffer lleno, sin suscriptores, error de deserialización.
- Validación y normalización; métricas básicas (recibidos, válidos, inválidos, descartados).
- Pruebas StepVerifier.

**Criterios:** el backend consume del simulador (100 dispositivos) sin errores; métricas visibles en `/actuator/metrics`; mensajes inválidos contados y el flujo sigue vivo; pruebas pasan.

## Fase 3 — Persistencia reactiva ☐
- Flyway (JDBC al arranque, fuera de event loops) + R2DBC para la app.
- Tablas `devices`, `telemetry`, índices, restricción de duplicados (P7).
- Pipeline A con `flatMap` de concurrencia configurable, ligada al pool R2DBC; errores por elemento; reintentos.
- REST: `/api/devices`, `/{id}`, `/{id}/latest`, `/{id}/history` (paginación + rango).

**Criterios:** mediciones persistidas y consultables; duplicados rechazados por la base y contados; caída breve de PostgreSQL no mata el pipeline; pruebas Testcontainers pasan.

## Fase 4 — WebSocket + React ☐
- `/ws/telemetry` con contrato JSON de suscripción (todos / por `deviceId`), `sample()` configurable, clientes lentos.
- Pipeline C de alertas con antirrebote.
- Frontend: vistas general, dispositivos, individual, Reactive Monitor, pipeline.

**Criterios:** dashboard en tiempo real con 100 simulados; filtros por dispositivo; métricas reales; pruebas Vitest pasan.

## Fase 5 — ESP32 físico ☐
Requisito previo: ADR de librería MQTT (P2) y NTP (P3).

- `firmware/esp32-temperature/` según `hardware-esp32.md`: `TemperatureSensor` + `Dht11Sensor` + `SimulatedTemperatureSensor`, WiFi/MQTT con backoff, LWT, NTP, `sequence`, logs.

**Criterios:** `pio run` compila todos los entornos; la placa publica según contrato; aparece en el mismo dashboard que los simulados; desconectar WiFi → `offline` (LWT) → reconecta solo.

## Fase 6 — Laboratorio Reactor ☐
Experimentos 1-5 de main.md §13, configurables sin recompilar. Ver `experiments.md`.

## Fase 7 — Estabilidad y documentación ☐
Integración completa, análisis de rendimiento, recursos, correcciones, informe final.
