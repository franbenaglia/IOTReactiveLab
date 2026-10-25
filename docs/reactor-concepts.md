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
| `timeout` | ✅ | `TelemetryProcessor#withRetry` |
| `Flux.interval` + `concatMap` + `onBackpressureDrop` | ✅ | `DeviceRegistryFlusher` |
| `Mono.zip` | ✅ | `MetricsController` |
| `switchIfEmpty` | ✅ | `DeviceController` |
| `Hooks.onErrorDropped` | ✅ | `ReactorHooksConfig` |
| `block()` (dónde sí) | ✅ | `R2dbcPoolWarmup`, `DeviceRegistryFlusher#stop` |
| `switchMap` | ✅ | `TelemetryWebSocketHandler#handle` |
| `sample` | ✅ | `TelemetryWebSocketHandler#stream` (un dispositivo) |
| `window` + `collectMap` | ✅ | `TelemetryWebSocketHandler#stream` (varios) |
| `onBackpressureLatest` / `onBackpressureBuffer(DROP_OLDEST)` | ✅ | `TelemetryWebSocketHandler#stream` |
| prefetch de `merge` / `switchMap` | ✅ | `TelemetryWebSocketHandler` |
| `publish().autoConnect(n)` | ✅ | `TelemetryWebSocketHandler#handle` |
| `takeUntilOther` | ✅ | `TelemetryWebSocketHandler#handle` |
| `handle` | ✅ | `AlertService` |
| `doOnDiscard` | ✅ | `TelemetryWebSocketHandler#stream` |
| `concatMap` (vs `flatMap` en el pipeline) | ⏳ fase 6 | experimento 2 |
| `buffer` / `bufferTimeout` | ⏳ fase 6 | variante por lotes (ADR-013) |
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


---

## Fase 3 — persistencia

### `timeout(Duration)` — dónde: `TelemetryProcessor#withRetry`
- **Qué hace:** si el publisher no emite nada en el plazo, cancela la suscripción de arriba y emite `TimeoutException`.
- **Por qué:** una base **colgada** (congelada, red partida) no devuelve error: la escritura quedaría esperando para siempre y ocuparía una de las 20 ranuras de `flatMap`. Con 20 colgadas, el pipeline se detiene sin un solo error en el log.
- **Ubicación: antes de `retryWhen`.** Así el plazo es **por intento**: vence, `retryWhen` recibe el `TimeoutException` (transitorio) y vuelve a intentar con un plazo nuevo. Después de `retryWhen`, sería un plazo total para todos los intentos.
- **Alternativa:** el timeout del pool (`max-acquire-time`) cubre solo la espera por una conexión, no una consulta que se cuelga una vez obtenida la conexión. El `statement_timeout` de PostgreSQL lo aplica el servidor: no sirve si el servidor está congelado.
- **Hilos:** el plazo lo mide `Schedulers.parallel()`; el error se emite en ese hilo.
- **Efecto secundario:** al cancelar, la operación de abajo puede terminar con error más tarde y Reactor no tiene a quién entregarlo: ver `Hooks.onErrorDropped`.

### `Flux.interval` + `onBackpressureDrop` + `concatMap` — dónde: `DeviceRegistryFlusher`
- **Qué hace:** cada 5 s, un tick dispara un flush de `last_seen`. `concatMap` espera a que termine un flush antes de empezar el siguiente.
- **Por qué `concatMap` y no `flatMap`:** con la base lenta, `flatMap` lanzaría flushes superpuestos que pelean por las mismas filas. Aquí el orden y la exclusión mutua importan más que el throughput: es el caso de uso típico de `concatMap`.
- **Por qué `onBackpressureDrop`:** `interval` no puede frenar el tiempo. Si un flush tarda más que el período, `concatMap` no pide el siguiente tick e `interval` fallaría con `OverflowException`. Descartar ticks es correcto, porque el próximo flush escribe todo lo acumulado.
- **Hilos:** los ticks salen de `Schedulers.parallel()`; el flush continúa en el event loop de R2DBC.
- **Errores:** `onErrorResume` dentro de `concatMap`: un flush fallido (base caída) no termina el `interval`. Los valores se reponen para el siguiente.

### `Mono.zip` — dónde: `MetricsController#overview`
- **Qué hace:** se suscribe a varios `Mono` **a la vez** y emite una tupla cuando todos terminaron.
- **Por qué:** las dos consultas (conteos de dispositivos y de filas) son independientes. En secuencia (`a.flatMap(x -> b…)`) se sumarían sus latencias; con `zip`, cuenta solo la mayor.
- **Errores:** si una falla, `zip` cancela la otra y propaga el error.
- **Vacío:** si alguno completa vacío, `zip` completa vacío. Aquí las dos consultas siempre devuelven una fila.

### `switchIfEmpty` — dónde: `DeviceController`
- **Qué hace:** si el `Mono` completa sin valor (la consulta no encontró filas), se suscribe a la alternativa: aquí `Mono.error(404)`.
- **Cuándo se ejecuta:** el argumento se **arma** siempre (assembly), pero solo se **suscribe** si hace falta. Por eso `notFound()` devuelve un `Mono.error` y no lanza la excepción directamente.
- **Alternativa:** `defaultIfEmpty(valor)`; `Mono.defer` si armar la alternativa fuera costoso.

### `Hooks.onErrorDropped` — dónde: `ReactorHooksConfig`
- **Qué es:** un error emitido por un publisher cuyo suscriptor ya canceló. La regla de Reactive Streams prohíbe entregarlo, así que Reactor lo "descarta" y por defecto lo loguea como ERROR con stack trace.
- **Cuándo pasó aquí:** al arrancar con el pool frío, `timeout()` canceló una escritura que esperaba conexión; el pool venció su propia espera un instante después y emitió su error a nadie. La escritura ya se había reintentado: no era una falla.
- **Qué se hizo:** contarlo (`iotlab_reactor_errors_dropped_total`) y loguearlo en debug. Un valor que crece sin parar sí sería una señal a investigar.

### `block()` — dónde **sí** se usa
- **Regla del proyecto:** nunca en controladores ni en event loops (bloquear un event loop frena todas las conexiones que atiende).
- **Excepciones documentadas:** `R2dbcPoolWarmup#start` y `DeviceRegistryFlusher#stop` corren en el hilo de arranque/apagado de Spring, que no es un event loop. Ahí bloquear es la única forma de garantizar un orden: el pool listo antes de MQTT, el último flush antes de cerrar el pool.
- **En las pruebas** se usa `block()` para preparar datos, igual que `StepVerifier` bloquea para verificar.

---

## Fase 4 — WebSocket y alertas

### Anatomía de una conexión WebSocket en WebFlux
`handle(session)` devuelve un `Mono<Void>` que dura lo que dura la conexión.
Entrada y salida son dos flujos independientes: `session.receive()` (los
comandos del cliente) y `session.send(Flux)` (lo que se escribe). **Reactor
Netty pide elementos al Flux de salida a medida que puede escribir en el
socket**: un cliente lento genera menos demanda. Esa es la señal de
backpressure que el pipeline usa para descartar solo para ese cliente.

### `publish().autoConnect(3)` — dónde: `handle`
- **Qué hace:** convierte el Flux frío `receive()` en uno compartido. Se conecta a la fuente recién cuando hay 3 suscriptores (respuestas, datos y la señal de fin).
- **Por qué:** `receive()` solo admite **un** suscriptor. Con `share()` (que equivale a `autoConnect(1)`), el primer suscriptor dispararía la conexión y los otros dos podrían perderse el primer comando.

### `switchMap` — dónde: `handle`
- **Qué hace:** por cada `subscribe` arma un stream nuevo y **cancela el anterior**.
- **Por qué:** es exactamente la semántica del contrato ("un subscribe nuevo reemplaza al anterior"). `flatMap` mantendría los dos streams vivos (datos duplicados); `concatMap` nunca pasaría al segundo, porque el primero no termina.
- **Cancelación:** al cancelar el stream viejo se cancelan sus suscripciones al `RealtimeBus` y al de alertas; el sink deja de entregarle.

### `sample(Duration)` — dónde: `stream`, suscripción a un dispositivo
- **Qué hace:** cada intervalo emite **el último** elemento recibido en esa ventana y descarta los anteriores. Sin elementos nuevos, no emite.
- **Por qué no alcanza para muchos dispositivos:** sobre un Flux mezclado de 5.000 dispositivos, `sample` se quedaría con una medición de un único dispositivo por intervalo.
- **Hilos:** el reloj es `Schedulers.parallel()`; las emisiones salen de ese hilo.

### `window(Duration)` + `collectMap` — dónde: `stream`, varios dispositivos
- **Qué hace:** `window` corta el Flux en sub-Flux de tiempo fijo. `collectMap(deviceId)` reduce cada ventana a un mapa en el que **la última medición de cada dispositivo pisa a las anteriores**. Es la agregación del Pipeline B.
- **Memoria:** acotada a una entrada por dispositivo suscripto. Con `buffer(Duration)` + deduplicar, se guardarían todas las mediciones de la ventana.
- **`concatMap` sobre ventanas:** las ventanas son secuenciales; mantiene el orden y no las superpone.

### `onBackpressureLatest` + `doOnDiscard` — dónde: `stream`
- **Qué hace:** si abajo no hay demanda, conserva solo el lote más nuevo y descarta el que estaba esperando. `doOnDiscard`, ubicado **después**, cuenta los descartes (`iotlab_ws_dropped_total`): los operadores de arriba avisan de lo que descartan a través del contexto de la suscripción.
- **Alternativas:** `onBackpressureBuffer(n)` (atraso creciente, y error al llenarse); `onBackpressureDrop` (descarta el nuevo y conserva el viejo: lo contrario de lo que sirve en tiempo real).
- **Estados y alertas** usan `onBackpressureBuffer(256, …, DROP_OLDEST)`: son pocos y conviene no perderlos, pero tampoco pueden crecer sin límite.

### Prefetch: el hallazgo de la fase 4
`merge` y `switchMap` **piden por adelantado** a cada fuente (32 por
defecto), aunque el suscriptor de abajo no haya pedido nada. En
`PipelineBTest`, un cliente lento recibía el lote **más viejo**: había
quedado guardado en la cola interna de `merge`, y `onBackpressureLatest`
nunca vio falta de demanda. Con `Flux.merge(1, …)` y `switchMap(fn, 1)`, un
cliente lento retiene a lo sumo un lote viejo y después recibe siempre el más
nuevo. **Lección:** cada operador con cola interna es un buffer; el
backpressure "real" depende de cuánto pide cada uno por adelantado.

Límite que queda: Reactor Netty también tiene un buffer de escritura propio
por conexión. Un cliente muy lento acumula ahí algunos mensajes antes de que
el canal deje de ser escribible. Está acotado, pero no es cero. Medirlo es
parte del experimento 5.

### `takeUntilOther(commands.then())` — dónde: `handle`
- Cuando el cliente cierra, `receive()` completa, `then()` emite, y la salida se corta. Sin esto, `switchMap` seguiría vivo, porque el stream interno es infinito.

### `handle` — dónde: `AlertService`
- **Qué hace:** map + filter en un paso: por cada medición, `sink.next(alerta)` o nada.
- **Estado por dispositivo en un `HashMap` sin sincronizar:** es correcto porque Reactive Streams prohíbe llamar a `onNext` en paralelo (regla §1.3). El estado vive dentro de una única suscripción serializada. Si se agregara `parallel()`, dejaría de serlo.
