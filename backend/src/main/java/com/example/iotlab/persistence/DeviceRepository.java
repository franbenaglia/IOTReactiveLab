package com.example.iotlab.persistence;

import com.example.iotlab.api.DeviceView;
import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Source;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Acceso a {@code devices}. Todas las escrituras son upserts idempotentes. */
@Repository
public class DeviceRepository {

    private static final String SELECT_VIEW = """
            SELECT d.device_id, d.source, d.status, d.status_changed_at, d.last_seen, d.created_at,
                   t.temperature AS last_temperature, t.humidity AS last_humidity, t.measured_at AS last_measured_at
            FROM devices d
            LEFT JOIN LATERAL (
                SELECT temperature, humidity, measured_at FROM telemetry
                WHERE device_id = d.device_id ORDER BY measured_at DESC LIMIT 1
            ) t ON true
            """;

    private final DatabaseClient db;

    public DeviceRepository(DatabaseClient db) {
        this.db = db;
    }

    /** Alta del dispositivo al ver su primera telemetría; completa {@code source} si faltaba. */
    public Mono<Void> ensure(String deviceId, Source source) {
        return db.sql("""
                INSERT INTO devices (device_id, source) VALUES (:deviceId, :source)
                ON CONFLICT (device_id) DO UPDATE SET source = EXCLUDED.source
                WHERE devices.source IS NULL
                """)
                .bind("deviceId", deviceId)
                .bind("source", source.name())
                .then();
    }

    /**
     * Estado online/offline. La condición del UPDATE descarta estados más viejos que el
     * guardado (llegan desordenados tras reconexiones): se ordena por hora de recepción.
     */
    public Mono<Void> upsertStatus(DeviceStatus s) {
        return db.sql("""
                INSERT INTO devices (device_id, status, status_changed_at)
                VALUES (:deviceId, :status, :changedAt)
                ON CONFLICT (device_id) DO UPDATE
                SET status = EXCLUDED.status, status_changed_at = EXCLUDED.status_changed_at
                WHERE devices.status_changed_at IS NULL OR devices.status_changed_at <= EXCLUDED.status_changed_at
                """)
                .bind("deviceId", s.deviceId())
                .bind("status", s.state().name())
                .bind("changedAt", s.receivedAt())
                .then();
    }

    /** Actualiza last_seen de muchos dispositivos en una sola sentencia (arrays + unnest). */
    public Mono<Long> touchAll(String[] deviceIds, Instant[] seenAt) {
        return db.sql("""
                UPDATE devices d SET last_seen = GREATEST(d.last_seen, u.seen_at)
                FROM unnest(:ids::varchar[], :seen::timestamptz[]) AS u(device_id, seen_at)
                WHERE d.device_id = u.device_id
                """)
                .bind("ids", deviceIds)
                .bind("seen", seenAt)
                .fetch().rowsUpdated();
    }

    public Flux<DeviceView> findAll() {
        return db.sql(SELECT_VIEW + " ORDER BY d.device_id").map(DeviceRepository::view).all();
    }

    public Mono<DeviceView> findById(String deviceId) {
        return db.sql(SELECT_VIEW + " WHERE d.device_id = :deviceId")
                .bind("deviceId", deviceId)
                .map(DeviceRepository::view)
                .one();
    }

    public Mono<DeviceCounts> counts() {
        return db.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'ONLINE') AS online,
                       count(*) FILTER (WHERE status = 'OFFLINE') AS offline
                FROM devices
                """)
                .map(r -> new DeviceCounts(r.get("total", Long.class), r.get("online", Long.class), r.get("offline", Long.class)))
                .one();
    }

    public record DeviceCounts(long total, long online, long offline) {}

    static DeviceView view(Readable r) {
        return new DeviceView(
                r.get("device_id", String.class),
                r.get("source", String.class),
                r.get("status", String.class),
                r.get("status_changed_at", Instant.class),
                r.get("last_seen", Instant.class),
                r.get("created_at", Instant.class),
                r.get("last_temperature", Double.class),
                r.get("last_humidity", Double.class),
                r.get("last_measured_at", Instant.class));
    }
}
