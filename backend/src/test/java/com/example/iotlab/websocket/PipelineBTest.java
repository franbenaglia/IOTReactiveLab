package com.example.iotlab.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.iotlab.alerts.AlertProperties;
import com.example.iotlab.alerts.AlertService;
import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.reactive.LatestTelemetryCache;
import com.example.iotlab.reactive.RealtimeBus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** Pipeline B en tiempo virtual: agregación por intervalo, filtros y clientes lentos. */
class PipelineBTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineMetrics metrics = new PipelineMetrics(registry);
    final RealtimeBus bus = new RealtimeBus();
    final TelemetryWebSocketHandler handler = new TelemetryWebSocketHandler(bus, new LatestTelemetryCache(bus),
            new AlertService(bus, new AlertProperties(35, 1, 2), metrics), new WsCodec(JsonMapper.builder().build()), metrics);
    long seq;

    Telemetry t(String id, double temp) {
        return new Telemetry(id, temp, null, Instant.now(), ++seq, Source.SIMULATED, Instant.now());
    }

    static ClientCommand.Subscribe sub(Set<String> devices, long ms) {
        return new ClientCommand.Subscribe(devices, Duration.ofMillis(ms));
    }

    @SuppressWarnings("unchecked")
    static List<WsMessages.Item> items(Object msg) {
        assertThat(msg).isInstanceOf(WsMessages.Items.class);
        return ((WsMessages.Items) msg).items();
    }

    @Test
    void singleDeviceIsSampledToTheLatestReadingPerInterval() {
        StepVerifier.withVirtualTime(() -> handler.stream(sub(Set.of("dev-001"), 1000)))
                .expectNextMatches(m -> m instanceof WsMessages.Subscribed)
                .assertNext(m -> assertThat(items(m)).isEmpty())          // snapshot vacío
                .then(() -> {
                    bus.publish(t("dev-001", 20));
                    bus.publish(t("dev-002", 99));                         // otro dispositivo: filtrado
                    bus.publish(t("dev-001", 21));
                    bus.publish(t("dev-001", 22));
                })
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(m -> assertThat(items(m)).extracting(WsMessages.Item::temperature).containsExactly(22.0))
                .thenAwait(Duration.ofSeconds(3))                          // sin datos nuevos: nada
                .expectNoEvent(Duration.ofSeconds(1))
                .thenCancel()
                .verify();
    }

    @Test
    void manyDevicesGetTheLatestReadingOfEachDevicePerWindow() {
        StepVerifier.withVirtualTime(() -> handler.stream(sub(Set.of(), 2000)))
                .expectNextCount(2) // subscribed + snapshot
                .then(() -> {
                    bus.publish(t("dev-001", 20));
                    bus.publish(t("dev-002", 30));
                    bus.publish(t("dev-001", 21));
                    bus.publish(t("dev-003", 40));
                    bus.publish(t("dev-002", 31));
                })
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(m -> assertThat(items(m))
                        .extracting(WsMessages.Item::deviceId, WsMessages.Item::temperature)
                        .containsExactlyInAnyOrder(
                                org.assertj.core.groups.Tuple.tuple("dev-001", 21.0),
                                org.assertj.core.groups.Tuple.tuple("dev-002", 31.0),
                                org.assertj.core.groups.Tuple.tuple("dev-003", 40.0)))
                .thenCancel()
                .verify();
    }

    @Test
    void slowClientGetsOnlyTheNewestBatchAndDropsAreCounted() {
        // Demanda 2 (subscribed + snapshot), y después nada: el cliente no lee.
        StepVerifier.withVirtualTime(() -> handler.stream(sub(Set.of(), 1000)), 2)
                .expectNextCount(2)
                .then(() -> bus.publish(t("dev-001", 20)))
                .thenAwait(Duration.ofSeconds(1))
                .then(() -> bus.publish(t("dev-001", 21)))
                .thenAwait(Duration.ofSeconds(1))
                .then(() -> bus.publish(t("dev-001", 22)))
                .thenAwait(Duration.ofSeconds(1))
                .thenRequest(2)
                // El primer lote quedó retenido en el único lugar de prefetch de merge; el segundo
                // (21) se descartó; el tercero es el más nuevo. Nunca un atraso creciente.
                .assertNext(m -> assertThat(items(m)).extracting(WsMessages.Item::temperature).containsExactly(20.0))
                .assertNext(m -> assertThat(items(m)).extracting(WsMessages.Item::temperature).containsExactly(22.0))
                .thenCancel()
                .verify();
        assertThat(registry.get("iotlab.ws.dropped").counter().count()).isEqualTo(1);
    }

    @Test
    void statusEventsAreFilteredBySubscription() {
        StepVerifier.withVirtualTime(() -> handler.stream(sub(Set.of("dev-001"), 1000)))
                .expectNextCount(2)
                .then(() -> {
                    bus.publish(new com.example.iotlab.domain.DeviceStatus("dev-002", com.example.iotlab.domain.DeviceStatus.State.OFFLINE, null, Instant.now()));
                    bus.publish(new com.example.iotlab.domain.DeviceStatus("dev-001", com.example.iotlab.domain.DeviceStatus.State.OFFLINE, null, Instant.now()));
                })
                .assertNext(m -> assertThat(((WsMessages.Status) m).deviceId()).isEqualTo("dev-001"))
                .thenCancel()
                .verify();
    }
}
