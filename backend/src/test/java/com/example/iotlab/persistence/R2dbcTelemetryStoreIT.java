package com.example.iotlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.processing.StoreResult;
import com.example.iotlab.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

class R2dbcTelemetryStoreIT extends IntegrationTest {

    @Autowired R2dbcTelemetryStore store;
    @Autowired DeviceRepository devices;
    @Autowired DeviceRegistry registry;

    static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);

    static Telemetry t(String id, long seq, Instant measuredAt) {
        return new Telemetry(id, 21.5, null, measuredAt, seq, Source.SIMULATED, measuredAt.plusMillis(50));
    }

    @Test
    void insertsThenRejectsRedeliveryButAcceptsSequenceAfterReboot() {
        StepVerifier.create(store.save(t("store-001", 1, T0))).expectNext(StoreResult.INSERTED).verifyComplete();
        // Reentrega idéntica (ADR-011): misma clave.
        StepVerifier.create(store.save(t("store-001", 1, T0))).expectNext(StoreResult.DUPLICATE).verifyComplete();
        // El dispositivo reinició: sequence vuelve a 1, pero con otra hora.
        StepVerifier.create(store.save(t("store-001", 1, T0.plusSeconds(30)))).expectNext(StoreResult.INSERTED).verifyComplete();
        assertThat(rows("store-001")).isEqualTo(2);
        StepVerifier.create(devices.findById("store-001"))
                .assertNext(d -> assertThat(d.source()).isEqualTo("SIMULATED"))
                .verifyComplete();
    }

    @Test
    void statusUpsertIgnoresOlderStates() {
        Instant now = Instant.now();
        StepVerifier.create(store.saveStatus(new DeviceStatus("status-001", DeviceStatus.State.ONLINE, null, now))).verifyComplete();
        // Llega tarde un OFFLINE anterior (reconexión): no pisa al ONLINE más nuevo.
        StepVerifier.create(store.saveStatus(new DeviceStatus("status-001", DeviceStatus.State.OFFLINE, null, now.minusSeconds(5)))).verifyComplete();
        StepVerifier.create(devices.findById("status-001"))
                .assertNext(d -> {
                    assertThat(d.status()).isEqualTo("ONLINE");
                    assertThat(d.source()).isNull(); // todavía sin telemetría
                })
                .verifyComplete();
    }

    @Test
    void lastSeenIsWrittenInBatches() {
        StepVerifier.create(store.save(t("seen-001", 1, T0))).expectNextCount(1).verifyComplete();
        // El flusher corre cada 500 ms en las pruebas.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(devices.findById("seen-001").block().lastSeen()).isEqualTo(T0.plusMillis(50)));
    }

    @Test
    void foreignKeyViolationIsRecoveredByReRegisteringTheDevice() {
        StepVerifier.create(store.save(t("fk-001", 1, T0))).expectNextCount(1).verifyComplete();
        // Alguien borra el dispositivo (y su telemetría) por fuera: el registro en memoria lo
        // sigue creyendo dado de alta.
        db.sql("DELETE FROM telemetry WHERE device_id = 'fk-001'").then().block();
        db.sql("DELETE FROM devices WHERE device_id = 'fk-001'").then().block();

        StepVerifier.create(store.save(t("fk-001", 2, T0.plusSeconds(1))))
                .expectErrorSatisfies(e -> {
                    assertThat(R2dbcTelemetryStore.sqlState(e)).isEqualTo("23503");
                    assertThat(store.isTransient(e)).isTrue(); // el pipeline reintentará
                })
                .verify();
        // El reintento vuelve a dar de alta el dispositivo y guarda.
        StepVerifier.create(store.save(t("fk-001", 2, T0.plusSeconds(1)))).expectNext(StoreResult.INSERTED).verifyComplete();
    }

    @Test
    void humidityNullAndValueAreStored() {
        Telemetry withHumidity = new Telemetry("hum-001", 22.0, 41.5, T0, 1, Source.PHYSICAL, T0);
        StepVerifier.create(store.save(withHumidity)).expectNext(StoreResult.INSERTED).verifyComplete();
        Double h = db.sql("SELECT humidity FROM telemetry WHERE device_id = 'hum-001'")
                .map(r -> r.get("humidity", Double.class)).one().block();
        assertThat(h).isEqualTo(41.5);
    }
}
