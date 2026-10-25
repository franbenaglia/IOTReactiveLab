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

Notebook de desarrollo, Ubuntu 22.04, kernel 6.8. _(Completar CPU / RAM al
ejecutar la primera prueba de carga: `nproc`, `free -h`.)_

## Registro

| Fecha | Fase | Prueba / criterio | Comando | Resultado | Notas |
|---|---|---|---|---|---|
| — | — | _sin pruebas todavía_ | — | — | — |
