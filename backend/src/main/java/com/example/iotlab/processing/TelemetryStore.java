package com.example.iotlab.processing;

import com.example.iotlab.domain.Telemetry;
import reactor.core.publisher.Mono;

/**
 * Persistencia de mediciones. La implementación R2DBC llega en la fase 3; hasta entonces, {@link
 * NoOpTelemetryStore}.
 */
public interface TelemetryStore {

    /** Publisher asíncrono: no debe bloquear al suscribirse. */
    Mono<StoreResult> save(Telemetry telemetry);

    /**
     * Si el error es transitorio (base caída, timeout), el pipeline reintenta sin confirmar el
     * mensaje. Si no lo es (un bug, un dato que la base nunca aceptará), reintentar bloquearía
     * para siempre las confirmaciones siguientes (AckTracker): se registra y se confirma.
     */
    default boolean isTransient(Throwable error) {
        return true;
    }
}
