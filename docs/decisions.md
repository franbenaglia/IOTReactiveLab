# Decisiones (ADR)

Formato: Contexto / Decisión / Consecuencias. Estado: **vigente**,
**propuesta** (pendiente de confirmar) o **reemplazada por ADR-NNN**. No se
borran ADRs: se reemplazan.

---

## ADR-001 — Monolito modular + simulador independiente
**Estado:** vigente · 2026-10-25

**Contexto.** main.md §20 pide empezar simple y permitir Kafka / múltiples
instancias más adelante sin reescritura.

**Decisión.** Un único backend Spring Boot organizado por paquetes
(`config, mqtt, domain, reactive, processing, persistence, websocket, api,
metrics, exception`), un simulador Python independiente, un firmware y un
frontend, cada uno en su carpeta. Sin Kafka ni Kubernetes en la v1.

**Consecuencias.** La frontera MQTT → Reactor se encapsula en
`MqttReactorAdapter` (expone `Flux<Telemetry>`); reemplazar MQTT por Kafka
implica cambiar ese adaptador, no los pipelines.

---

## ADR-002 — Hardware físico: ESP32 DevKit V1 + DHT11
**Estado:** vigente · 2026-10-25

**Contexto.** main.md no fija el sensor. El usuario usará la misma placa y
sensores que `../esp32-proj/esp32-screen-dht11-spi`, probado de punta a punta.

**Decisión.** Placa ESP32 DevKit V1 (ESP-WROOM-32, `board = esp32doit-devkit-v1`).
Sensor DHT11 en **GPIO 4**, alimentado a 3V3. Pines reservados igual que en el
proyecto de referencia (I2C 21/22 para un futuro TMP102/BME280, ADC1 en 35).
La pantalla TFT del proyecto de referencia queda **fuera del alcance** inicial.

**Consecuencias.**
- El dispositivo físico **sí** envía `humidity` (el DHT11 la mide). El contrato ya la admite como opcional.
- Rango del DHT11: 0-50 °C, sin negativos, resolución 1 °C / 1 %. Se reutiliza el filtro de plausibilidad + saltos del proyecto de referencia.
- Lectura mínima cada ~1 s; se usa 2,5 s (valor probado).
- La abstracción `TemperatureSensor` se mantiene para sumar DS18B20/DHT22/BME280 sin tocar la publicación.

---

## ADR-003 — Toolchain del firmware
**Estado:** vigente · 2026-10-25 (P2 y P3 resueltos el mismo día)

**Decisión.**
- PlatformIO, `platform = espressif32@7.0.1` (Arduino core 2.0.17 / ESP-IDF 4.4.7), la versión fijada y verificada en el hardware de referencia.
- Credenciales en `secrets.ini` vía `extra_configs` (fuera del repo) con `secrets.ini.example` versionado. Ojo: sin `secrets.ini` ningún entorno compila (`No section: 'secrets'`).
- ArduinoJson 7 para el payload.
- Máquina de estados WiFi → MQTT → Listo con backoff exponencial separado por capa, sin bucles bloqueantes (portada del proyecto de referencia).

**Resuelto — P2, librería MQTT: `256dpi/MQTT`** (confirmado por el usuario).
Antecedente: El proyecto de referencia usa PubSubClient
2.8, que **solo publica con QoS 0** y el contrato exige QoS 1. Candidatas:
`256dpi/MQTT` (lwmqtt; publish QoS 1, LWT, síncrona) o `AsyncMqttClient`
(asíncrona sobre AsyncTCP). Se elige 256dpi/MQTT: publica QoS 1, soporta LWT y su API se parece a
PubSubClient, lo que facilita portar la máquina de estados de la referencia.

**Resuelto — P3, NTP: se agrega** (confirmado por el usuario). El contrato pide `timestamp` UTC; el proyecto de
referencia no tenía NTP. Se agrega `configTime()` y no se publica hasta tener
hora sincronizada (o se publica con la política que fije `mqtt-contract.md`).

---

## ADR-004 — Versiones base
**Estado:** propuesta (confirmar al iniciar fase 2) · 2026-10-25

**Decisión preliminar.** Java 21 LTS, Spring Boot 4.1.x (4.1.2 última estable
al 2026-10-25), Paho Java 1.2.5, r2dbc-postgresql 1.1.x, Flyway (versión
gestionada por Spring Boot), Testcontainers 2.x, PostgreSQL 17, Mosquitto 2.x,
paho-mqtt Python 2.1, React + Vite + TS (versiones actuales al iniciar fase 4).

**Consecuencias.** Requiere instalar JDK 21 (P1). Las versiones de librerías
del backend se toman del BOM de Spring Boot salvo que no estén gestionadas.

---

## ADR-005 — Red: el ESP32 llega al broker por la IP LAN del host
**Estado:** vigente · 2026-10-25

**Decisión.** Mosquitto publica el puerto 1883 en el host. Contenedores entre
sí usan nombres de servicio (`mosquitto`, `postgres`); procesos en el host
(simulador, backend en dev) usan `localhost`; el ESP32 usa la **IP LAN** del
host, nunca `localhost`. Autenticación obligatoria en Mosquitto
(`allow_anonymous false`, archivo de contraseñas fuera del repo).

**Consecuencias.** La IP LAN cambia al cambiar de red: síntoma `rc=-2` en el
firmware (lección del proyecto de referencia). Se documenta cómo verificarla.

---

## ADR-006 — Harness: orquestador único + subagentes sin estado
**Estado:** vigente · 2026-10-25

**Decisión.** El agente principal orquesta y es el **único** que escribe
`docs/progress.md` y hace commits. Los subagentes (`.claude/agents/`) reciben
tareas acotadas y devuelven informes. Flujo y tabla de delegación en
`CLAUDE.md` §4. Un hook `SessionStart` inyecta el estado actual.

**Consecuencias.** El estado del proyecto vive en archivos versionados, no en
la memoria de la sesión; cualquier sesión nueva puede retomar leyendo
`CLAUDE.md` → `progress.md` → `decisions.md`.

---

## ADR-007 — Mosquitto: autenticación con usuarios fijos y contraseñas desde `.env`
**Estado:** vigente · 2026-10-25

**Decisión.** `allow_anonymous false` desde el primer día. El archivo de
contraseñas **no se versiona ni se monta**: el entrypoint del contenedor lo
genera al arrancar con `mosquitto_passwd` a partir de variables de `.env`.
Usuarios: `backend` (lee `iot/devices/#`), `simulator` (escribe
`iot/devices/#`), `healthcheck` (lee `$SYS`), y uno por dispositivo físico
cuyo usuario es su `deviceId` (ACL `pattern write iot/devices/%u/#`: un ESP32
solo puede publicar en sus propios topics).

**Consecuencias.** Cambiar una contraseña = editar `.env` y
`docker compose up -d --force-recreate mosquitto`. El simulador no puede tener
ACL por dispositivo (un usuario para N dispositivos virtuales).

---

## ADR-008 — Simulador: paho-mqtt integrado al event loop de asyncio
**Estado:** vigente · 2026-10-25

**Contexto.** main.md pide paho-mqtt + asyncio, sin un proceso por dispositivo.
paho no es asyncio nativo.

**Decisión.** Integrar cada `paho.Client` al event loop con sus callbacks de
socket (`on_socket_open/close/register_write`, patrón del ejemplo oficial
`loop_asyncio.py`): un único hilo atiende las N conexiones. Protocolo MQTT 5
en el simulador, para poder desconectar con *reason code* 0x04 ("Disconnect
with Will Message") y provocar el LWT a voluntad. `connect()` (bloqueante:
DNS + TCP) se ejecuta en un executor, con un semáforo que limita conexiones
simultáneas. Una publicación cuenta como **confirmada** solo al recibir el
PUBACK (`on_publish`).

**Alternativas descartadas.** `aiomqtt` (envuelve paho, pero no expone la
desconexión con will); un hilo `loop_start()` por cliente (1.000 hilos).

**Consecuencias.** Sin dependencias extra además de paho y PyYAML. El
backend puede usar MQTT 3.1.1 o 5 (P5) sin afectar al simulador: Mosquitto
traduce entre versiones.
