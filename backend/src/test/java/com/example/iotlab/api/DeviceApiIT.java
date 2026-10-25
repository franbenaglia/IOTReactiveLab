package com.example.iotlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.persistence.R2dbcTelemetryStore;
import com.example.iotlab.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class DeviceApiIT extends IntegrationTest {

    @Autowired R2dbcTelemetryStore store;
    @Value("${local.server.port}") int port;
    WebTestClient web;

    static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);
    static boolean seeded;

    @BeforeEach
    void setUp() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        if (!seeded) {
            // api-001: 250 mediciones, una por segundo, la última a BASE + 249 s.
            Flux.range(0, 250)
                    .concatMap(i -> store.save(new Telemetry("api-001", 20 + i % 10, 40.0, BASE.plusSeconds(i), i,
                            Source.PHYSICAL, BASE.plusSeconds(i))))
                    .then(store.saveStatus(new DeviceStatus("api-001", DeviceStatus.State.ONLINE, null, Instant.now())))
                    .block();
            seeded = true;
        }
    }

    @Test
    void listsDevicesWithTheirLatestReading() {
        web.get().uri("/api/devices").exchange()
                .expectStatus().isOk()
                .expectBodyList(DeviceView.class)
                .value(list -> assertThat(list).anySatisfy(d -> {
                    assertThat(d.deviceId()).isEqualTo("api-001");
                    assertThat(d.status()).isEqualTo("ONLINE");
                    assertThat(d.source()).isEqualTo("PHYSICAL");
                    assertThat(d.lastMeasuredAt()).isEqualTo(BASE.plusSeconds(249));
                }));
    }

    @Test
    void getsOneDeviceAndItsLatest() {
        web.get().uri("/api/devices/api-001").exchange().expectStatus().isOk()
                .expectBody(DeviceView.class).value(d -> assertThat(d.deviceId()).isEqualTo("api-001"));
        web.get().uri("/api/devices/api-001/latest").exchange().expectStatus().isOk()
                .expectBody(TelemetryView.class).value(t -> {
                    assertThat(t.sequence()).isEqualTo(249);
                    assertThat(t.humidity()).isEqualTo(40.0);
                });
    }

    @Test
    void unknownDeviceIs404AndInvalidIdIs400() {
        web.get().uri("/api/devices/no-existe").exchange().expectStatus().isNotFound();
        web.get().uri("/api/devices/no-existe/latest").exchange().expectStatus().isNotFound();
        web.get().uri("/api/devices/MAYUSCULAS").exchange().expectStatus().isBadRequest();
    }

    @Test
    void historyIsPaginatedAndFilteredByTime() {
        String from = BASE.plusSeconds(100).toString();
        String to = BASE.plusSeconds(200).toString(); // [100, 200): 100 filas
        web.get().uri(u -> u.path("/api/devices/api-001/history").queryParam("from", from).queryParam("to", to)
                        .queryParam("size", 60).build())
                .exchange().expectStatus().isOk()
                .expectBody(HistoryPage.class).value(p -> {
                    assertThat(p.items()).hasSize(60);
                    assertThat(p.hasMore()).isTrue();
                    assertThat(p.items().get(0).sequence()).isEqualTo(199); // descendente
                });
        web.get().uri(u -> u.path("/api/devices/api-001/history").queryParam("from", from).queryParam("to", to)
                        .queryParam("size", 60).queryParam("page", 1).build())
                .exchange().expectStatus().isOk()
                .expectBody(HistoryPage.class).value(p -> {
                    assertThat(p.items()).hasSize(40);
                    assertThat(p.hasMore()).isFalse();
                    assertThat(p.items().get(39).sequence()).isEqualTo(100);
                });
    }

    @Test
    void historyRejectsBadParameters() {
        web.get().uri("/api/devices/api-001/history?size=5000").exchange().expectStatus().isBadRequest();
        web.get().uri("/api/devices/api-001/history?from=ayer").exchange().expectStatus().isBadRequest();
        web.get().uri("/api/devices/api-001/history?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void overviewCombinesMetricsAndDatabaseCounts() {
        web.get().uri("/api/metrics/overview").exchange().expectStatus().isOk()
                .expectBody(MetricsOverview.class).value(o -> {
                    assertThat(o.devicesTotal()).isPositive();
                    assertThat(o.devicesOnline()).isPositive();
                    assertThat(o.telemetryRows()).isGreaterThanOrEqualTo(250);
                });
    }
}
