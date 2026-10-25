package com.example.iotlab.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Errores "descartados": un publisher emitió onError cuando su suscriptor ya había cancelado
 * (p. ej. el pool de conexiones vence su propia espera justo después de que nuestro timeout()
 * canceló la escritura). Reactor no tiene a quién entregarlo y por defecto lo loguea como ERROR
 * con stack trace. No es una falla del pipeline: la operación ya se había dado por fallida y se
 * reintentó. Se cuentan en una métrica y se loguean en debug.
 */
@Configuration
public class ReactorHooksConfig {

    private static final Logger log = LoggerFactory.getLogger("reactor.dropped");
    private final Counter dropped;

    public ReactorHooksConfig(MeterRegistry registry) {
        this.dropped = Counter.builder("iotlab.reactor.errors.dropped")
                .description("Errores emitidos después de una cancelación (Hooks.onErrorDropped)")
                .register(registry);
    }

    @PostConstruct
    void install() {
        Hooks.onErrorDropped(e -> {
            dropped.increment();
            log.debug("Error descartado tras cancelación: {}", e.toString());
        });
    }

    @PreDestroy
    void uninstall() {
        Hooks.resetOnErrorDropped();
    }
}
