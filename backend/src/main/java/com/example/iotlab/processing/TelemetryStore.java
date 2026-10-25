package com.example.iotlab.processing;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Telemetry;
import reactor.core.publisher.Mono;

/**
 * Puerto de persistencia del Pipeline A. Implementaciones: {@code R2dbcTelemetryStore} (fase 3) y
 * {@link NoOpTelemetryStore} (pruebas sin base).
 */
public interface TelemetryStore {

    /** Publisher asíncrono: no debe bloquear al suscribirse. */
    Mono<StoreResult> save(Telemetry telemetry);

    /** Estado online/offline del dispositivo. Idempotente. */
    Mono<Void> saveStatus(DeviceStatus status);

    /**
     * Si el error es transitorio (base caída, timeout), el pipeline reintenta sin confirmar el
     * mensaje. Si no lo es (un bug, un dato que la base nunca aceptará), reintentar bloquearía
     * para siempre las confirmaciones siguientes (AckTracker): se registra y se confirma.
     */
    default boolean isTransient(Throwable error) {
        return true;
    }
}
