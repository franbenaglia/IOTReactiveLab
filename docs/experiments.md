# Experimentos — Laboratorio Reactor

Fase 6 (main.md §13). Cada experimento es reproducible: escenario
configurado (sin recompilar), comando, condiciones y resultados.

## Plantilla

```markdown
### EXP-N.M — título — fecha
- **Pregunta:**
- **Configuración:** (concurrencia, operador, dispositivos, intervalo, modo simulador)
- **Comando(s):**
- **Máquina / condiciones:**
- **Resultados:** (throughput, latencia p50/p95/p99, operaciones en curso, memoria, errores)
- **Interpretación:**
- **Limitaciones:**
```

## Experimentos previstos

| ID | Tema | Variables | Métricas |
|---|---|---|---|
| EXP-1 | Concurrencia `flatMap` | 1, 5, 20, 50 | Throughput, latencia, en curso, memoria, errores |
| EXP-2 | `flatMap` vs `concatMap` | operador | Concurrencia, orden, throughput, latencia |
| EXP-3 | Backpressure | frecuencia creciente del simulador | Llegadas, capacidad, saturación de buffers, descartes, memoria |
| EXP-4 | Caída de PostgreSQL | detener / reanudar `postgres` | Errores, reintentos, recuperación, pérdida/duplicación |
| EXP-5 | Clientes WebSocket | N clientes | Conexiones, mensajes enviados, latencia, recursos |

_(Sin resultados todavía.)_
