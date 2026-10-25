package com.example.iotlab.persistence;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.processing.StoreResult;
import com.example.iotlab.processing.TelemetryStore;
import io.r2dbc.spi.R2dbcException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@link TelemetryStore} sobre PostgreSQL con R2DBC (fase 3). Reemplaza a NoOpTelemetryStore.
 *
 * <p>Clasificación de errores para {@code retryWhen} ({@link #isTransient}), por SQLSTATE:
 * los de datos (clase 22), integridad (23) y sintaxis/esquema (42) no se arreglan reintentando,
 * excepto la violación de FK (23503): significa que el dispositivo no existe (p. ej. se vació la
 * tabla) y se resuelve olvidándolo y dándolo de alta otra vez. Todo lo demás (conexión
 * rechazada, pool agotado, timeouts) se considera transitorio: la base volverá.
 */
@Component
public class R2dbcTelemetryStore implements TelemetryStore {

    static final String FK_VIOLATION = "23503";

    private final TelemetryRepository telemetry;
    private final DeviceRepository devices;
    private final DeviceRegistry registry;

    public R2dbcTelemetryStore(TelemetryRepository telemetry, DeviceRepository devices, DeviceRegistry registry) {
        this.telemetry = telemetry;
        this.devices = devices;
        this.registry = registry;
    }

    @Override
    public Mono<StoreResult> save(Telemetry t) {
        return registry.ensure(t)
                .then(telemetry.insert(t))
                .map(rows -> rows == 1 ? StoreResult.INSERTED : StoreResult.DUPLICATE)
                .doOnSuccess(r -> registry.touch(t.deviceId(), t.receivedAt()))
                .doOnError(e -> {
                    if (FK_VIOLATION.equals(sqlState(e))) {
                        registry.forget(t.deviceId());
                    }
                });
    }

    @Override
    public Mono<Void> saveStatus(DeviceStatus status) {
        return devices.upsertStatus(status);
    }

    @Override
    public boolean isTransient(Throwable error) {
        String state = sqlState(error);
        if (state == null) {
            return true; // sin SQLSTATE: conexión, pool, timeout
        }
        if (FK_VIOLATION.equals(state)) {
            return true;
        }
        return !(state.startsWith("22") || state.startsWith("23") || state.startsWith("42"));
    }

    /** SQLSTATE del primer R2dbcException en la cadena de causas (Spring los envuelve). */
    static String sqlState(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof R2dbcException r && r.getSqlState() != null) {
                return r.getSqlState();
            }
        }
        return null;
    }
}
