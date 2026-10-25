# Hardware ESP32

Basado en `/home/francisco/desarrollo/iot/esp32-proj/esp32-screen-dht11-spi`
(probado de punta a punta con esta misma placa y sensor). Ver ADR-002 y ADR-003.

## Placa y sensor

| | |
|---|---|
| Placa | ESP32 DevKit V1 (ESP-WROOM-32) — `board = esp32doit-devkit-v1` |
| Plataforma | `espressif32@7.0.1` (Arduino core 2.0.17 / ESP-IDF 4.4.7) |
| Puerto | `/dev/ttyUSB0`, 115200 baudios |
| Sensor | DHT11 (temperatura + humedad) |

## Cableado DHT11

| DHT11 | ESP32 | Nota |
|---|---|---|
| `VCC` / `+` | `3V3` | No a 5 V: la salida de datos va directo a un GPIO |
| `DATA` / `OUT` | `D4` (GPIO 4) | Sin función de arranque (no es strapping) |
| `GND` / `-` | `GND` | |

Sensor pelado de 4 patas: resistencia de 4,7k-10k entre DATA y 3V3. Los
módulos de 3 pines ya la traen.

## Mapa de pines (para `include/BoardConfig.h`)

```c
#define PIN_DHT11       4   // sensor actual
#define PIN_I2C_SDA    21   // reservado: TMP102 / BME280
#define PIN_I2C_SCL    22   // reservado
#define PIN_ADC_TENSION 35  // reservado; ADC1 (ADC2 no funciona con WiFi activo)
#define PIN_LED_PLACA   2   // LED integrado (prueba de vida)
```

Evitar GPIO 6-11 (flash), 12 (strapping de tensión de flash), 0/2/5/15 para
entradas en el arranque. 34-39 son solo entrada.

## Bibliotecas probadas en la referencia

| Biblioteca | Versión | Uso aquí |
|---|---|---|
| `adafruit/DHT sensor library` | ^1.4.6 | Sí |
| `adafruit/Adafruit Unified Sensor` | ^1.1.14 | Sí (dependencia) |
| `bblanchon/ArduinoJson` | ^7.4.3 | Sí |
| `knolleary/PubSubClient` | ^2.8 | **No**: solo publica QoS 0 (P2) |

## Qué reutilizar de la referencia

| De | Para | Por qué |
|---|---|---|
| `src/Conectividad.cpp` | Máquina de estados WiFi→MQTT con backoff | Probada, no bloquea |
| `src/SensorDHT.cpp` | Filtro de plausibilidad + saltos con resincronización | Ajustado contra este sensor: aparecen tramas con checksum OK y bits alterados |
| `tools/leer-serie.py` | Capturar el monitor serie sin TTY | `pio device monitor` falla desde sesiones sin terminal |
| `pruebas/parpadeo.cpp`, `pruebas/prueba_dht.cpp` | Entornos de prueba de hardware | Aíslan placa y sensor |
| `secrets.ini.example` | Plantilla de credenciales | Mismo mecanismo |

## Lecciones aprendidas (bitácora de la referencia)

- **`rc=-2` al conectar MQTT**: casi siempre es la IP del broker. La IP LAN de la notebook cambia al cambiar de red; desde el ESP32, `localhost` es el propio ESP32. Verificar con `ip -4 addr show | grep -oP 'inet \K[\d.]+'` (la que no es 127.x ni 172.x) y `ss -tln | grep 1883`.
- **Contenedor sin red / reinicios en bucle**: revisar que el servicio esté en la red de compose y que la columna PORTS de `docker ps` no esté vacía.
- **Otro servicio ocupando puertos** (p. ej. un cluster kind en 8080): verificar antes de culpar al firmware.
- **`No section: 'secrets'`**: falta `secrets.ini`.
- **`publish()` devuelve false sin explicación**: buffer MQTT chico; dimensionarlo para el payload.
- **Client ID único** por dispositivo: dos placas con el mismo se desconectan mutuamente.
- Cerrar cualquier monitor serie antes de `upload` (puerto ocupado).

## Entornos PlatformIO previstos (fase 5)

| Entorno | Para qué |
|---|---|
| `telemetry` (defecto) | Firmware real: DHT11 + WiFi + MQTT |
| `simulated-sensor` | Mismo firmware con `SimulatedTemperatureSensor` (sin sensor conectado) |
| `prueba-dht` | El DHT11 solo, por serie |
| `parpadeo` | Prueba de vida de la placa |
