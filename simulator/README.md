# Simulador de dispositivos ESP32

Genera N dispositivos virtuales que publican telemetría y estado por MQTT
según [docs/mqtt-contract.md](../docs/mqtt-contract.md). Un único proceso
asyncio atiende todas las conexiones (ADR-008).

## Puesta en marcha

```bash
docker compose up -d mosquitto            # desde la raíz, con .env completo
cd simulator
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python simulator.py --devices 10
```

La contraseña del usuario MQTT `simulator` se toma, en orden, de
`--password`, de la variable `MQTT_SIMULATOR_PASSWORD` o del `.env` de la raíz.

## Ejemplos

```bash
python simulator.py --devices 10
python simulator.py --devices 100 --interval 2
python simulator.py --devices 1000 --interval 1
python simulator.py --devices 100 --duration 300
python simulator.py --devices 50 --mode random                       # intervalos distintos por dispositivo
python simulator.py --devices 50 --mode burst                        # ráfagas cada 30 s
python simulator.py --devices 5000 --connection-mode B --mode load --rate 2000
python simulator.py --devices 20 --disconnects --invalid-pct 10 --spikes
python simulator.py --help
```

Todos los parámetros tienen su equivalente en [config.yaml](config.yaml); la
CLI pisa al YAML. `--seed` hace reproducible la evolución de temperaturas.

## Modos de simulación (`--mode`)

| Modo | Comportamiento |
|---|---|
| `normal` | Cada dispositivo publica cada `interval` exacto. Arrancan desfasados al azar dentro del primer intervalo, para no publicar todos en el mismo instante. |
| `random` | Cada intervalo es `interval × U(1-jitter, 1+jitter)`: los dispositivos derivan entre sí. |
| `burst` | Como normal, pero cada `burst_every_seconds` cada dispositivo envía `burst_size` mensajes seguidos. |
| `load` | El intervalo se deriva del caudal total: `interval = devices / rate`. |

Se combinan con estos comportamientos (flags):

| Flag | Comportamiento |
|---|---|
| `--disconnects` | Con probabilidad `disconnect_probability` por muestra, el dispositivo se cae `disconnect_seconds` y vuelve. En modo A es una caída MQTT real (el broker publica el LWT `offline`). |
| `--invalid-pct N` | N % de los mensajes son inválidos: JSON truncado, campo faltante, temperatura fuera de rango, tipo incorrecto o `deviceId` distinto al del topic. |
| `--spikes` | Picos de `spike_degrees` durante `spike_samples` muestras: superan `max` a propósito, para probar alertas. |
| `--humidity` | Agrega humedad simulada (por defecto `null`, como un sensor solo-temperatura). |

**Evolución de la temperatura.** Paseo aleatorio con reversión a la media de
cada dispositivo: `x += N(0, variation) + reversion·(base − x)`, acotado a
`[min, max]`. Cada muestra depende de la anterior.

## Modos de conexión (`--connection-mode`)

| | A (por defecto) | B |
|---|---|---|
| Conexiones | Una por dispositivo | `ceil(devices / devices_per_connection)` |
| `client_id` | `deviceId` | `esp32-sim-shared-NNNN` |
| LWT | Uno por dispositivo | **Ninguno por dispositivo** |
| Estudia | Al broker con muchas conexiones | Volumen de telemetría |

**El modo B es una optimización del generador de carga y no reproduce
fielmente dispositivos MQTT independientes.** Topic y payload siguen
identificando a cada dispositivo, pero: no hay LWT por dispositivo (si el
proceso muere, nadie publica `offline`); una caída de la conexión afecta a
todos sus dispositivos a la vez; las desconexiones simuladas se representan
publicando `offline`/`online` y no cortan la conexión; y el orden y el control
de flujo (`max_inflight`) se comparten entre los dispositivos de la conexión.

## Estadísticas

Cada `stats.interval_seconds` se imprime una línea:

```text
[  10.0s] disp=100 conn=100/100 | telemetría pedidos=1000 confirmados=998 enVuelo=2 | 100.0 pedidos/s 99.8 conf/s | ack avg=0.9ms max=4.1ms | errores=0 reconex=0
```

| Campo | Significado |
|---|---|
| `pedidos` | Publicaciones que paho aceptó para enviar (**solicitadas**) |
| `confirmados` | Publicaciones con PUBACK del broker (**confirmadas**, QoS 1) |
| `enVuelo` | Pedidos todavía sin PUBACK |
| `ack` | Latencia publish → PUBACK en la ventana |
| `errores` | Sin PUBACK tras `ack_timeout_seconds` + rechazadas localmente (cola llena) + fallos de conexión |
| `reconex` | Reconexiones exitosas (automáticas o tras una desconexión simulada) |

Confirmado significa que el **broker** recibió el mensaje, no que un
suscriptor lo procesó. Para medir del lado receptor:

```bash
.venv/bin/python tools/count_messages.py --duration 60   # recibidos, válidos, duplicados, dispositivos
```

Al terminar se imprime un resumen JSON (`--summary-json` lo guarda en un
archivo). Código de salida 1 si hubo fallos de conexión o mensajes sin
confirmar.

## Pruebas

```bash
.venv/bin/pytest -m "not integration"   # unitarias, sin broker
.venv/bin/pytest                        # todas; las de integración necesitan Mosquitto
```

Carga reproducible: [scripts/load/run-scenario.sh](../scripts/load/run-scenario.sh).

## Red

| El simulador corre en | `--host` |
|---|---|
| El host (lo habitual) | `localhost` |
| Otro equipo de la LAN | IP LAN del host con Docker |
| Un contenedor de la misma red compose | `mosquitto` |
