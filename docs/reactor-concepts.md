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

Estado: ✅ documentado · ⏳ pendiente (fase en la que se usa).

| Operador | Estado | Dónde |
|---|---|---|
| `Sinks.Many` unicast / multicast | ✅ | `MqttReactorAdapter`, `RealtimeTelemetryBus` |
| `Mono.create` / `Mono.defer` | ✅ | `MqttSubscriber#connect`, `TelemetryProcessor#persist` |
| `map` | ✅ | `TelemetryProcessor#process` |
| `doOnNext` | ✅ | `TelemetryProcessor#process` |
| `flatMap` | ✅ | `TelemetryProcessor#process` |
| `retryWhen` | ✅ | `TelemetryProcessor#persist`, `MqttSubscriber#start` |
| `onErrorResume` | ✅ | `TelemetryProcessor#persist` |
| `doOnError` | ✅ | `TelemetryProcessor#process` |
| `doFinally` vs `doOnTerminate` | ✅ | `TelemetryProcessor#persist`, `PipelineRunner` |
| `concatMap` | ⏳ fase 6 | experimento 2 |
| `buffer` / `bufferTimeout` | ⏳ fase 3 | inserción por lotes |
| `sample` | ⏳ fase 4 | Pipeline B |

---

## El Pipeline A completo (fase 2)

```java
messages                                        // Flux<InboundMessage> desde Sinks.Many (hot)
    .map(parser::parse)                         // síncrono, CPU
    .doOnNext(this::observe)                    // métricas, secuencia, tiempo real
    .flatMap(this::handle, concurrency)         // hasta N escrituras en curso
    .doOnNext(this::complete)                   // PUBACK en orden (AckTracker) + métricas
    .doOnError(...)                             // defensa: no debería ocurrir
```

Lectura de hilos, de punta a punta. Paho entrega en su hilo "MQTT Call" y
`tryEmitNext` empuja el elemento por `map` → `doOnNext` → `flatMap` **en ese
mismo hilo**, si hay demanda. `flatMap` se suscribe a `store.save()`. Cuando la
escritura termina (en la fase 3, en un event loop de R2DBC), el resultado sigue
por `doOnNext(complete)` **en ese otro hilo**, y `flatMap` pide un elemento más:
si había mensajes en la cola del Sink, el siguiente `map` corre ahora en el
event loop. Reactor no cambia de hilo por sí solo; solo `publishOn`/`subscribeOn`
lo hacen, y aquí no se usan porque ningún paso bloquea.

---

### `Sinks.Many` — dónde: `MqttReactorAdapter`, `RealtimeTelemetryBus` (fase 2)
- **Qué hace:** un publisher al que se le empujan elementos de forma imperativa (`tryEmitNext`). Es el puente entre un callback (Paho) y un `Flux`.
- **Por qué se eligió:** Paho ya entrega mensajes por callback; no hay un publisher que envolver. Hay dos sinks con propósitos distintos:
  - `unicast().onBackpressureBuffer(cola acotada)` para la persistencia: un solo suscriptor, y guarda los mensajes mientras ese suscriptor no los pide.
  - `multicast().directBestEffort()` para el tiempo real: N suscriptores, y quien no tiene demanda pierde el elemento.
- **Alternativa:** `Flux.create(sink -> ...)` (un `FluxSink` atado a un suscriptor; más incómodo de compartir con un componente de ciclo de vida propio); `Flux.push`; `multicast().onBackpressureBuffer()`, donde un suscriptor lento haría crecer un buffer compartido.
- **Cuándo se ejecuta:** el sink existe desde la construcción. Unicast acumula hasta que alguien se suscribe; multicast descarta si no hay nadie (`FAIL_ZERO_SUBSCRIBER`).
- **Hilos:** `tryEmitNext` **no es thread-safe para varios productores simultáneos** (`FAIL_NON_SERIALIZED`). Aquí hay un único productor (el hilo de Paho), y por eso la cola puede ser SPSC.
- **Errores:** `tryEmitNext` nunca lanza; devuelve un `EmitResult`. `MqttReactorAdapter#emit` lo traduce a `boolean` y el llamador decide. `emitNext(…, handler)` reintentaría con un `EmitFailureHandler`; no hace falta con un solo productor.
- **Sobrecarga:** la cola es acotada (`Queues.get(n)`, potencia de 2 con **mínimo 8**). No desborda por construcción: el broker entrega como máximo `receiveMaximum` mensajes sin confirmar. Si desbordara, el resultado sería `FAIL_OVERFLOW`, o `FAIL_ZERO_SUBSCRIBER` si además nadie se suscribió todavía (comprobado en `MqttReactorAdapterTest`).

### `Mono.create` y `Mono.defer` — dónde: `MqttSubscriber#connect`, `TelemetryProcessor#persist`
- **Qué hacen:** `create` adapta una API de callback de un solo resultado (el `IMqttToken` de Paho) a un `Mono`. `defer` posterga la creación del publisher hasta la suscripción.
- **Por qué:** `retryWhen` reintenta **resuscribiéndose**. Con `Mono.defer(this::connect)`, cada reintento ejecuta `client.connect()` de nuevo. Sin `defer`, un `Mono` armado una sola vez podría reproducir el mismo resultado viejo.
- **Alternativa:** `Mono.fromFuture` si la API devolviera `CompletableFuture`; un `ScheduledExecutorService` con reintentos a mano.
- **Cuándo se ejecuta:** en cada suscripción (assembly ≠ ejecución).
- **Hilos:** el `sink.success()` de `create` llega en el hilo de Paho.
- **Errores:** `sink.error(e)` propaga al `retryWhen`.
- **Sobrecarga:** n/a.

### `map` — dónde: `TelemetryProcessor#process` (fase 2)
- **Qué hace:** transforma cada `InboundMessage` en un `ParseResult` (JSON → validación → normalización), uno a uno, de forma síncrona.
- **Por qué se eligió:** la transformación es CPU pura (unos microsegundos) y nunca falla: `TelemetryParser` convierte todo error en `ParseResult.invalid`.
- **Alternativa:** `handle` (map + filter en uno), si se quisiera descartar los inválidos; aquí no se descartan porque también hay que confirmarlos. `flatMap` sería innecesario: no hay nada asíncrono.
- **Cuándo se ejecuta:** por elemento, en el hilo que emite el elemento (ver la lectura de hilos más arriba).
- **Hilos:** puede correr en un event loop. Por eso el parser no debe bloquear: ni I/O ni locks largos.
- **Errores:** una excepción dentro de `map` terminaría el `Flux` con `onError`. Como el Sink unicast no admite una segunda suscripción, el pipeline quedaría muerto hasta reiniciar: por eso el parser no lanza.
- **Sobrecarga:** sin buffer propio; respeta la demanda de abajo.

### `doOnNext` — dónde: `TelemetryProcessor#process` (dos veces)
- **Qué hace:** ejecuta un efecto lateral sin modificar el elemento.
- **Por qué:** dos usos con tiempos distintos. (1) **Antes** de `flatMap`, en orden de llegada: métricas, detección de reinicio de `sequence` y publicación al tiempo real. (2) **Después** de `flatMap`, en orden de terminación: confirmación al broker.
- **Alternativa:** `tap()` con un `SignalListener` (más estructurado, para métricas); hacerlo dentro de `map`, que mezcla transformación y efecto.
- **Cuándo / hilos:** por elemento, en el hilo de la señal.
- **Errores:** una excepción en el efecto se convierte en `onError` del flujo. Los efectos usados aquí no lanzan.
- **Sobrecarga:** n/a; un efecto lento frena todo el flujo, porque corre en la cadena.

### `flatMap(fn, concurrency)` — dónde: `TelemetryProcessor#process` (fase 2)
- **Qué hace:** por cada elemento se suscribe al `Mono` de `store.save()` y fusiona los resultados, con **como máximo `concurrency` suscripciones internas activas**.
- **Por qué se eligió:** las escrituras son I/O no bloqueante. Tener 20 en curso a la vez multiplica el throughput sin 20 hilos. El límite (`iotlab.pipeline.concurrency`) protege a la base y debe ser ≤ al pool R2DBC (fase 3).
- **Alternativa:** `concatMap` (una a la vez, orden preservado: experimento 2); `flatMapSequential` (concurrente, pero reemite en el orden original, con buffer); `parallel().runOn()` (paralelismo de CPU, innecesario aquí).
- **Cuándo se ejecuta:** pide `concurrency` elementos al empezar y uno más cada vez que termina una interna.
- **Hilos:** concurrente, **no paralelo**. Con `NoOpTelemetryStore` todo ocurre en el hilo de Paho. Con R2DBC, las internas terminan en event loops.
- **Errores:** un `onError` interno terminaría el flujo entero. Por eso cada `Mono` interno termina siempre en un `Outcome` (`retryWhen` + `onErrorResume`).
- **Sobrecarga:** es el punto donde se origina el backpressure. Con `concurrency` internas en curso, deja de pedir; el Sink acumula; los mensajes sin confirmar llenan `receiveMaximum`; el broker deja de entregar. **Orden:** los resultados salen en orden de terminación. `TelemetryProcessorTest#acksFollowArrivalOrder…` muestra escrituras desordenadas con PUBACK en orden, gracias a `AckTracker`.

### `retryWhen(Retry.backoff(...))` — dónde: `TelemetryProcessor#persist`, `MqttSubscriber#start`
- **Qué hace:** ante `onError`, se vuelve a suscribir al publisher de arriba según una política. Aquí: espera exponencial con jitter (100 ms, 200 ms, … hasta `retryMaxBackoff`), intentos ilimitados y `filter(store::isTransient)`.
- **Por qué se eligió:** ADR-010. Un mensaje no se confirma hasta que se guarda; ante una caída de la base se insiste, y mientras tanto la presión llega al broker. El filtro evita reintentar para siempre algo que nunca va a funcionar, lo que bloquearía todas las confirmaciones siguientes.
- **Alternativa:** `retry(n)` (inmediato, sin espera: martilla a la base caída); `Retry.fixedDelay`; un circuit breaker (Resilience4j), si hiciera falta cortar antes.
- **Cuándo se ejecuta:** al recibir `onError`. El backoff usa `Schedulers.parallel()` para esperar, y en las pruebas se reemplaza con `StepVerifier.withVirtualTime`.
- **Hilos:** el reintento ocurre en un hilo de `parallel`.
- **Errores:** si el filtro rechaza el error, o se agotan los intentos, propaga `onError` (aquí lo captura `onErrorResume`).
- **Sobrecarga:** cada mensaje en reintento ocupa una de las `concurrency` ranuras de `flatMap`. Con la base caída, las 20 se ocupan y el pipeline se detiene ordenadamente.

### `onErrorResume` — dónde: `TelemetryProcessor#persist`
- **Qué hace:** reemplaza un `onError` por otro publisher (aquí `Mono.just(Outcome FAILED)`).
- **Por qué:** aísla el error por elemento: una medición imposible de guardar no termina el `Flux` principal.
- **Alternativa:** `onErrorReturn` (valor fijo, sin log); `onErrorContinue` (desaconsejado: depende de soporte de cada operador y su semántica sorprende).
- **Cuándo / hilos:** al llegar el error, en el hilo del error.
- **Sobrecarga:** n/a.

### `doOnError` — dónde: `TelemetryProcessor#process`
- **Qué hace:** observa un `onError` sin capturarlo.
- **Por qué:** vigilancia. Por diseño el flujo principal no debería terminar nunca con error; si pasa, que quede en el log y no sea silencioso.
- **Alternativa:** `onErrorResume` en el flujo principal: inútil, porque el Sink unicast no admite resuscripción.

### `doFinally` vs `doOnTerminate` — dónde: `TelemetryProcessor#persist`, `PipelineRunner`
- **Qué hacen:** los dos corren al terminar. `doOnTerminate` corre **antes** de propagar `onComplete`/`onError` hacia abajo. `doFinally` corre **después**, e incluye la cancelación.
- **Hallazgo de la fase 2:** el gauge de escrituras en curso con `doFinally` mostraba `concurrency + 1`. Al propagarse el `onComplete`, `flatMap` ya se había suscrito a la escritura siguiente antes de que `doFinally` restara (`TelemetryProcessorTest#flatMapNeverExceedsConfiguredConcurrency`). Se usa `doOnTerminate` + `doOnCancel`.
- **`doFinally` sí es lo correcto** cuando importa cualquier forma de terminar y el orden no: `PipelineRunner` lo usa para liberar el latch de apagado.

