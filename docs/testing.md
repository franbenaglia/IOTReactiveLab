# Pruebas

## Estrategia

| Componente | Herramientas | Qué se prueba |
|---|---|---|
| Simulador | pytest, Mosquitto real en Docker | Generación de datos, IDs únicos, intervalos, reconexión, publicación |
| Backend | JUnit 5, Reactor Test (StepVerifier), Testcontainers (PostgreSQL, Mosquitto) | Validación, transformación, errores, concurrencia, integración |
| Frontend | Vitest, Testing Library | Componentes, consumo de API, recepción WebSocket |
| Firmware | `pio run` (compilación), prueba en placa con `tools/leer-serie.py` | Compila todos los entornos; publica según contrato |
| Carga | `scripts/load/` | 10 / 100 / 1.000 / 5.000 dispositivos |

Regla: cada criterio de aceptación de `development-plan.md` se cierra con una
fila en el registro, con comando y resultado. Las pruebas de carga registran
también la máquina y las condiciones.

## Máquina de referencia

Notebook de desarrollo: Intel Core i5-1135G7 (4 núcleos / 8 hilos, 2,4 GHz),
15 GiB RAM, Ubuntu 22.04, kernel 6.8, Docker 29.8. Broker y simulador en la
misma máquina (sin red física de por medio): las latencias son de loopback y
no representan una LAN. El navegador y el IDE abiertos compiten por CPU.

## Cómo reproducir la fase 1

```bash
docker compose up -d mosquitto postgres
cd simulator && .venv/bin/pytest                 # unitarias + integración
cd ../scripts/load && ./run-scenario.sh 1000 A 1 60
```

`run-scenario.sh` corre el simulador y, en paralelo, `tools/count_messages.py`
suscripto como `backend`: compara lo **confirmado** por el broker (PUBACK)
con lo **recibido** por un suscriptor, valida cada mensaje contra el contrato
y detecta duplicados `(deviceId, sequence)`.

## Registro

| Fecha | Fase | Prueba / criterio | Comando | Resultado | Notas |
|---|---|---|---|---|---|
| 2026-10-25 | 1 | C1: Mosquitto y PostgreSQL `healthy` | `docker compose up -d mosquitto postgres` + `docker compose ps` | ✅ ambos `(healthy)` | |
| 2026-10-25 | 1 | C2: conexión sin credenciales rechazada | `mosquitto_pub` sin `-u` / con clave errónea | ✅ `Connection Refused: not authorised` | |
| 2026-10-25 | 1 | ACL: un ESP32 solo publica en sus topics | `mosquitto_pub -u esp32-real-001 -t iot/devices/otro/...` | ✅ no llega al suscriptor; en su propio topic sí | |
| 2026-10-25 | 1 | C3: 10 dispositivos publican según el contrato | `simulator.py --devices 10 --interval 1 --duration 20` + `count_messages.py` | ✅ 200 pedidos = 200 confirmados = 200 recibidos, 200 válidos | |
| 2026-10-25 | 1 | C4: carga 10/100/1.000 × modos A/B, 60 s | `scripts/load/run-scenario.sh N A\|B 1 60` | ✅ ver tabla de carga | |
| 2026-10-25 | 1 | C5: `online` retained + LWT `offline` | 3 dispositivos, `kill -9` del simulador, `mosquitto_sub` en `+/status` | ✅ 3 `online` y luego 3 `offline` (LWT) | |
| 2026-10-25 | 1 | C6: pytest | `.venv/bin/pytest` | ✅ 39 pasan (33 unitarias + 6 integración) | Integración: publicación A y B, LWT, reconexión automática, clave errónea, inválidos |

## Carga — fase 1 (2026-10-25)

Intervalo 1 s por dispositivo, 60 s, QoS 1, `--seed 42`. Resultados crudos en
`scripts/load/results/` (no versionado).

| Dispositivos | Modo | Conexiones | Pedidos | Confirmados | Recibidos | Válidos | Duplicados | conf/s | ACK avg / max | Errores |
|---|---|---|---|---|---|---|---|---|---|---|
| 10 | A | 10 | 600 | 600 | 600 | 600 | 0 | 10,0 | 0,49 / 1,08 ms | 0 |
| 10 | B | 1 | 600 | 600 | 600 | 600 | 0 | 10,0 | 0,63 / 3,27 ms | 0 |
| 100 | A | 100 | 5.998 | 5.998 | 5.998 | 5.998 | 0 | 99,8 | 0,69 / 9,61 ms | 0 |
| 100 | B | 1 | 6.000 | 6.000 | 6.000 | 6.000 | 0 | 99,9 | 5,83 / 42,99 ms | 0 |
| 1.000 | A | 1.000 | 59.713 | 59.713 | 59.713 | 59.713 | 0 | 989,6 | 0,33 / 38,81 ms | 0 |
| 1.000 | B | 10 | 59.974 | 59.974 | 59.974 | 59.974 | 0 | 997,9 | 2,22 / 53,51 ms | 0 |

Extra (no exigido en fase 1), 5.000 dispositivos:

| Dispositivos | Modo | Conexiones | Pedidos | Confirmados | Recibidos | Válidos | Duplicados | conf/s | ACK avg / max | Errores |
|---|---|---|---|---|---|---|---|---|---|---|
| 5.000 | B (250/conexión) | 20 | 299.343 | 299.343 | 299.343 | 299.343 | 0 | 4.942 | 15 / 240 ms | 0 |
| 5.000 | A | 5.000 | 242.891 | 242.891 | 242.891 | 242.891 | 0 | 3.726 | 702 / 1.675 ms | 0 |

**Límite observado — 5.000/A satura el simulador, no el broker.** Objetivo
5.000 msg/s, logrado ~3.700-4.000. Señales: las líneas de estadísticas,
programadas cada 10 s, salen a 10,4 / 21,9 / 34,0 s (el event loop no da
abasto); la latencia del ACK crece de forma sostenida (cola local); con 20
conexiones (modo B) el mismo caudal sale sin problema. Un proceso Python
atiende 5.000 sockets con el costo por mensaje de paho. Mitigación si hace
falta: varias instancias con `--id-offset` (p. ej. 2 × 2.500). Aun saturado no
hubo pérdidas ni duplicados: todo lo pedido se confirmó y se recibió.

Observaciones:
- En todos los casos **pedidos = confirmados = recibidos**, sin duplicados ni mensajes inválidos.
- Modo A con 1.000 conexiones publica algo menos que 60.000 porque cada dispositivo espera su conexión antes de arrancar y el arranque se reparte en el primer intervalo (conexiones de 50 en 50).
- La latencia del ACK sube en modo B: los mensajes de 100 dispositivos se serializan por un mismo socket y compiten por las mismas 100 ranuras de `max_inflight`. Es una diferencia real entre los modos, no un defecto.
- La primera corrida de 10/A contó 18 mensajes ajenos (una prueba de LWT en paralelo, otro prefijo). Se agregó `--prefix` al contador y se repitió; la fila es la repetición.
