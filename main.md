# PROYECTO: IoT Reactive Lab

## 1. Objetivo general

Desarrollar una aplicación completa de monitoreo IoT cuyo objetivo principal sea estudiar, implementar y experimentar con la programación reactiva en Java utilizando Spring Boot, Spring WebFlux y Project Reactor.

La aplicación debe ser funcional, modular, extensible y permitir evaluar el comportamiento de Reactor bajo distintas condiciones de concurrencia y carga.

Se utilizarán dos tipos de dispositivos:

1. Un dispositivo ESP32 real conectado a un sensor de temperatura.
2. Un simulador desarrollado en Python capaz de generar múltiples dispositivos ESP32 virtuales que publican mediciones mediante MQTT.

Todos los dispositivos publicarán sobre un broker MQTT Mosquitto.

El backend Java recibirá las mediciones, las procesará mediante Project Reactor, las almacenará en PostgreSQL utilizando R2DBC y las enviará a un frontend React mediante WebSocket.

La aplicación deberá contar con herramientas para visualizar el comportamiento del sistema, los flujos reactivos y las métricas de rendimiento.

**Este proyecto debe priorizar la comprensión de la programación reactiva, sin sacrificar las buenas prácticas de ingeniería de software.**

---

# 2. Tecnologías

## Backend

- Java 21 o una versión LTS superior compatible.
- Spring Boot.
- Spring WebFlux.
- Project Reactor.
- Reactor Netty.
- Spring Data R2DBC.
- PostgreSQL.
- Cliente MQTT asíncrono Eclipse Paho.
- Spring Boot Actuator.
- Micrometer.
- Maven.

Seleccionar versiones estables y compatibles entre sí.

No introducir Spring MVC ni JPA/Hibernate en el backend.

## Frontend

- React.
- TypeScript.
- Vite.
- Una biblioteca de componentes visuales apropiada.
- Recharts para gráficos.
- WebSocket nativo del navegador.
- TanStack Query, si resulta útil para las consultas REST.

## Simulador

- Python 3.
- Eclipse Paho MQTT para Python.
- asyncio cuando corresponda.
- Configuración mediante archivo YAML y argumentos CLI.

## Dispositivo físico

- ESP32.
- Arduino Framework.
- Sensor de temperatura configurable.
- Conexión Wi-Fi.
- Cliente MQTT.

El modelo exacto del sensor se definirá posteriormente.

La implementación debe permitir incorporar adaptadores para distintos sensores, por ejemplo DS18B20, DHT22 o BME280.

No asumir un modelo concreto ni asignar pines arbitrariamente.

## Infraestructura

- Docker.
- Docker Compose.
- Eclipse Mosquitto.
- PostgreSQL.

La aplicación debe poder ejecutarse localmente sobre una computadora Linux.

---

# 3. Arquitectura del sistema

Implementar los siguientes componentes independientes.

### A. ESP32 Firmware

Programa para el dispositivo físico.

Responsabilidades:

- Conectarse a Wi-Fi.
- Conectarse al broker MQTT.
- Leer el sensor.
- Publicar mediciones periódicamente.
- Identificarse mediante un deviceId único.
- Reconectarse si pierde conectividad.
- Registrar información de diagnóstico mediante puerto serie.

### B. Device Simulator

Aplicación Python independiente.

Responsabilidades:

- Simular múltiples ESP32.
- Publicar mediciones mediante MQTT.
- Administrar múltiples clientes MQTT virtuales.
- Configurar cantidad de dispositivos.
- Configurar frecuencia de publicación.
- Generar valores de temperatura realistas.
- Simular variaciones de temperatura.
- Simular desconexiones.
- Simular dispositivos defectuosos.
- Generar cargas de prueba controladas.

### C. MQTT Broker

Utilizar Mosquitto.

Responsabilidades:

- Recibir publicaciones.
- Gestionar conexiones MQTT.
- Distribuir mensajes a los suscriptores.
- Proporcionar mecanismos de autenticación.
- Permitir configurar políticas de conexión y QoS.

### D. Reactive Backend

Aplicación Spring Boot WebFlux.

Responsabilidades:

- Suscribirse a MQTT.
- Convertir los callbacks MQTT en flujos Reactor.
- Validar mediciones.
- Transformar datos.
- Ejecutar pipelines reactivos.
- Almacenar mediciones.
- Proporcionar endpoints REST.
- Distribuir eventos mediante WebSocket.
- Exponer métricas.
- Controlar errores y sobrecarga.

### E. Dashboard

Aplicación React.

Responsabilidades:

- Mostrar los dispositivos registrados.
- Visualizar temperaturas.
- Mostrar gráficos históricos.
- Recibir mediciones en tiempo real.
- Mostrar estados de conexión.
- Mostrar métricas del backend.
- Permitir observar el comportamiento de los pipelines reactivos.

---

# 4. Contrato MQTT

Utilizar una estructura uniforme para los dispositivos reales y simulados.

## Topic para telemetría

```text
iot/devices/{deviceId}/telemetry
```

Ejemplo:

```text
iot/devices/esp32-real-001/telemetry
```

Para un dispositivo virtual:

```text
iot/devices/esp32-sim-001/telemetry
```

## Topic para estado

```text
iot/devices/{deviceId}/status
```

Los estados posibles serán:

- online
- offline

Utilizar Last Will and Testament de MQTT cuando corresponda.

## Mensaje JSON

```json
{
  "deviceId": "esp32-real-001",
  "temperature": 24.8,
  "humidity": null,
  "timestamp": "2026-10-25T12:30:00Z",
  "sequence": 125,
  "source": "PHYSICAL"
}
```

Campos:

- deviceId: identificador del dispositivo.
- temperature: temperatura en grados Celsius.
- humidity: humedad relativa opcional.
- timestamp: fecha y hora de medición en UTC.
- sequence: número secuencial de la medición.
- source: PHYSICAL o SIMULATED.

La humedad es opcional porque inicialmente solamente disponemos de un sensor de temperatura.

Todos los mensajes deben respetar el mismo contrato.

Utilizar inicialmente MQTT QoS 1.

No asumir que QoS 1 implica procesamiento exactamente una vez: implementar detección de duplicados mediante deviceId y sequence cuando corresponda.

---

# 5. Firmware ESP32

Crear el firmware en una carpeta independiente.

```text
firmware/
    esp32-temperature/
```

Utilizar PlatformIO o una estructura Arduino compatible.

Requisitos:

1. Configuración Wi-Fi.
2. Configuración del broker MQTT.
3. Identificador único del dispositivo.
4. Intervalo configurable de lectura.
5. Adaptador de sensor.
6. Publicación MQTT.
7. Reconexión automática.
8. Logs por puerto serie.
9. Contador secuencial de mensajes.

Crear una interfaz de abstracción para los sensores.

Ejemplo conceptual:

```cpp
class TemperatureSensor {
public:
    virtual bool begin() = 0;
    virtual float readTemperature() = 0;
};
```

Implementar un sensor simulado de firmware que permita probar el ESP32 incluso antes de conocer el sensor físico exacto.

Mantener separada la lógica de adquisición de temperatura de la lógica de publicación MQTT.

No almacenar contraseñas reales en el repositorio.

---

# 6. Simulador de dispositivos ESP32

Este componente es muy importante para el proyecto.

Debe ser una aplicación independiente desarrollada en Python.

Su objetivo es producir múltiples fuentes MQTT que se comporten como dispositivos reales.

## Configuración

Ejemplo:

```yaml
broker:
  host: localhost
  port: 1883

simulation:
  devices: 100
  interval_seconds: 5
  duration_seconds: 0

temperature:
  min: 18
  max: 35
  initial: 24
  variation: 0.5

behavior:
  random_disconnects: false
  invalid_messages: false
  temperature_spikes: false
```

## Comandos CLI

Implementar comandos equivalentes a:

```bash
python simulator.py --devices 10
```

```bash
python simulator.py --devices 100 --interval 2
```

```bash
python simulator.py --devices 1000 --interval 1
```

```bash
python simulator.py --devices 100 --duration 300
```

## Comportamiento de cada dispositivo

Cada dispositivo virtual debe tener:

- ID único.
- Temperatura inicial propia.
- Intervalo de publicación.
- Número secuencial de mensajes.
- Estado de conexión.
- Variación progresiva de temperatura.

Evitar que las temperaturas cambien aleatoriamente de manera completamente independiente entre cada muestra.

Implementar un modelo sencillo de evolución temporal con pequeñas variaciones y límites configurables.

## Modos de simulación

### Modo normal

Los dispositivos generan mediciones con intervalos regulares.

### Modo aleatorio

Cada dispositivo utiliza intervalos ligeramente diferentes.

### Modo burst

Los dispositivos generan ráfagas de mensajes para estudiar la respuesta del backend.

### Modo desconexión

Algunos dispositivos se desconectan aleatoriamente y posteriormente vuelven a conectarse.

### Modo datos inválidos

Un porcentaje configurable de mensajes contiene valores incorrectos o JSON malformado.

### Modo carga

Permite generar una cantidad elevada de mensajes por segundo.

## Escalabilidad del simulador

No crear obligatoriamente un proceso del sistema operativo por cada dispositivo.

Investigar e implementar una solución eficiente basada en I/O asíncrono y tareas concurrentes.

El simulador debe distinguir entre:

- Dispositivos virtuales lógicos.
- Conexiones MQTT físicas.
- Mensajes publicados.

Incluir dos modos:

**Modo A:** cada dispositivo virtual posee su propia conexión MQTT.

**Modo B:** múltiples dispositivos virtuales comparten conexiones MQTT, manteniendo identificadores diferenciados en el topic y payload.

El modo A permite estudiar el comportamiento del broker ante muchas conexiones.

El modo B permite estudiar grandes volúmenes de telemetría sin exigir una conexión por dispositivo.

Documentar que el modo B es una optimización del generador de carga y no reproduce fielmente todas las características de dispositivos MQTT independientes.

## Estadísticas del simulador

Mostrar en consola:

- Cantidad de dispositivos.
- Conexiones MQTT activas.
- Mensajes publicados.
- Mensajes por segundo.
- Errores.
- Reconexiones.
- Tiempo transcurrido.

No confundir publicación solicitada con publicación efectivamente confirmada por MQTT.

---

# 7. Backend: arquitectura interna

Organizar Spring Boot con una estructura modular.

Propuesta:

```text
backend/
  src/main/java/
    com/example/iotlab/
      config/
      mqtt/
      domain/
      reactive/
      processing/
      persistence/
      websocket/
      api/
      metrics/
      exception/
```

Evitar concentrar toda la lógica en una única clase.

## Componentes principales

### MqttSubscriber

Administra la conexión MQTT.

Utilizar un cliente asíncrono compatible con Eclipse Paho.

Debe:

- Conectarse al broker.
- Suscribirse a los topics.
- Recibir mensajes.
- Gestionar reconexiones.
- Registrar errores.
- Liberar recursos al detener la aplicación.

### MqttReactorAdapter

Convierte los mensajes MQTT en un Flux.

Evaluar la utilización de:

```java
Sinks.Many<Telemetry>
```

Este componente constituye uno de los elementos centrales para estudiar Reactor.

Debe tratar explícitamente:

- Fallos de emisión.
- Buffer completo.
- Ausencia de suscriptores.
- Errores de deserialización.
- Sobrecarga.

No asumir que los buffers internos son infinitos.

### TelemetryProcessor

Implementa el pipeline principal.

Operadores que deben utilizarse y documentarse:

- map
- filter
- flatMap
- concatMap
- buffer
- bufferTimeout
- sample
- onErrorResume
- retryWhen
- doOnNext
- doOnError

Cada operador debe utilizarse solamente cuando tenga una finalidad real dentro del sistema.

### TelemetryRepository

Persistencia mediante Spring Data R2DBC.

Utilizar PostgreSQL.

### TelemetryWebSocketHandler

Transmite las mediciones a clientes React.

### TelemetryController

Expone endpoints REST para consultas históricas.

---

# 8. Pipelines reactivos

Implementar al menos tres pipelines diferenciados.

## Pipeline A: procesamiento y persistencia

```text
MQTT
 |
 v
Flux<Telemetry>
 |
 v
Validación
 |
 v
Normalización
 |
 v
Control de duplicados
 |
 v
flatMap()
 |
 v
R2DBC
 |
 v
PostgreSQL
```

Debe permitir configurar el nivel de concurrencia.

Ejemplo:

```java
flatMap(telemetry -> repository.save(telemetry), 20)
```

La implementación real debe gestionar los errores individualmente y evitar que un error de escritura finalice inesperadamente todo el flujo.

El límite de concurrencia debe relacionarse con la configuración del pool de conexiones R2DBC.

Para preservar mediciones, no depender exclusivamente de un Sink en memoria.

Diseñar la frontera entre recepción MQTT, confirmación y persistencia con una política explícita de entrega, reintentos y recuperación.

No es necesario incorporar Kafka en la primera versión.

## Pipeline B: distribución en tiempo real

```text
Flux<Telemetry>
       |
       v
    sample()
       |
       v
   WebSocket
       |
       v
  React Dashboard
```

Permitir configurar la frecuencia de actualización del dashboard.

No es necesario enviar todas las mediciones a todos los clientes.

Implementar filtros por deviceId y mecanismos de agregación.

Distinguir este flujo, donde es aceptable omitir actualizaciones intermedias, del flujo de persistencia.

## Pipeline C: detección de alertas

Generar eventos cuando una temperatura supera un umbral.

Ejemplo:

```text
Temperatura > 35 °C
        |
        v
    ALERTA
        |
        v
    WebSocket
        |
        v
    Dashboard
```

Agregar una política sencilla para evitar emitir continuamente la misma alerta mientras la temperatura permanece fuera de rango.

---

# 9. Persistencia PostgreSQL

Crear una tabla de dispositivos.

```text
devices
-------
id
device_id
source
status
last_seen
created_at
```

Crear una tabla de mediciones.

```text
telemetry
---------
id
device_id
temperature
humidity
measured_at
received_at
sequence
```

Requisitos:

- Índices para consultas históricas.
- Restricción para detectar mensajes duplicados.
- Paginación.
- Consulta por dispositivo.
- Consulta por rango temporal.
- Última medición disponible.

Utilizar migraciones de base de datos mediante Flyway o una herramienta equivalente compatible con el stack.

Si las migraciones emplean JDBC, ejecutarlas durante el inicio y fuera de los event loops, manteniendo R2DBC para las operaciones de aplicación.

---

# 10. API REST

Implementar endpoints:

```text
GET /api/devices
```

Lista de dispositivos.

```text
GET /api/devices/{deviceId}
```

Información de un dispositivo.

```text
GET /api/devices/{deviceId}/latest
```

Última medición.

```text
GET /api/devices/{deviceId}/history
```

Histórico con paginación y filtros temporales.

```text
GET /api/metrics/overview
```

Métricas generales de procesamiento.

```text
GET /actuator/health
```

Estado de salud del backend.

Los controladores deben devolver Mono o Flux según corresponda.

No utilizar block() para resolver las operaciones de los controladores.

---

# 11. Comunicación WebSocket

Implementar:

```text
/ws/telemetry
```

Debe permitir:

- Suscripción a todos los dispositivos.
- Suscripción a dispositivos específicos.
- Actualizaciones en tiempo real.
- Gestión de desconexiones.
- Manejo de clientes lentos.
- Control de errores.

Definir mensajes de suscripción y eventos mediante contratos JSON explícitos.

Evitar enviar innecesariamente todas las mediciones a cada cliente.

---

# 12. Dashboard React

Desarrollar una interfaz moderna de monitoreo IoT.

Debe contener las siguientes secciones.

## Vista general

Mostrar:

- Dispositivos registrados.
- Dispositivos activos.
- Dispositivos desconectados.
- Mediciones por segundo.
- Temperatura promedio.
- Alertas activas.

## Vista de dispositivos

Tabla con:

- Identificador.
- Tipo: físico o simulado.
- Última temperatura.
- Última comunicación.
- Estado.
- Frecuencia estimada de publicación.

Permitir seleccionar un dispositivo.

## Vista individual

Mostrar:

- Temperatura actual.
- Gráfico temporal.
- Últimas mediciones.
- Estado del dispositivo.
- Fuente de datos.
- Alertas.

## Vista Reactive Monitor

Esta vista es especialmente importante.

Debe mostrar:

- Mensajes MQTT recibidos por segundo.
- Mensajes procesados por segundo.
- Escrituras PostgreSQL por segundo.
- Operaciones R2DBC en curso.
- Latencia de procesamiento.
- Errores.
- Eventos rechazados.
- Cantidad de clientes WebSocket.
- Uso de memoria del backend.
- Métricas disponibles de los pools de conexiones y Netty.

Todas las métricas deben provenir de instrumentación real.

No fabricar valores para mostrar actividad en el dashboard.

Si una métrica no puede obtenerse directamente, documentar la limitación e implementar una medición alternativa bien definida.

## Visualización del pipeline

Crear una representación visual del procesamiento:

```text
MQTT RECEIVER
      |
      v
VALIDATION
      |
      v
TRANSFORMATION
      |
      v
PERSISTENCE
      |
      v
POSTGRESQL
```

Cada componente debe mostrar métricas relacionadas con su actividad.

---

# 13. Laboratorio Reactor

Incluir una sección experimental que permita analizar cómo cambia el rendimiento según la configuración.

Implementar escenarios reproducibles.

## Experimento 1: concurrencia

Ejecutar pruebas con:

```text
flatMap concurrency = 1
flatMap concurrency = 5
flatMap concurrency = 20
flatMap concurrency = 50
```

Comparar:

- Throughput.
- Latencia.
- Operaciones en curso.
- Consumo de memoria.
- Errores.

Los cambios de concurrencia deben hacerse mediante configuración de escenarios, sin necesitar modificar y recompilar el código.

## Experimento 2: flatMap vs concatMap

Permitir comparar ambos operadores.

Analizar:

- Concurrencia.
- Orden de procesamiento.
- Throughput.
- Latencia.

## Experimento 3: backpressure

Aumentar progresivamente la frecuencia de publicación del simulador.

Analizar:

- Llegada de mensajes.
- Capacidad de procesamiento.
- Saturación de buffers.
- Eventos descartados o rechazados.
- Uso de memoria.

Explicar los límites del backpressure cuando la fuente de eventos es MQTT.

## Experimento 4: interrupción de PostgreSQL

Detener temporalmente PostgreSQL.

Analizar:

- Comportamiento del backend.
- Errores de escritura.
- Reintentos.
- Recuperación.
- Posible pérdida o duplicación de mensajes.

## Experimento 5: múltiples clientes WebSocket

Simular múltiples clientes conectados.

Medir:

- Conexiones concurrentes.
- Mensajes enviados.
- Latencia.
- Consumo de recursos.

---

# 14. Docker Compose

Crear una configuración para levantar:

- Mosquitto.
- PostgreSQL.
- Backend Spring Boot.
- Frontend React.

El simulador Python puede ejecutarse desde el host o mediante un perfil opcional de Docker Compose.

Utilizar volúmenes persistentes para PostgreSQL.

Agregar healthchecks y configuración mediante variables de entorno.

La red debe permitir que un ESP32 físico conectado a la misma LAN pueda acceder al broker MQTT utilizando la dirección IP de la computadora anfitriona.

Documentar claramente las diferencias entre:

- localhost.
- Nombres de servicios Docker.
- Dirección IP de la computadora en la LAN.

El broker no debe exponerse a Internet sin autenticación y controles de acceso adecuados.

---

# 15. Pruebas

Implementar pruebas automatizadas.

## Backend

- JUnit.
- Reactor Test.
- StepVerifier.
- Pruebas de validación.
- Pruebas de transformación.
- Pruebas de manejo de errores.
- Pruebas de concurrencia.
- Pruebas de integración.

Utilizar Testcontainers para PostgreSQL y, cuando corresponda, Mosquitto.

## Simulador

- Pruebas de generación de datos.
- Pruebas de IDs únicos.
- Pruebas de intervalos.
- Pruebas de reconexión.
- Pruebas de publicación MQTT.

## Frontend

- Pruebas básicas de componentes.
- Pruebas de consumo de API.
- Pruebas de recepción WebSocket.

## Pruebas de carga

Crear scripts reproducibles para ejecutar escenarios de:

- 10 dispositivos.
- 100 dispositivos.
- 1.000 dispositivos.
- 5.000 dispositivos.

No suponer que todos los escenarios funcionarán en cualquier computadora.

Registrar las condiciones y limitaciones de cada prueba.

---

# 16. Observabilidad

Utilizar Spring Boot Actuator y Micrometer.

Implementar métricas para:

- Mensajes MQTT recibidos.
- Mensajes descartados.
- Mensajes válidos.
- Mensajes inválidos.
- Mediciones persistidas.
- Errores de persistencia.
- Tiempo de procesamiento.
- Operaciones concurrentes.
- Conexiones WebSocket.
- Memoria JVM.
- CPU del proceso.

Agregar logs estructurados.

No bloquear los event loops realizando operaciones pesadas de logging, serialización o persistencia.

Evitar métricas de alta cardinalidad innecesarias, especialmente aquellas que incorporen un deviceId diferente como etiqueta para cada dispositivo.

---

# 17. Arquitectura y buenas prácticas

Mantener separados:

1. Adquisición de datos.
2. Comunicación MQTT.
3. Adaptación a Reactor.
4. Procesamiento.
5. Persistencia.
6. Distribución WebSocket.
7. API REST.
8. Observabilidad.

No utilizar un diseño excesivamente complejo para el volumen inicial.

Priorizar interfaces claras, clases con responsabilidades definidas y código fácil de comprender.

Los procesos de adquisición y procesamiento deben poder probarse por separado.

Evitar operaciones bloqueantes dentro de los event loops.

No utilizar subscribe() indiscriminadamente dentro de servicios.

Definir claramente los puntos donde comienzan las suscripciones de larga duración y gestionar su ciclo de vida.

Utilizar StepVerifier para verificar el comportamiento reactivo.

---

# 18. Harness Engineering y seguimiento del proyecto

El desarrollo se realizará mediante Claude Code y puede extenderse durante múltiples sesiones.

Implementar un mecanismo de seguimiento persistente del trabajo.

Crear:

```text
CLAUDE.md

.claude/
    agents/
        architect.md
        backend-developer.md
        frontend-developer.md
        iot-developer.md
        qa-reviewer.md
        consistency-reviewer.md

docs/
    architecture.md
    decisions.md
    reactor-concepts.md
    mqtt-contract.md
    development-plan.md
    progress.md
    testing.md
    experiments.md
```

## Responsabilidades de los agentes

### architect

Define y supervisa la arquitectura.

### backend-developer

Implementa Spring WebFlux, Reactor, MQTT y R2DBC.

### frontend-developer

Implementa React y el dashboard.

### iot-developer

Implementa el firmware ESP32 y el simulador Python.

### qa-reviewer

Desarrolla y ejecuta pruebas.

### consistency-reviewer

Verifica que el código respete las decisiones arquitectónicas, contratos y estándares establecidos.

El agente principal de Claude Code actuará como orquestador.

No asumir que la existencia de archivos de agentes produce automáticamente una ejecución coordinada: definir explícitamente la estrategia de delegación y revisión.

## Persistencia del contexto

Después de cada etapa relevante:

1. Actualizar progress.md.
2. Registrar decisiones en decisions.md.
3. Actualizar architecture.md si corresponde.
4. Documentar problemas pendientes.
5. Registrar pruebas ejecutadas y resultados.
6. Realizar un commit Git cuando la etapa esté validada.

El proyecto debe poder retomarse después de perder el contexto de una sesión.

Al comenzar una nueva sesión, Claude Code deberá consultar CLAUDE.md y los documentos de seguimiento antes de modificar código.

No almacenar archivos temporales de razonamiento interno.

---

# 19. Plan de implementación

Desarrollar el proyecto por etapas.

## Fase 1: infraestructura y simulador

- Crear estructura del repositorio.
- Configurar Docker Compose.
- Levantar Mosquitto.
- Levantar PostgreSQL.
- Crear simulador Python.
- Comprobar publicaciones MQTT.
- Documentar el procedimiento.

Resultado esperado: múltiples dispositivos virtuales publicando mensajes al broker.

## Fase 2: backend MQTT + Reactor

- Crear proyecto Spring Boot.
- Configurar cliente MQTT.
- Recibir mensajes.
- Convertir eventos a Flux.
- Implementar validación.
- Agregar métricas básicas.
- Crear pruebas con StepVerifier.

Resultado esperado: backend capaz de procesar flujos MQTT reactivos.

## Fase 3: persistencia reactiva

- Configurar R2DBC.
- Crear esquema PostgreSQL.
- Implementar repositorios.
- Persistir mediciones.
- Implementar consultas históricas.
- Gestionar errores.

Resultado esperado: mediciones almacenadas y consultables.

## Fase 4: WebSocket + React

- Crear frontend.
- Implementar WebSocket.
- Mostrar dispositivos.
- Mostrar mediciones.
- Crear gráficos.

Resultado esperado: dashboard en tiempo real.

## Fase 5: ESP32 físico

- Implementar firmware.
- Configurar Wi-Fi.
- Integrar sensor de temperatura.
- Publicar MQTT.
- Verificar integración.

Resultado esperado: el ESP32 real aparece en el mismo dashboard que los dispositivos simulados.

## Fase 6: laboratorio Reactor

- Métricas avanzadas.
- Control de concurrencia.
- Experimentos con flatMap.
- Comparación con concatMap.
- Backpressure.
- Pruebas de sobrecarga.

Resultado esperado: sistema que permite observar y comparar el comportamiento de los pipelines reactivos.

## Fase 7: estabilidad y documentación

- Ejecutar pruebas de integración.
- Analizar rendimiento.
- Revisar gestión de recursos.
- Corregir problemas.
- Documentar resultados.

---

# 20. Instrucciones de ejecución para Claude Code

Antes de comenzar:

1. Revisar el repositorio y detectar cualquier código existente.
2. Verificar las versiones de Java, Spring Boot, Reactor y las librerías necesarias.
3. Confirmar compatibilidad entre dependencias.
4. Definir la arquitectura inicial.
5. Crear CLAUDE.md y la documentación del harness.
6. Registrar el plan de desarrollo.
7. Comenzar por la fase 1.

No implementar todas las fases simultáneamente.

Completar cada fase, ejecutar sus pruebas y verificar su funcionamiento antes de avanzar.

No agregar Kafka, Kubernetes ni otros componentes complejos en la primera versión.

El sistema debe comenzar como un monolito modular con un simulador independiente.

La incorporación futura de Kafka, múltiples instancias del backend u otros componentes debe ser posible sin requerir una reescritura completa.

## Prioridad educativa

Este proyecto tiene un segundo objetivo, tan importante como su funcionamiento: aprender Project Reactor.

Por eso, cuando se implementen operadores reactivos relevantes, documentar:

- Qué hace el operador.
- Por qué se eligió.
- Qué alternativa existe.
- Cuándo se ejecuta.
- Cómo interactúa con los hilos.
- Cómo maneja errores.
- Cómo se comporta ante sobrecarga.

Agregar comentarios educativos concisos en las secciones más importantes.

No confundir ejecución concurrente con ejecución paralela.

Diferenciar claramente operadores síncronos, publishers asíncronos, operaciones bloqueantes y operaciones no bloqueantes.

El código debe ser productivo y las explicaciones deben mantenerse principalmente en docs/reactor-concepts.md.

## Primera tarea concreta

Comenzar con la fase 1:

**Crear el repositorio, configurar Mosquitto y PostgreSQL mediante Docker Compose y desarrollar el simulador Python de múltiples dispositivos ESP32.**

Primero verificar que 10, 100 y 1.000 dispositivos simulados puedan publicar correctamente sus mediciones MQTT.

Una vez validado, continuar con la integración Spring WebFlux y Reactor, siguiendo el plan y los criterios de aceptación documentados.