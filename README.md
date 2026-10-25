# IoT Reactive Lab

Laboratorio para estudiar programación reactiva con Spring WebFlux y Project
Reactor sobre un sistema IoT: ESP32 físico + dispositivos simulados → MQTT →
backend reactivo → PostgreSQL / WebSocket → dashboard React.

- Especificación: [main.md](main.md)
- Estado del proyecto: [docs/progress.md](docs/progress.md)
- Guía para Claude Code: [CLAUDE.md](CLAUDE.md)

## Inicio rápido

```bash
cp .env.example .env                 # completar contraseñas (openssl rand -base64 18)
docker compose up -d mosquitto postgres

cd backend && mvn spring-boot:run    # :8080   (Java 17, ver backend/README.md)
cd frontend && npm install && npm run dev   # :5173 (Node ≥ 20.19, ver frontend/README.md)

cd simulator
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python simulator.py --devices 50 --interval 1 --spikes --disconnects
```

Abrir http://localhost:5173. Detalle: [simulator/README.md](simulator/README.md),
[backend/README.md](backend/README.md), [frontend/README.md](frontend/README.md).

## Red: localhost, servicios Docker e IP LAN

| Quién se conecta | A Mosquitto | A PostgreSQL |
|---|---|---|
| Proceso en el host (simulador, backend en desarrollo) | `localhost:1883` | `localhost:5432` |
| Contenedor de este compose | `mosquitto:1883` | `postgres:5432` |
| ESP32 u otro equipo de la LAN | `<IP LAN del host>:1883` | no expuesto |

Desde el ESP32, `localhost` es el propio ESP32. La IP LAN del host cambia al
cambiar de red:

```bash
ip -4 addr show | grep -oP 'inet \K[\d.]+'   # la que no es 127.x ni 172.x
```

Mosquitto escucha en todas las interfaces (para el ESP32) **con
autenticación obligatoria y ACL**; PostgreSQL solo en `127.0.0.1`. No exponer
el broker a Internet: no tiene TLS. En una red no confiable, `MQTT_BIND=127.0.0.1`
en `.env`.

## Usuarios MQTT

Definidos en [infra/mosquitto/config/acl](infra/mosquitto/config/acl); las
contraseñas salen de `.env` y el contenedor genera el archivo de contraseñas
al arrancar (ADR-007). Tras cambiar `.env`:
`docker compose up -d --force-recreate mosquitto`.

| Usuario | Puede |
|---|---|
| `backend` | Leer `iot/devices/#` |
| `simulator` | Escribir `iot/devices/#` |
| `healthcheck` | Leer `$SYS/#` |
| `<deviceId>` (p. ej. `esp32-real-001`) | Escribir solo `iot/devices/<deviceId>/#` |

Verificación manual (el CLI de Mosquitto está dentro del contenedor):

```bash
set -a; . ./.env; set +a
docker compose exec mosquitto mosquitto_sub -u backend -P "$MQTT_BACKEND_PASSWORD" -t 'iot/devices/#' -v
```
