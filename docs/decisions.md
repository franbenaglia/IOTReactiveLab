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
**Estado:** vigente (Java confirmado por el usuario; librerías a revalidar al iniciar fase 2) · 2026-10-25

**Decisión.** **Java 17 LTS** (el JDK ya instalado), y no 21 como sugiere main.md §2: Spring Boot 4.x exige 17 como mínimo, y nada del stack (WebFlux, Reactor, R2DBC, Paho) necesita 21. El usuario eligió no instalar otro JDK. Resto: Spring Boot 4.1.x (4.1.2 última estable
al 2026-10-25), Paho Java 1.2.5, r2dbc-postgresql 1.1.x, Flyway (versión
gestionada por Spring Boot), Testcontainers 2.x, PostgreSQL 17, Mosquitto 2.x,
paho-mqtt Python 2.1, React + Vite + TS (versiones actuales al iniciar fase 4).

**Consecuencias.** Sin features de Java 21 (pattern matching en `switch` con records, sequenced collections, virtual threads). Virtual threads no aplican igual en un backend reactivo. Migrar a 21 o 25 más adelante solo implica cambiar `java.version` en el `pom.xml`. Las versiones de librerías
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

---

## ADR-009 — Backend: cliente Paho MQTT 5 (`mqttv5`) — resuelve P5
**Estado:** vigente (aprobado por el usuario) · 2026-10-25

**Contexto.** Paho Java ofrece `client.mqttv3` y `mqttv5.client`, ambos 1.2.5.
Verificado con `javap` sobre los jars: los dos tienen `setManualAcks` y
`messageArrivedComplete`, pero en v3 `setMaxInflight` limita solo lo que el
cliente **publica**. El límite de mensajes **recibidos** sin confirmar es, en
3.1.1, un valor global del broker (`max_inflight_messages`). En v5 el cliente
lo declara en el CONNECT: `setReceiveMaximum`.

**Opciones.**
1. **v5**: `ReceiveMaximum` configurable desde el backend (perilla de backpressure para el experimento 3), sesión persistente con `cleanStart=false` + `sessionExpiryInterval`, reason codes en los errores.
2. v3.1.1: la API más usada y probada. El límite de entrada depende solo del broker; la sesión persistente es `cleanSession=false` sin expiración propia.

**Decisión.** v5. Encaja con el objetivo del laboratorio: el
backend controla cuánto trabajo acepta. Mosquitto traduce entre versiones,
así que el ESP32 (3.1.1) y el simulador (5) no se ven afectados.

**Riesgo y verificación.** `mqttv5.client` tiene menos uso que v3. Al inicio
de la fase 2, una prueba de integración debía demostrar: (a) el broker nunca
entrega más de `ReceiveMaximum` mensajes sin confirmar; (b) un mensaje no
confirmado se reentrega al reconectar. Si alguna fallaba, se volvía a v3.

**Validado el 2026-10-25** (`PahoV5BehaviorIT`, Mosquitto 2.0.22): (a) con
`ReceiveMaximum=5` llegan exactamente 5, y confirmar uno libera exactamente
una ranura; (b) al reconectar se reentregan m2..m6, en orden. Hallazgos:
- **El flag DUP llega en `false` en las reentregas.** No sirve para detectar duplicados; la deduplicación es de la base (ADR-011). La prueba lo verifica, así que avisará si cambia.
- **Retained y reconexión:** el backend se suscribe con `retainHandling=1` (opción de MQTT 5), así los `status` retained se reciben solo cuando la suscripción es nueva. Sin esto, cada reconexión traía de nuevo el último estado de todos los dispositivos (5.000 tras las pruebas de carga).
- **"Listo" = SUBACK recibido**, no CONNACK. Lo publicado entre ambos no llega a una sesión que todavía no tiene suscripción (se vio en `BackendMqttIT`).

---

## ADR-010 — Política de entrega MQTT → PostgreSQL — resuelve P4
**Estado:** vigente (aprobado por el usuario) · 2026-10-25

**Contexto.** main.md §8 pide no depender solo de un Sink en memoria y
definir explícitamente entrega, reintentos y recuperación. Con auto-ack, Paho
confirma el mensaje al volver del callback: si el backend cae o el sink está
lleno, el mensaje se pierde sin que nadie lo sepa.

**Decisión: al menos una vez de punta a punta + escritura idempotente.**

1. **Confirmación manual.** El PUBACK se envía cuando el mensaje llega a un estado final, no al recibirlo:
   | Resultado | ¿Se confirma? | Métrica |
   |---|---|---|
   | Insertado en PostgreSQL | sí | `persisted` |
   | Duplicado (la base lo rechazó por la clave única) | sí | `duplicate` |
   | Inválido (JSON, campos, rango) | sí, de inmediato: reentregarlo no lo arregla | `invalid{reason}` |
   | Falla de la base | **no**: se reintenta con backoff exponencial (`retryWhen`) sin límite de intentos | `persist_retry` |
2. **Orden de los PUBACK.** MQTT exige confirmar QoS 1 en el orden de llegada (§4.6, en 3.1.1 y en 5), pero `flatMap` completa fuera de orden. Un `AckTracker` libera cada PUBACK recién cuando se completaron todos los anteriores, igual que el commit de offsets en Kafka. Un mensaje que nunca llega a estado final bloquearía todos los siguientes: por eso todo camino del pipeline termina en uno de los estados de la tabla.
3. **Backpressure real hasta el broker.** Con la base lenta o caída no se confirma nada, se llenan las `ReceiveMaximum` ranuras (ADR-009) y el broker deja de entregar: encola hasta `max_queued_messages` (10.000) para la sesión del backend. Más allá descarta, y es medible en `$SYS`. Es el límite documentado del backpressure con MQTT: el broker es el buffer.
4. **Sesión persistente.** `clientId` fijo (`iotlab-backend`), `cleanStart=false`, expiración de sesión configurable (1 h por defecto). Si el backend se reinicia, el broker reentrega lo no confirmado y la clave única absorbe los duplicados (ADR-011).
5. **Sink acotado por construcción.** `Sinks.many().unicast().onBackpressureBuffer(cola de ReceiveMaximum)`. El broker nunca envía más que `ReceiveMaximum` sin confirmar, así que la cola no puede desbordar. Si `tryEmitNext` falla igual (bug), se registra `dropped{reason}` y se confirma, para no bloquear el `AckTracker`. Una prueba verifica que no ocurre con carga.
6. **Tiempo real separado.** Los pipelines B (WebSocket) y C (alertas) se alimentan de otro sink, `multicast().directBestEffort()`. Un cliente lento pierde actualizaciones y nunca frena la persistencia. Allí perder intermedias es aceptable (main.md §8).
7. **Ciclo de vida.** Al arrancar se suscriben primero los pipelines y después se conecta MQTT. Al detener: se desconecta MQTT, se esperan las escrituras en curso con un plazo, y se liberan los `Disposable`.

**Consecuencias.** No se pierde ningún mensaje que el broker haya aceptado
mientras su cola no se llene. Puede haber duplicados en tránsito, pero nunca en
la base. Es un diseño más complejo que el auto-ack, a cambio de concentrar lo
más educativo del proyecto (backpressure, orden, reintentos). Kafka sigue
siendo innecesario en la v1.

---

## ADR-011 — Clave de deduplicación: `(device_id, measured_at, sequence)` — resuelve P7
**Estado:** vigente (aprobado por el usuario) · 2026-10-25

**Contexto.** `sequence` vuelve a 1 cuando un dispositivo reinicia (el ESP32
sin persistencia, o el simulador relanzado). Una clave `(device_id, sequence)`
descartaría mediciones legítimas.

**Opciones.**
1. `UNIQUE (device_id, sequence)` + persistir `sequence` en NVS en el ESP32. No resuelve el simulador relanzado y desgasta la flash.
2. **`UNIQUE (device_id, measured_at, sequence)`**. Una reentrega MQTT es byte a byte el mismo payload (mismo `timestamp`, misma `sequence`): se detecta. Tras un reinicio se repite la `sequence`, pero con otro `timestamp`: no hay conflicto.
3. Agregar `bootId` al contrato. Es la semántica más limpia, pero cambia el contrato de main.md §4.

**Decisión.** Opción 2. Es la única sin cambio de contrato ni de
firmware, y **el mismo índice sirve para las consultas históricas** (`WHERE
device_id = ? AND measured_at BETWEEN …`, y la última medición con `ORDER BY
measured_at DESC`). Un solo índice para deduplicación e historia. Inserción
con `INSERT … ON CONFLICT DO NOTHING`; 0 filas afectadas = duplicado.

**Consecuencias.** La deduplicación depende de que el productor arme el
payload una sola vez y lo reenvíe idéntico, cosa que cumplen paho y
256dpi/MQTT. Requiere hora válida en el dispositivo: el ESP32 no publica hasta
sincronizar NTP (ADR-003). Se agrega la métrica `sequence_reset` cuando la
`sequence` de un dispositivo retrocede, que indica que se reinició.

---

## ADR-012 — Maven del backend: settings propio del proyecto
**Estado:** vigente (aprobado por el usuario) · 2026-10-25

**Contexto.** `~/.m2/settings.xml` del usuario redirige **todos** los
repositorios (`mirrorOf *`) a un Archiva de la intranet de SENASA, inaccesible
fuera de esa red. Así, `mvn` no puede descargar nada (verificado).

**Decisión.** `backend/.mvn/maven.config` con
`--settings .mvn/settings.xml`, que apunta a Maven Central. Afecta solo a este
proyecto y no toca la configuración global del usuario. Se suma Maven Wrapper
(`mvnw`) para fijar la versión de Maven.

---

## ADR-013 — Diseño de la persistencia (fase 3)
**Estado:** vigente · 2026-10-25

**Decisiones.**
1. **`DatabaseClient` con SQL explícito**, no repositorios derivados de Spring Data. La inserción necesita `ON CONFLICT ON CONSTRAINT telemetry_dedup DO NOTHING` (ADR-011), el listado de dispositivos usa `LEFT JOIN LATERAL` para la última medición, y `last_seen` se actualiza con `unnest` de arrays. Los repositorios derivados no ayudan en ninguno de los tres casos. Las clases se llaman `TelemetryRepository` y `DeviceRepository`, como pide main.md.
2. **Una fila por mensaje, con `flatMap(concurrency)`.** Sin lotes (`bufferTimeout` + inserción múltiple) en esta fase: el experimento 1 compara justamente la concurrencia de `flatMap` sobre escrituras individuales. Los lotes quedan como variante para la fase 6.
3. **`telemetry.device_id` es FK a `devices`.** Para no duplicar escrituras, `DeviceRegistry` recuerda los dispositivos ya dados de alta. Si la FK falla (SQLSTATE 23503, p. ej. alguien vació `devices`), se olvida el dispositivo y el error se trata como transitorio: el reintento lo da de alta de nuevo.
4. **`last_seen` por lotes**, cada `iotlab.devices.last-seen-flush` (5 s), en una sola sentencia. Es aproximado por diseño: si el backend cae, se pierden como mucho esos segundos de `last_seen`, nunca telemetría.
5. **`status` se persiste en `devices`** (upsert ordenado por hora de recepción, que descarta estados viejos que llegan tarde) **con la misma política de reintentos** que la telemetría, y se confirma recién al guardarse.
6. **Clasificación de errores por SQLSTATE** (`R2dbcTelemetryStore#isTransient`): las clases 22, 23 y 42 no son transitorias, salvo 23503; todo lo demás sí (conexión, pool, timeouts).
7. **Plazo por intento de escritura** (`iotlab.pipeline.store-timeout`, 5 s) con `timeout()` antes de `retryWhen`: una base colgada no devuelve error, y sin plazo la escritura retendría su ranura de `flatMap` indefinidamente.
8. **Pool R2DBC:** `initial-size` = concurrencia (20), `max-size` 25 (margen para la API y el flush). Se precalienta (`R2dbcPoolWarmup`) antes de conectar MQTT, porque al arrancar llega de golpe lo acumulado en la sesión.
9. **Host `127.0.0.1` por defecto, no `localhost`**, para R2DBC: ver el hallazgo en `progress.md` (fase 3).
10. **P8 cerrado:** los `status` retained de dispositivos que nunca enviaron telemetría crean filas en `devices` (status sin `source`). Es lo esperado: un dispositivo que solo dijo "offline" existe. Para limpiar los restos de las pruebas: `simulator/tools/clear_retained.py --prefix <prefijo>` en el broker y `DELETE` en la base.

---

## ADR-014 — Dashboard: stack y estrategia de datos (fase 4)
**Estado:** vigente · 2026-10-25

**Stack.** React 19.3 + TypeScript 5.9 + Vite 8, Mantine 9 (componentes),
Recharts 3, TanStack Query 5, react-router 7, Vitest 4 + Testing Library +
jsdom 29. Versiones elegidas para funcionar con el **Node 20.20** instalado:
react-router 8, Vitest 5 y jsdom 30 exigen Node 22. TypeScript 7 (el
compilador reescrito en Go) se descartó por madurez.

**Qué viaja por dónde.**
| Vista | REST (TanStack Query, polling) | WebSocket |
|---|---|---|
| Vista general | overview (5 s), alertas (3 s), monitor (2 s) | — |
| Dispositivos | listado (10 s) | suscripción filtrada a los ≤ 50 de la página visible, 1 s |
| Individual | dispositivo, historial 24 h (una vez) | ese dispositivo, 1 s: telemetría, estado y alertas |
| Reactive Monitor | `/api/metrics/monitor` (1 s) | — |

El WebSocket se usa donde aporta: flujos continuos filtrados por dispositivo.
La vista general no se suscribe a `"*"`: con 5.000 dispositivos serían ~1 MB
por intervalo para mostrar seis números.

**Tasas por segundo:** el backend expone contadores acumulados (lo que mide
de verdad) y el cliente deriva la tasa con dos lecturas. Si un contador falta
o retrocede (reinicio del backend), la tasa se muestra "no disponible", nunca
como 0.

---

## ADR-015 — Backend y frontend en Docker Compose: fase 7
**Estado:** vigente · 2026-10-25

main.md §14 pide backend y frontend en compose. Se posterga a la fase 7
(estabilidad), junto con el perfil opcional del simulador (P9). Mientras
tanto: backend con `mvn spring-boot:run` o el jar, y frontend con `npm run
dev` (el proxy de Vite resuelve `/api` y `/ws`). Lo que falta: Dockerfiles
(JRE 17; build de Node + nginx con proxy de `/api` y `/ws`) y variables
`POSTGRES_HOST=postgres` y `MQTT_SERVER_URI=tcp://mosquitto:1883`.
