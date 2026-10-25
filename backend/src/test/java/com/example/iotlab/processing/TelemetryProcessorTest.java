package com.example.iotlab.processing;

import static com.example.iotlab.support.Messages.inbound;
import static com.example.iotlab.support.Messages.telemetry;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.iotlab.config.PipelineProperties;
import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.mqtt.AckTracker;
import com.example.iotlab.mqtt.InboundMessage;
import com.example.iotlab.reactive.RealtimeTelemetryBus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class TelemetryProcessorTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineMetrics metrics = new PipelineMetrics(registry);
    final List<Long> acked = Collections.synchronizedList(new ArrayList<>());

    static PipelineProperties props(int concurrency) {
        return new PipelineProperties(concurrency, -40, 125, Duration.ofMinutes(5),
                Duration.ofMillis(100), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }

    TelemetryProcessor processor(TelemetryStore store, int concurrency) {
        TelemetryParser parser = new TelemetryParser(JsonMapper.builder().build(), props(concurrency),
                Clock.fixed(Instant.parse("2026-10-25T12:31:00Z"), ZoneOffset.UTC));
        return new TelemetryProcessor(parser, store, new SequenceTracker(), new RealtimeTelemetryBus(),
                acked::add, metrics, props(concurrency));
    }

    static TelemetryStore store(Function<Telemetry, Mono<StoreResult>> fn) {
        return new TelemetryStore() {
            @Override public Mono<StoreResult> save(Telemetry t) { return fn.apply(t); }
            @Override public Mono<Void> saveStatus(DeviceStatus s) { return Mono.empty(); }
        };
    }

    double count(String name, String... tags) {
        return registry.get(PipelineMetrics.PREFIX + name).tags(tags).counter().count();
    }

    @Test
    void invalidMessageDoesNotTerminateTheFlux() {
        InboundMessage ok1 = telemetry("dev-001", 20, 1);
        InboundMessage bad = inbound("iot/devices/dev-001/telemetry", "{no es json");
        InboundMessage ok2 = telemetry("dev-001", 21, 2);

        StepVerifier.create(processor(new NoOpTelemetryStore(), 1).process(Flux.just(ok1, bad, ok2)))
                .assertNext(o -> assertThat(o.type()).isEqualTo(Outcome.Type.PERSISTED))
                .assertNext(o -> {
                    assertThat(o.type()).isEqualTo(Outcome.Type.INVALID);
                    assertThat(o.reason()).isEqualTo(InvalidReason.MALFORMED_JSON);
                })
                .assertNext(o -> assertThat(o.type()).isEqualTo(Outcome.Type.PERSISTED))
                .verifyComplete();

        // Los tres se confirman, también el inválido: reentregarlo no lo arregla.
        assertThat(acked).containsExactly(ok1.ackToken(), bad.ackToken(), ok2.ackToken());
        assertThat(count("telemetry.invalid", "reason", "malformed_json")).isEqualTo(1);
        assertThat(count("telemetry.valid")).isEqualTo(2);
    }

    @Test
    void transientErrorIsRetriedWithBackoffAndAckedOnlyAfterSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        TelemetryStore flaky = store(t -> attempts.incrementAndGet() <= 3
                ? Mono.error(new RuntimeException("base caída"))
                : Mono.just(StoreResult.INSERTED));
        InboundMessage msg = telemetry("dev-001", 20, 1);

        // Tiempo virtual: el backoff (100 ms, 200 ms, 400 ms...) no espera de verdad.
        StepVerifier.withVirtualTime(() -> processor(flaky, 1).process(Flux.just(msg)))
                .expectSubscription()
                .then(() -> assertThat(acked).isEmpty()) // en reintento: sin confirmar
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(o -> assertThat(o.type()).isEqualTo(Outcome.Type.PERSISTED))
                .verifyComplete();

        assertThat(attempts).hasValue(4);
        assertThat(count("telemetry.store.retries")).isEqualTo(3);
        assertThat(acked).containsExactly(msg.ackToken());
    }

    @Test
    void nonTransientErrorBecomesFailedAndIsAckedSoItDoesNotBlockOthers() {
        TelemetryStore broken = new TelemetryStore() {
            @Override public Mono<StoreResult> save(Telemetry t) {
                return t.sequence() == 2 ? Mono.error(new IllegalArgumentException("dato imposible")) : Mono.just(StoreResult.INSERTED);
            }
            @Override public Mono<Void> saveStatus(DeviceStatus s) { return Mono.empty(); }
            @Override public boolean isTransient(Throwable e) { return !(e instanceof IllegalArgumentException); }
        };
        StepVerifier.create(processor(broken, 1).process(Flux.just(
                        telemetry("dev-001", 20, 1), telemetry("dev-001", 20, 2), telemetry("dev-001", 20, 3))))
                .expectNextMatches(o -> o.type() == Outcome.Type.PERSISTED)
                .expectNextMatches(o -> o.type() == Outcome.Type.FAILED)
                .expectNextMatches(o -> o.type() == Outcome.Type.PERSISTED)
                .verifyComplete();
        assertThat(acked).hasSize(3);
        assertThat(count("telemetry.stored", "result", "failed")).isEqualTo(1);
        assertThat(count("telemetry.store.retries")).isZero();
    }

    @Test
    void hungWriteTimesOutAndIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        // Primer intento: nunca responde (base colgada). Segundo: OK.
        TelemetryStore hung = store(t -> attempts.incrementAndGet() == 1 ? Mono.never() : Mono.just(StoreResult.INSERTED));
        StepVerifier.withVirtualTime(() -> processor(hung, 1).process(Flux.just(telemetry("dev-001", 20, 1))))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(4))
                .then(() -> assertThat(acked).isEmpty())   // antes del plazo (5 s): esperando
                .thenAwait(Duration.ofSeconds(2))          // vence el plazo → reintento
                .assertNext(o -> assertThat(o.type()).isEqualTo(Outcome.Type.PERSISTED))
                .verifyComplete();
        assertThat(attempts).hasValue(2);
        assertThat(count("telemetry.store.retries")).isEqualTo(1);
    }

    @Test
    void statusIsPersistedWithTheSameRetryPolicy() {
        AtomicInteger attempts = new AtomicInteger();
        TelemetryStore flakyStatus = new TelemetryStore() {
            @Override public Mono<StoreResult> save(Telemetry t) { return Mono.just(StoreResult.INSERTED); }
            @Override public Mono<Void> saveStatus(DeviceStatus s) {
                return attempts.incrementAndGet() == 1 ? Mono.error(new RuntimeException("caída")) : Mono.empty();
            }
        };
        InboundMessage status = inbound("iot/devices/dev-001/status", "{\"deviceId\":\"dev-001\",\"status\":\"offline\"}");
        StepVerifier.withVirtualTime(() -> processor(flakyStatus, 1).process(Flux.just(status)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(o -> assertThat(o.type()).isEqualTo(Outcome.Type.STATUS))
                .verifyComplete();
        assertThat(attempts).hasValue(2);
        assertThat(acked).containsExactly(status.ackToken());
    }

    @Test
    void duplicatesAreCountedAndAcked() {
        StepVerifier.create(processor(store(t -> Mono.just(StoreResult.DUPLICATE)), 1)
                        .process(Flux.just(telemetry("dev-001", 20, 1))))
                .expectNextMatches(o -> o.type() == Outcome.Type.DUPLICATE)
                .verifyComplete();
        assertThat(count("telemetry.stored", "result", "duplicate")).isEqualTo(1);
        assertThat(acked).hasSize(1);
    }

    @Test
    void flatMapNeverExceedsConfiguredConcurrency() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        TelemetryStore slow = store(t -> Mono.delay(Duration.ofMillis(20))
                .doOnSubscribe(s -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                .doOnTerminate(inFlight::decrementAndGet) // no doFinally: ver TelemetryProcessor
                .thenReturn(StoreResult.INSERTED));
        Flux<InboundMessage> input = Flux.range(1, 100).map(i -> telemetry("dev-001", 20, i));

        StepVerifier.create(processor(slow, 5).process(input))
                .expectNextCount(100)
                .verifyComplete();
        assertThat(maxInFlight).hasValue(5);
        assertThat(registry.get(PipelineMetrics.PREFIX + "telemetry.store.inflight").gauge().value()).isZero();
    }

    @Test
    void acksFollowArrivalOrderEvenWhenWritesCompleteOutOfOrder() {
        List<Integer> sentPacketIds = Collections.synchronizedList(new ArrayList<>());
        AckTracker tracker = new AckTracker((id, qos) -> sentPacketIds.add(id), new AckTracker.Listener() {
            @Override public void sent(int c) { }
            @Override public void failed(Exception e) { }
        });
        Random random = new Random(42);
        TelemetryStore jittery = store(t -> Mono.delay(Duration.ofMillis(random.nextInt(30))).thenReturn(StoreResult.INSERTED));
        TelemetryParser parser = new TelemetryParser(JsonMapper.builder().build(), props(10),
                Clock.fixed(Instant.parse("2026-10-25T12:31:00Z"), ZoneOffset.UTC));
        TelemetryProcessor p = new TelemetryProcessor(parser, jittery, new SequenceTracker(),
                new RealtimeTelemetryBus(), tracker::complete, metrics, props(10));

        List<Long> completionOrder = Collections.synchronizedList(new ArrayList<>());
        Flux<InboundMessage> input = Flux.range(1, 200).map(i -> {
            long token = tracker.register(i, 1); // como hace MqttSubscriber al recibir
            InboundMessage m = telemetry("dev-001", 20, i);
            return new InboundMessage(m.topic(), m.payload(), token, m.receivedAt(), m.receivedNanos());
        });
        StepVerifier.create(p.process(input).doOnNext(o -> completionOrder.add(o.ackToken())))
                .expectNextCount(200)
                .verifyComplete();

        List<Long> sorted = new ArrayList<>(completionOrder);
        Collections.sort(sorted);
        assertThat(completionOrder).isNotEqualTo(sorted);  // las escrituras terminaron desordenadas...
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= 200; i++) expected.add(i);
        assertThat(sentPacketIds).isEqualTo(expected);     // ...pero los PUBACK salieron en orden
        assertThat(tracker.pending()).isZero();
    }

    @Test
    void sequenceResetIsDetected() {
        StepVerifier.create(processor(new NoOpTelemetryStore(), 1).process(Flux.just(
                        telemetry("dev-001", 20, 50), telemetry("dev-001", 20, 51), telemetry("dev-001", 20, 1))))
                .expectNextCount(3)
                .verifyComplete();
        assertThat(count("telemetry.sequence.resets")).isEqualTo(1);
    }

    @Test
    void validTelemetryIsPublishedToRealtimeBus() {
        RealtimeTelemetryBus bus = new RealtimeTelemetryBus();
        TelemetryParser parser = new TelemetryParser(JsonMapper.builder().build(), props(1),
                Clock.fixed(Instant.parse("2026-10-25T12:31:00Z"), ZoneOffset.UTC));
        TelemetryProcessor p = new TelemetryProcessor(parser, new NoOpTelemetryStore(), new SequenceTracker(),
                bus, acked::add, metrics, props(1));
        List<Telemetry> seen = new ArrayList<>();
        bus.flux().subscribe(seen::add);
        StepVerifier.create(p.process(Flux.just(telemetry("dev-001", 20, 1), inbound("iot/devices/dev-001/telemetry", "x"))))
                .expectNextCount(2)
                .verifyComplete();
        assertThat(seen).hasSize(1); // el inválido no llega al tiempo real
    }
}
