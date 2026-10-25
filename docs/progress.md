# Progreso

Documento vivo. El hook `SessionStart` muestra la sección *Estado actual* al
abrir cada sesión: mantenerla corta y al día.

## Estado actual

- **Fase:** 1 — Infraestructura y simulador → **completada y validada** (2026-10-25). Próxima: **Fase 2** (backend MQTT + Reactor).
- **Última tarea:** carga 10/100/1.000 (y 5.000) en modos A y B: pedidos = confirmados = recibidos, sin errores. Evidencia en `testing.md`.
- **Próximo paso:** antes de codificar la fase 2, cerrar con `architect` los ADR de P4 (política de entrega), P5 (Paho v3/v5) y P7 (clave de deduplicación).
- **En curso / a medias:** nada.

## Pendientes / bloqueos

| # | Pendiente | Bloquea | Notas |
|---|---|---|---|
| P4 | Política de entrega MQTT → persistencia (ack, reintentos, recuperación) | Fase 2-3 | Decisión de arquitectura abierta (main.md §8 Pipeline A) |
| P5 | Paho Java: cliente `mqttv3` o `mqttv5` | Fase 2 | Ambos 1.2.5 en Maven Central |
| P6 | Mosquitto CLI no instalado en el host | — | Usar `docker compose exec mosquitto mosquitto_sub ...` (ver README) |
| P8 | El broker conserva status retained `offline` de los 5.000 `esp32-sim-*` de las pruebas de carga | Fase 2 | Decidir si el backend los registra; limpiar con `simulator/tools/clear_retained.py --prefix esp32-sim-` |
| P9 | Perfil Docker Compose opcional para el simulador | — | main.md §14 lo deja opcional; hoy corre desde el host |
| P7 | `sequence` se reinicia al reiniciar el dispositivo: definir clave de deduplicación | Fase 2-3 | Ver `mqtt-contract.md` → Garantías |

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
