# Progreso

Documento vivo. El hook `SessionStart` muestra la sección *Estado actual* al
abrir cada sesión: mantenerla corta y al día.

## Estado actual

- **Fase:** 4 — WebSocket + dashboard → **completada y validada** (2026-10-25). Próxima: **Fase 5** (ESP32 físico).
- **Última tarea:** `/ws/telemetry` (Pipeline B), alertas (Pipeline C), endpoint del monitor y dashboard React con 4 vistas, verificado en navegador con el simulador. 85 pruebas de backend + 17 de frontend.
- **Próximo paso:** fase 5, `firmware/esp32-temperature/`, según `hardware-esp32.md` (256dpi/MQTT, NTP, LWT, `TemperatureSensor` + DHT11 + simulado). Necesita al usuario: placa conectada en `/dev/ttyUSB0`, credenciales WiFi en `secrets.ini` y la contraseña de `esp32-real-001` (está en `.env`).
- **En curso / a medias:** nada.

## Pendientes / bloqueos

| # | Pendiente | Bloquea | Notas |
|---|---|---|---|
| P6 | Mosquitto CLI no instalado en el host | — | Usar `docker compose exec mosquitto mosquitto_sub ...` (ver README) |
| P9 | Backend, frontend y perfil del simulador en Docker Compose | Fase 7 | ADR-015 |
| P11 | Node 20 ya no tiene soporte (desde abril 2026) | — | El frontend usa versiones compatibles con Node 20 (ADR-014). Recomendado: Node 22/24 LTS y subir react-router 8, Vitest 5 y jsdom 30 |
| P12 | `GET /api/devices` devuelve todo (5.350 → 1,4 MB) | — | Paginar en el servidor si crece la cantidad de dispositivos |

## Entorno

Detectado el 2026-10-25 en la notebook de desarrollo (Linux, Ubuntu 22.04):

| Herramienta | Versión | Estado |
|---|---|---|
| Java | OpenJDK 17.0.19 | OK — se usa 17 (ADR-004) |
| Maven | 3.8.6 | OK (Spring Boot 4.x pide ≥ 3.6.3) |
| Docker / Compose | 29.8.0 / v5.5.1 | OK |
| Python | 3.10.12 | OK |
| Node / npm | 20.20.2 / 10.8.2 | OK |
| PlatformIO Core | 6.2.0 | OK (`~/.platformio/penv/bin/pio`) |
| Git | 2.34.1 | OK |
| IP LAN | 10.44.60.77 | **Cambia** al cambiar de red |

Versiones de librerías consultadas en Maven Central / PyPI el 2026-10-25:
Spring Boot 4.1.2 (última estable), Paho Java 1.2.5, r2dbc-postgresql
1.1.3.RELEASE, Flyway 13.11.1, Testcontainers 2.0.5, paho-mqtt (Python) 2.1.0.
Revalidar compatibilidad al comenzar cada fase (ADR-004).

## Registro

### 2026-10-25 — Fase 4: WebSocket, alertas y dashboard
- Contrato `docs/websocket-contract.md` escrito antes del código.
- Backend: `TelemetryWebSocketHandler` (`switchMap`, `sample`, `window` + `collectMap`, `onBackpressureLatest`, prefetch 1), `AlertService` con antirrebote e histéresis, `LatestTelemetryCache`, `/api/metrics/monitor`, métricas de Netty, frecuencia estimada por dispositivo.
- Hallazgo: el **prefetch** de `merge` retenía lotes viejos de un cliente lento; con prefetch 1 queda a lo sumo uno (`reactor-concepts.md`).
- Frontend (ADR-014): React 19 + Vite 8 + Mantine 9 + Recharts + TanStack Query, versiones compatibles con Node 20 (P11). Páginas con carga diferida (bundle inicial de 117 kB comprimido).
- Verificación en navegador con Playwright: las cuatro vistas con datos reales del simulador, alertas en vivo, tabla con suscripción filtrada.

### 2026-10-25 — Fase 3: persistencia reactiva
- Spring Data R2DBC (`DatabaseClient`) + r2dbc-postgresql 1.1.3 + pool; Flyway 12.4 por JDBC al arrancar. ADR-013.
- Esquema V1 con la clave única de ADR-011 como índice de consulta. FK `telemetry → devices`, con alta única en memoria y recuperación ante una violación de FK.
- `status` persistido con reintentos; `last_seen` por lotes (`Flux.interval` + `concatMap`).
- `timeout()` por intento (base colgada) antes de `retryWhen`.
- API REST completa (main.md §10) con ProblemDetail para 400/404.
- Hallazgos:
  - Arranque con atraso + pool frío → esperas de conexión > 3 s y `onErrorDropped`. Se agregaron precalentamiento del pool y un hook que convierte los errores descartados en métrica.
  - **`localhost` en R2DBC lento al abrir muchas conexiones; `127.0.0.1` lo resuelve** (vaciado de 10,6 s → 2,05 s). Detalle en `testing.md`.
  - La sesión MQTT expiró entre fases (> 1 h): al volver, el broker reenvió los status retained y los 5.000 `esp32-sim-*` de la fase 1 quedaron registrados como dispositivos OFFLINE. Es lo esperado (P8 cerrado, ADR-013 punto 10).
- Validación real: 100 disp × 90 s con PostgreSQL detenido 20 s → 8.997/8.997 filas.

### 2026-10-25 — Fase 2: backend MQTT + Reactor
- Spring Boot 4.1.2 (Spring Framework 7, Reactor 2025.0, Jackson 3.1, JUnit 6, Testcontainers 2.0.5), Java 17. `backend/.mvn/settings.xml` → Maven Central (ADR-012); todas las dependencias se resolvieron sin la intranet.
- **ADR-009 validado** con `PahoV5BehaviorIT`. Hallazgos: DUP=false en reentregas; hace falta `retainHandling=1`; "listo" recién con el SUBACK.
- Problemas encontrados y resueltos:
  - Testcontainers: Mosquitto no vacía stdout sin TTY, así que esperar por log no funciona; se espera por el puerto.
  - Gauge de escrituras en curso en `concurrency + 1` con `doFinally`; se pasó a `doOnTerminate` + `doOnCancel` (documentado en `reactor-concepts.md`).
  - `Queues.get(n)` tiene un mínimo de 8.
  - Datos de prueba con hora fija rechazados como `FUTURE_TIMESTAMP` contra el reloj real: el validador funcionaba bien, el dato de prueba estaba mal.
  - Conflicto de nombre `MqttProperties` (propia y de Paho).
- Validación real: 100 dispositivos × 60 s con 5 % de inválidos; todo cuadra (ver `testing.md`). Sesión persistente: 1.998 mensajes publicados con el backend caído, recibidos al volver.

### 2026-10-25 — Decisiones previas a la fase 2
- Usuario: backend con **Java 17** (ADR-004).
- Verificado con `javap` sobre los jars de Paho 1.2.5: v3 y v5 tienen confirmación manual; solo v5 permite que el cliente declare `ReceiveMaximum`.
- Verificado en la documentación de Mosquitto 2.0.22: `max_inflight_messages` y `max_queued_messages` son por cliente, y más allá de la cola se descarta.
- Detectado: `~/.m2/settings.xml` redirige todo a la intranet de SENASA, así que Maven no descarga.
- Aprobados por el usuario: ADR-009 (MQTT 5), ADR-010 (confirmar tras guardar, `AckTracker` en orden, sesión persistente), ADR-011 (clave `(device_id, measured_at, sequence)`), ADR-012 (settings Maven del proyecto).

### 2026-10-25 — Fase 1: infraestructura y simulador
- Decisiones del usuario: firmware con `256dpi/MQTT` (QoS 1) y NTP → ADR-003 resuelto. Sobre Java 21: Spring Boot 4.1 corre con 17, pero main.md pide 21+; el usuario eligió quedarse con Java 17 (ADR-004, P1 cerrado).
- `docker-compose.yml`: Mosquitto 2.0.22 (auth + ACL, contraseñas generadas al arrancar desde `.env`, ADR-007) y PostgreSQL 17.11 (solo localhost). Healthchecks OK. `.env` local generado con contraseñas aleatorias.
- Problema: Mosquitto avisaba que el ACL montado era legible por todos y el entrypoint de la imagen hacía `chown -R` sobre el montaje de solo lectura. Solución: montar en `/iotlab/mosquitto` y copiar a `/mosquitto/run` con `umask 077`.
- Simulador (`simulator/`): paho-mqtt integrado al event loop de asyncio (ADR-008), MQTT 5, modos normal/random/burst/load, flags de desconexión/inválidos/picos/humedad, modos de conexión A y B, estadísticas pedidos vs confirmados (PUBACK) con latencia de ACK.
- Herramientas: `tools/count_messages.py` (receptor: valida contrato, duplicados), `tools/clear_retained.py`, `scripts/load/run-scenario.sh`.
- 39 pruebas pytest. Carga 10/100/1.000 A y B sin errores; 5.000/A satura el simulador (~3.700 msg/s), 5.000/B OK (~4.940 msg/s).

### 2026-10-25 — Fase 0: harness
- Leída la especificación `main.md`. Repositorio vacío salvo ese archivo.
- Creados `CLAUDE.md`, `.claude/agents/*` (6 agentes), `.claude/hooks/session-start.sh`, `.claude/settings.json`, `docs/*`.
- Analizado el proyecto de referencia `../esp32-proj/esp32-screen-dht11-spi` (ESP32 DevKit V1 + DHT11, probado): hardware, toolchain y lecciones volcadas en `hardware-esp32.md` y ADR-002/003.
- Detectados P1-P6.
- `git init` + commit inicial.
