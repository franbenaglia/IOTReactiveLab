# IoT Reactive Lab — guía para Claude Code

Laboratorio para aprender Project Reactor: dispositivos ESP32 (uno físico y
muchos simulados) publican telemetría por MQTT (Mosquitto); un backend Spring
WebFlux la procesa con pipelines Reactor, la persiste en PostgreSQL vía R2DBC y
la distribuye por WebSocket a un dashboard React.

**La especificación completa y fuente de verdad es [main.md](main.md).** Este
archivo no la repite: dice cómo trabajar sobre ella sin perder contexto.

---

## 1. Protocolo de inicio de sesión (obligatorio)

Antes de modificar código, en este orden:

1. Leer [docs/progress.md](docs/progress.md): fase actual, última tarea, próximo paso, bloqueos.
2. Leer [docs/decisions.md](docs/decisions.md): decisiones vigentes (ADR). No contradecirlas sin registrar una nueva.
3. Leer el documento específico de la tarea:
   - contrato MQTT → [docs/mqtt-contract.md](docs/mqtt-contract.md)
   - contrato WebSocket → [docs/websocket-contract.md](docs/websocket-contract.md)
   - arquitectura → [docs/architecture.md](docs/architecture.md)
   - plan y criterios de aceptación → [docs/development-plan.md](docs/development-plan.md)
   - ESP32 / hardware → [docs/hardware-esp32.md](docs/hardware-esp32.md)
   - pruebas → [docs/testing.md](docs/testing.md)
4. `git status` y `git log --oneline -10` para ver si quedó trabajo sin commitear.

El hook `SessionStart` ([.claude/hooks/session-start.sh](.claude/hooks/session-start.sh))
inyecta el estado actual de `progress.md` y de git al abrir la sesión, pero no
reemplaza leer los documentos de la tarea.

## 2. Protocolo de cierre de etapa (obligatorio)

Al terminar una etapa relevante (no cada edición):

1. Actualizar `docs/progress.md` (sección *Estado actual* + entrada en el registro).
2. Registrar decisiones nuevas en `docs/decisions.md` (formato ADR).
3. Actualizar `docs/architecture.md` si cambió algún componente o frontera.
4. Anotar problemas pendientes en `progress.md` → *Pendientes / bloqueos*.
5. Registrar pruebas ejecutadas y resultados en `docs/testing.md`.
6. Si la etapa está validada (criterios de aceptación cumplidos): commit Git.

Si una sesión se corta a mitad de etapa, dejar al menos *Estado actual* de
`progress.md` actualizado con qué quedó a medias.

## 3. Reglas del proyecto

- **Una fase por vez.** No empezar la fase N+1 sin cumplir los criterios de aceptación de la N (ver `development-plan.md`).
- **Sin** Kafka, Kubernetes, Spring MVC, JPA/Hibernate en esta versión. Monolito modular + simulador independiente.
- **Sin `block()`** en controladores ni en event loops. Sin `subscribe()` sueltos en servicios: las suscripciones de larga duración tienen un único punto de arranque documentado y se liberan al detener la app.
- **Métricas reales.** Nunca fabricar valores para el dashboard. Sin `deviceId` como tag de Micrometer (alta cardinalidad).
- **Secretos fuera del repo.** `secrets.ini` (firmware), `.env` (compose), `passwd` de Mosquitto: en `.gitignore`, con su `*.example` versionado.
- **Contratos primero.** MQTT (`docs/mqtt-contract.md`, único para físico y simulado) y WebSocket (`docs/websocket-contract.md`): cualquier cambio se hace primero en el documento y luego en productores y consumidores (los tipos de `frontend/src/api/types.ts` incluidos).
- **Educativo:** cada operador Reactor relevante se documenta en `docs/reactor-concepts.md` (qué hace, por qué, alternativa, cuándo se ejecuta, hilos, errores, sobrecarga). En el código, comentarios breves; la explicación larga va al doc.
- No guardar archivos temporales de razonamiento en el repo.
- Idioma: documentación y comentarios en español; identificadores de código en inglés.

## 4. Estrategia de delegación (agentes)

Los archivos de [.claude/agents/](.claude/agents/) **no se ejecutan solos**. El
agente principal es el **orquestador** y decide explícitamente cuándo delegar:

| Situación | Agente | Qué recibe en el prompt |
|---|---|---|
| Decisión de diseño con impacto en varios componentes | `architect` | Problema, opciones, ADRs relacionados |
| Código Java/Reactor/MQTT/R2DBC | `backend-developer` | Tarea concreta, criterios de aceptación, docs a leer |
| Código React/TS | `frontend-developer` | Ídem |
| Firmware ESP32 o simulador Python | `iot-developer` | Ídem + `hardware-esp32.md` si es firmware |
| Escribir/ejecutar pruebas, validar criterios de aceptación | `qa-reviewer` | Fase y criterios a validar |
| Antes de cerrar una etapa | `consistency-reviewer` | Diff o lista de archivos tocados |

Flujo por tarea:

1. Orquestador elige la tarea desde `development-plan.md` y la anota como *en curso* en `progress.md`.
2. Si hay una decisión abierta → `architect` propone, el orquestador registra el ADR.
3. Developer correspondiente implementa (tareas chicas puede hacerlas el orquestador directamente).
4. `qa-reviewer` ejecuta las pruebas y reporta evidencia (comandos + salida).
5. `consistency-reviewer` revisa contra ADRs, contrato y reglas de este archivo.
6. Orquestador aplica el protocolo de cierre (sección 2).

Los subagentes **no** actualizan `progress.md` ni hacen commits: devuelven un
informe y el orquestador consolida. Así hay un solo escritor del estado.

## 5. Estructura del repositorio (objetivo)

```text
main.md                     especificación (no editar salvo pedido explícito)
CLAUDE.md                   este archivo
.claude/agents/             definiciones de subagentes
.claude/hooks/              hooks del harness
docs/                       seguimiento y documentación técnica
infra/mosquitto/            configuración del broker
docker-compose.yml          Mosquitto, PostgreSQL, backend, frontend
simulator/                  simulador Python (fase 1)
backend/                    Spring Boot WebFlux (fases 2-3)
frontend/                   React + Vite (fase 4)
firmware/esp32-temperature/ PlatformIO (fase 5)
scripts/load/               escenarios de carga reproducibles
```

## 6. Entorno local verificado

Ver `docs/progress.md` → *Entorno* (versiones de herramientas detectadas y
faltantes). Comandos frecuentes:

```bash
docker compose up -d mosquitto postgres     # infraestructura
cd firmware/esp32-temperature && pio run -t upload && pio device monitor
ip -4 addr show | grep -oP 'inet \K[\d.]+'  # IP LAN para el ESP32 (no 127.x ni 172.x)
```
