package com.example.iotlab.persistence;

import com.example.iotlab.api.TelemetryView;
import com.example.iotlab.domain.Telemetry;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Acceso a {@code telemetry} con {@link DatabaseClient}: SQL explícito, porque la inserción
 * necesita {@code ON CONFLICT} (ADR-011) y las consultas usan el índice único en un orden
 * concreto. Todo devuelve publishers: nada se ejecuta hasta la suscripción, y nada bloquea.
 */
@Repository
public class TelemetryRepository {

    private final DatabaseClient db;

    public TelemetryRepository(DatabaseClient db) {
        this.db = db;
    }

    /** @return 1 si insertó, 0 si la clave (device_id, measured_at, sequence) ya existía */
    public Mono<Long> insert(Telemetry t) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO telemetry (device_id, temperature, humidity, measured_at, received_at, sequence)
                VALUES (:deviceId, :temperature, :humidity, :measuredAt, :receivedAt, :sequence)
                ON CONFLICT ON CONSTRAINT telemetry_dedup DO NOTHING
                """)
                .bind("deviceId", t.deviceId())
                .bind("temperature", t.temperature())
                .bind("measuredAt", t.measuredAt())
                .bind("receivedAt", t.receivedAt())
                .bind("sequence", t.sequence());
        spec = t.humidity() == null ? spec.bindNull("humidity", Double.class) : spec.bind("humidity", t.humidity());
        return spec.fetch().rowsUpdated();
    }

    public Mono<TelemetryView> latest(String deviceId) {
        return db.sql("""
                SELECT device_id, temperature, humidity, measured_at, received_at, sequence
                FROM telemetry WHERE device_id = :deviceId
                ORDER BY measured_at DESC LIMIT 1
                """)
                .bind("deviceId", deviceId)
                .map(TelemetryRepository::view)
                .one();
    }

    /** Página en orden descendente por measured_at, dentro de [from, to). */
    public Flux<TelemetryView> history(String deviceId, Instant from, Instant to, int limit, long offset) {
        return db.sql("""
                SELECT device_id, temperature, humidity, measured_at, received_at, sequence
                FROM telemetry
                WHERE device_id = :deviceId AND measured_at >= :from AND measured_at < :to
                ORDER BY measured_at DESC, sequence DESC
                LIMIT :limit OFFSET :offset
                """)
                .bind("deviceId", deviceId)
                .bind("from", from)
                .bind("to", to)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(TelemetryRepository::view)
                .all();
    }

    public Mono<Long> count() {
        return db.sql("SELECT count(*) AS n FROM telemetry").map(r -> r.get("n", Long.class)).one();
    }

    static TelemetryView view(Readable r) {
        return new TelemetryView(
                r.get("device_id", String.class),
                r.get("temperature", Double.class),
                r.get("humidity", Double.class),
                r.get("measured_at", Instant.class),
                r.get("received_at", Instant.class),
                r.get("sequence", Long.class));
    }
}
