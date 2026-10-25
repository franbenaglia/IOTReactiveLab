# Progreso

Documento vivo. El hook `SessionStart` muestra la sección *Estado actual* al
abrir cada sesión: mantenerla corta y al día.

## Estado actual

- **Fase:** 0 — Harness y planificación → completada. Próxima: **Fase 1** (infraestructura + simulador).
- **Última tarea:** creado el harness (CLAUDE.md, agentes, docs de seguimiento, hook de inicio). Hardware ESP32 definido a partir del proyecto de referencia.
- **Próximo paso:** Fase 1, tarea 1.1 — `docker-compose.yml` con Mosquitto (con autenticación) y PostgreSQL, ver `development-plan.md`.
- **En curso / a medias:** nada.

## Pendientes / bloqueos

| # | Pendiente | Bloquea | Notas |
|---|---|---|---|
| P1 | Instalar JDK 21 (solo hay 8, 11 y 17) | Fase 2 | `sudo apt install openjdk-21-jdk` o SDKMAN. Ver ADR-004 |
| P2 | Elegir librería MQTT del firmware con soporte de publicación QoS 1 | Fase 5 | PubSubClient solo publica QoS 0. Ver ADR-003 |
| P3 | NTP en el firmware para `timestamp` UTC | Fase 5 | El proyecto de referencia no usaba NTP. Ver ADR-003 |
| P4 | Política de entrega MQTT → persistencia (ack, reintentos, recuperación) | Fase 2-3 | Decisión de arquitectura abierta (main.md §8 Pipeline A) |
| P5 | Paho Java: cliente `mqttv3` o `mqttv5` | Fase 2 | Ambos 1.2.5 en Maven Central |
| P6 | Mosquitto CLI no instalado en el host | — | Usar `docker compose exec mosquitto mosquitto_sub ...` |
| P7 | `sequence` se reinicia al reiniciar el dispositivo: definir clave de deduplicación | Fase 2-3 | Ver `mqtt-contract.md` → Garantías |

## Entorno

Detectado el 2026-10-25 en la notebook de desarrollo (Linux, Ubuntu 22.04):

| Herramienta | Versión | Estado |
|---|---|---|
| Java | OpenJDK 17.0.19 | **Falta 21** (P1) |
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

### 2026-10-25 — Fase 0: harness
- Leída la especificación `main.md`. Repositorio vacío salvo ese archivo.
- Creados `CLAUDE.md`, `.claude/agents/*` (6 agentes), `.claude/hooks/session-start.sh`, `.claude/settings.json`, `docs/*`.
- Analizado el proyecto de referencia `../esp32-proj/esp32-screen-dht11-spi` (ESP32 DevKit V1 + DHT11, probado): hardware, toolchain y lecciones volcadas en `hardware-esp32.md` y ADR-002/003.
- Detectados P1-P6.
- `git init` + commit inicial.
