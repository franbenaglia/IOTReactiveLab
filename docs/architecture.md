# Arquitectura

Estado: fase 4 completada (infraestructura, simulador, backend con persistencia, WebSocket, alertas y dashboard React). Se actualiza al cerrar cada fase. Decisiones de
fondo en `decisions.md`.

## Vista de componentes

```text
 ESP32 DevKit V1 + DHT11 ─┐                         ┌──► PostgreSQL 17
 (firmware, LAN)          │  MQTT QoS1              │     (R2DBC; Flyway/JDBC al arranque)
                          ├────────► Mosquitto ─────┤
 Simulador Python ────────┘  1883 (auth)            │ Paho async
 (asyncio, N dispositivos)                          ▼
                                     Backend Spring Boot WebFlux
                                     MqttSubscriber → MqttReactorAdapter (Flux)
                                       ├─ Pipeline A: validar → normalizar → dedup → flatMap(save)
                                       ├─ Pipeline B: sample → WebSocket
                                       └─ Pipeline C: umbral → alerta → WebSocket
                                     REST /api/**   WS /ws/telemetry   /actuator/**
                                                    │
                                                    ▼
                                          Dashboard React (Vite)
```

## Componentes y responsabilidades

| Componente | Carpeta | Fase | Responsabilidad |
|---|---|---|---|
| Firmware | `firmware/esp32-temperature/` | 5 | Leer sensor (adaptador), publicar telemetría y estado, reconectar, logs |
| Simulador | `simulator/` | 1 | N dispositivos virtuales, modos de comportamiento y de conexión, estadísticas |
| Broker | `infra/mosquitto/` | 1 | Autenticación, ACL, QoS, retained, LWT |
| Backend | `backend/` | 2-4 | Pipelines Reactor, persistencia, REST, WS, métricas |
| Dashboard | `frontend/` | 4 | Vistas de monitoreo y Reactive Monitor |
| Base | servicio `postgres` | 1/3 | `devices`, `telemetry` |

## Paquetes del backend (ADR-001)

| Paquete | Contiene |
|---|---|
| `config` | Propiedades tipadas (`@ConfigurationProperties`), beans de infraestructura |
| `mqtt` | `MqttSubscriber` (Paho, ciclo de vida de la conexión) |
| `reactive` | `MqttReactorAdapter` (callback → `Sinks.Many` → `Flux`) |
| `domain` | `Telemetry`, `Device`, `DeviceStatus`, `Alert` (records inmutables) |
| `processing` | `TelemetryProcessor`, validación, normalización, dedup, alertas |
| `persistence` | Repositorios R2DBC, entidades de tabla |
| `websocket` | `TelemetryWebSocketHandler`, contrato de suscripción |
| `api` | Controladores REST (devuelven `Mono`/`Flux`) |
| `metrics` | Binders Micrometer, overview de métricas |
| `exception` | Errores de dominio y manejo global |

## Fronteras críticas (ADR-009, ADR-010)

```text
Mosquitto ──(ReceiveMaximum N sin confirmar)──► Paho v5, confirmación manual
                                                     │ hilo de callback de Paho
                                                     ▼
                              Sinks.many().unicast() (cola N: no desborda por construcción)
                                                     │
                     Pipeline A: validar → normalizar → flatMap(INSERT … ON CONFLICT, c ≤ pool R2DBC)
                                                     │ estado final por mensaje
                                                     ▼
                                    AckTracker → PUBACK en orden de llegada
              y en paralelo:  Sinks.many().multicast().directBestEffort() → Pipelines B y C
```

1. **Callback Paho → Reactor.** El hilo de Paho no es un event loop: el callback solo emite al sink y vuelve. Nada se confirma al recibirlo.
2. **Reactor → R2DBC.** Concurrencia de `flatMap` ≤ tamaño del pool. Una falla de la base se reintenta con backoff sin confirmar: se llenan las N ranuras y el broker deja de entregar (backpressure hasta el broker, que encola hasta `max_queued_messages`).
3. **PUBACK en orden.** El `AckTracker` libera confirmaciones solo cuando se completaron todas las anteriores (MQTT §4.6).
4. **Reactor → WebSocket.** Sink multicast best-effort: un cliente lento pierde actualizaciones y no frena la persistencia.

## Red (ADR-005)

| Desde | Hacia Mosquitto | Hacia PostgreSQL |
|---|---|---|
| Contenedor en compose | `mosquitto:1883` | `postgres:5432` |
| Proceso en el host | `localhost:1883` | `localhost:5432` |
| ESP32 en la LAN | `<IP LAN del host>:1883` | — |

## Simulador (fase 1)

| Módulo | Responsabilidad |
|---|---|
| `iotsim/config.py` | Dataclasses de configuración, YAML + CLI, validación, contraseña |
| `iotsim/contract.py` | Contrato MQTT en Python: topics, mensajes, inválidos, validación |
| `iotsim/model.py` | Evolución de temperatura/humedad (paseo con reversión) |
| `iotsim/device.py` | Dispositivo virtual: ID, secuencia, payloads |
| `iotsim/timing.py` | Intervalos por modo |
| `iotsim/connection.py` | paho ↔ asyncio, PUBACK, reconexión con backoff, LWT |
| `iotsim/runner.py` | Armado A/B, una tarea por dispositivo, apagado ordenado |
| `iotsim/stats.py` | Pedidos vs confirmados, latencia de ACK |

Un proceso, un hilo de event loop para todas las conexiones; `connect()` en
executor. Capacidad medida en la notebook de referencia: ~5.000 msg/s con
conexiones compartidas; ~3.700 msg/s con 5.000 conexiones (límite del
simulador, ver `testing.md`).

## Mosquitto (fase 1)

`infra/mosquitto/config/` se monta de solo lectura en `/iotlab/mosquitto`;
`entrypoint.sh` genera `passwd` desde `.env` y copia `acl` y
`mosquitto.conf` a `/mosquitto/run` con permisos 0700. Límites explícitos:
`max_inflight_messages 100`, `max_queued_messages 10000`.

## Backend (fase 2)

| Clase | Paquete | Responsabilidad |
|---|---|---|
| `MqttSubscriber` | `mqtt` | Paho v5: conexión, sesión persistente, `ReceiveMaximum`, SUBSCRIBE con `retainHandling=1`, reconexión; implementa `Acknowledger` |
| `AckTracker` | `mqtt` | PUBACK en orden de llegada; se reinicia al perder la conexión |
| `MqttHealthIndicator` | `mqtt` | `/actuator/health` → `mqtt` UP solo con SUBACK |
| `MqttReactorAdapter` | `reactive` | `Sinks.many().unicast()` con cola acotada → `Flux<InboundMessage>` |
| `RealtimeTelemetryBus` | `reactive` | `Sinks.many().multicast().directBestEffort()` para B/C |
| `TelemetryParser` | `processing` | Contrato: deserializar, validar, normalizar (nunca lanza) |
| `TelemetryProcessor` | `processing` | Pipeline A (assembly) |
| `PipelineRunner` | `processing` | Único `subscribe()` del pipeline; ciclo de vida |
| `TelemetryStore` / `NoOpTelemetryStore` | `processing` | Puerto de persistencia; la fase 3 trae la implementación R2DBC |
| `SequenceTracker` | `processing` | Detecta reinicios de dispositivo (`sequence` que retrocede) |
| `PipelineMetrics` | `metrics` | Micrometer, etiquetas acotadas |

**Ciclo de vida** (`SmartLifecycle`): `PipelineRunner` (fase 0) arranca antes
y se detiene después que `MqttSubscriber` (fase 100). Al arrancar, el
pipeline ya escucha cuando llega el primer mensaje. Al detener, MQTT se
desconecta primero, se vacía lo que está en curso (plazo `shutdown-timeout`) y
lo no confirmado lo reentrega el broker en el próximo arranque.

**Métricas** (`/actuator/prometheus`, prefijo `iotlab_`): `mqtt_received{type}`,
`mqtt_acks{result}`, `mqtt_acks_pending`, `mqtt_sink_queued`, `mqtt_connected`,
`mqtt_connections{event}`, `mqtt_dropped{reason}`, `telemetry_valid`,
`telemetry_invalid{reason}`, `telemetry_stored{result}`,
`telemetry_store_retries`, `telemetry_store_inflight`,
`telemetry_sequence_resets`, `status_received{state}` y `pipeline_latency`
(p50/p95/p99).

## Persistencia y API (fase 3, ADR-013)

| Clase | Paquete | Responsabilidad |
|---|---|---|
| `R2dbcTelemetryStore` | `persistence` | `TelemetryStore` real: alta del dispositivo + `INSERT … ON CONFLICT`; status; clasificación de errores por SQLSTATE |
| `TelemetryRepository`, `DeviceRepository` | `persistence` | SQL explícito con `DatabaseClient` |
| `DeviceRegistry` + `DeviceRegistryFlusher` | `persistence` | Alta única por dispositivo (FK), `last_seen` por lotes cada 5 s |
| `R2dbcPoolWarmup` | `persistence` | Conexiones listas antes de MQTT |
| `DeviceController`, `MetricsController` | `api` | REST: `/api/devices`, `/{id}`, `/{id}/latest`, `/{id}/history`, `/api/metrics/overview` |
| `ApiExceptionHandler` | `exception` | 400/404 como ProblemDetail |
| `ReactorHooksConfig` | `config` | `Hooks.onErrorDropped` → métrica |

**Esquema** (`db/migration/V1__devices_and_telemetry.sql`, Flyway por JDBC
al arrancar): `devices(id, device_id UNIQUE, source, status,
status_changed_at, last_seen, created_at)` y `telemetry(id, device_id FK,
temperature, humidity, measured_at, received_at, sequence)` con `UNIQUE
(device_id, measured_at, sequence)`, que sirve a la vez para deduplicar y para
las consultas.

**Orden de arranque** (`SmartLifecycle`, de menor a mayor fase):
`R2dbcPoolWarmup` (-200) → `DeviceRegistryFlusher` (-100) → `PipelineRunner`
(0) → `MqttSubscriber` (100). La parada es en orden inverso: MQTT primero,
el flush final de `last_seen` al último.

## Tiempo real y dashboard (fase 4, ADR-014)

```text
Pipeline A ─doOnNext─► RealtimeBus (multicast best-effort: telemetría + estados)
                          ├─► LatestTelemetryCache      (snapshot, promedio)
                          ├─► AlertService (Pipeline C) ─► alertas (multicast) ─┐
                          └─► por cada cliente WS: filter → sample | window+collectMap
                                 → onBackpressureLatest ─► merge(estados, alertas) ─► session.send
```

| Clase | Paquete | Responsabilidad |
|---|---|---|
| `TelemetryWebSocketHandler` | `websocket` | `/ws/telemetry` (docs/websocket-contract.md) |
| `WsCodec`, `ClientCommand`, `WsMessages` | `websocket` | Contrato JSON |
| `RealtimeBus` | `reactive` | Telemetría y estados para B y C |
| `LatestTelemetryCache` | `reactive` | Última medición por dispositivo |
| `AlertDetector`, `AlertService`, `AlertController` | `alerts` | Pipeline C, `GET /api/alerts` |
| `MonitorController` | `api` | `GET /api/metrics/monitor` |
| `NettyMetricsConfig` | `config` | Métricas de Reactor Netty con URIs agrupadas |

**Frontend** (`frontend/`): `src/api` (tipos de los contratos, cliente REST,
`useMonitor`), `src/ws` (`TelemetrySocket`: reconexión, heartbeat,
resuscripción), `src/pages` (Vista general, Dispositivos, Individual, Reactive
Monitor), `src/components` (diagrama del pipeline, gráficos con ventana
acotada). En desarrollo, Vite reenvía `/api` y `/ws` al backend.
