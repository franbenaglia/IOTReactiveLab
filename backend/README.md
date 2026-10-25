# Backend — IoT Reactive Lab

Spring Boot 4.1 · WebFlux · Project Reactor · Paho MQTT 5 · Java 17.
Arquitectura: [docs/architecture.md](../docs/architecture.md). Operadores
Reactor explicados: [docs/reactor-concepts.md](../docs/reactor-concepts.md).

## Ejecutar

```bash
docker compose up -d mosquitto        # desde la raíz, con .env completo
cd backend
mvn spring-boot:run                   # o: mvn package && java -jar target/iotlab-backend-0.1.0-SNAPSHOT.jar
```

La contraseña MQTT (`MQTT_BACKEND_PASSWORD`) se lee del `.env` de la raíz
(`spring.config.import`) o de una variable de entorno. Hay que ejecutarlo
desde `backend/`, porque la ruta `../.env` es relativa.

Maven usa `.mvn/settings.xml` (Maven Central), no `~/.m2/settings.xml` (ADR-012).

## Observar

```bash
curl localhost:8080/actuator/health                          # mqtt UP = suscripto
curl -s localhost:8080/actuator/prometheus | grep ^iotlab_   # métricas del pipeline
curl localhost:8080/actuator/metrics/iotlab.pipeline.latency
```

## Configuración (`application.yml`)

| Propiedad | Por defecto | Efecto |
|---|---|---|
| `iotlab.mqtt.server-uri` | `tcp://localhost:1883` | Broker (`MQTT_SERVER_URI`) |
| `iotlab.mqtt.receive-maximum` | 100 | Mensajes sin confirmar que acepta el backend: la perilla de backpressure |
| `iotlab.mqtt.session-expiry-seconds` | 3600 | Cuánto guarda el broker la sesión con el backend caído |
| `iotlab.pipeline.concurrency` | 20 | Escrituras en curso en `flatMap` |
| `iotlab.pipeline.temperature-min/max` | -40 / 125 | Rango de validación |
| `iotlab.pipeline.max-future-skew` | 5m | Tolerancia para timestamps adelantados |
| `iotlab.pipeline.retry-min/max-backoff` | 100ms / 10s | Reintentos de persistencia |

## Pruebas

```bash
mvn test      # unitarias
mvn verify    # + integración con Testcontainers (necesita Docker)
```
