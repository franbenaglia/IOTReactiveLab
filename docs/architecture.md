# Arquitectura

Estado: **inicial** (fase 0). Se actualiza al cerrar cada fase. Decisiones de
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

## Fronteras críticas (a detallar en fase 2-3)

1. **Callback Paho → Reactor.** Hilo de Paho ≠ event loop. El sink es acotado; si está lleno, el mensaje se cuenta como descartado y (según P4) se decide si se hace ACK manual o no.
2. **Reactor → R2DBC.** Concurrencia de `flatMap` ≤ tamaño del pool.
3. **Reactor → WebSocket.** Cada cliente con su propia política de backpressure (descartar/último) para que un cliente lento no frene al resto.

## Red (ADR-005)

| Desde | Hacia Mosquitto | Hacia PostgreSQL |
|---|---|---|
| Contenedor en compose | `mosquitto:1883` | `postgres:5432` |
| Proceso en el host | `localhost:1883` | `localhost:5432` |
| ESP32 en la LAN | `<IP LAN del host>:1883` | — |
