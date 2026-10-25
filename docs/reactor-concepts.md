# Conceptos de Reactor

Documento educativo del proyecto. Cada operador o mecanismo usado en el código
tiene aquí una entrada con la plantilla de abajo, y un enlace al lugar donde se
usa. Se completa a medida que se implementan las fases 2-6.

## Distinciones base

| Concepto | Significado en este proyecto |
|---|---|
| Concurrente vs paralelo | Concurrente: varias operaciones *en curso* a la vez (p. ej. 20 `save` esperando a PostgreSQL), aunque un solo hilo las atienda. Paralelo: varias ejecutándose *simultáneamente* en hilos distintos. `flatMap(…, 20)` da concurrencia; no implica paralelismo. |
| Operador síncrono | Transforma en el hilo que emite (`map`, `filter`). No debe bloquear. |
| Publisher asíncrono | Emite más tarde, en otro hilo o evento (`repository.save()`, `Mono.delay`). |
| Bloqueante | Retiene el hilo esperando I/O (JDBC, `Thread.sleep`, `block()`). Prohibido en event loops; si es inevitable, `subscribeOn(Schedulers.boundedElastic())` justificado. |
| No bloqueante | Registra interés y libera el hilo (R2DBC, Netty, Paho async). |
| Assembly vs subscription | Declarar el pipeline no ejecuta nada; ejecuta al suscribirse. |
| Hot vs cold | El flujo MQTT es *hot*: los eventos existen haya o no suscriptores. Por eso el backpressure no puede frenar al broker por sí solo. |

## Plantilla por operador

```markdown
### `operador` — dónde: `Clase#metodo` (fase N)
- **Qué hace:**
- **Por qué se eligió:**
- **Alternativa:**
- **Cuándo se ejecuta:** (assembly / subscription / por elemento)
- **Hilos:**
- **Errores:**
- **Sobrecarga:**
```

## Operadores a documentar (main.md §7)

`map` · `filter` · `flatMap` · `concatMap` · `buffer` · `bufferTimeout` ·
`sample` · `onErrorResume` · `retryWhen` · `doOnNext` · `doOnError` ·
`Sinks.Many` (y `EmitFailureHandler`)

_(Entradas pendientes: se agregan al implementarlos.)_
