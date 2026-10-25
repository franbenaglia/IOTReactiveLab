package com.example.iotlab.persistence;

import com.example.iotlab.processing.PipelineRunner;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Crea las conexiones iniciales del pool ANTES de conectar a MQTT.
 *
 * <p>Al arrancar, el broker entrega de golpe lo acumulado en la sesión. Con el pool frío,
 * flatMap pide 20 conexiones a la vez, la mayoría hay que crearlas en ese momento, y algunas
 * esperas superaban max-acquire-time (3 s). Se recuperaban con reintentos, pero se puede evitar.
 * Con initial-size = concurrencia del pipeline y este warmup, las conexiones ya existen.
 *
 * <p>Bloquea, a propósito, en el hilo de arranque de Spring (no es un event loop).
 */
@Component
public class R2dbcPoolWarmup implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(R2dbcPoolWarmup.class);

    private final ConnectionFactory connectionFactory;
    private volatile boolean running;

    public R2dbcPoolWarmup(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void start() {
        running = true;
        if (connectionFactory instanceof ConnectionPool pool) {
            try {
                Integer created = pool.warmup().block(Duration.ofSeconds(15));
                log.info("Pool R2DBC precalentado: {} conexiones", created);
            } catch (RuntimeException e) {
                // Base caída al arrancar: no impide arrancar; el pipeline reintentará.
                log.warn("No se pudo precalentar el pool R2DBC: {}", e.toString());
            }
        }
    }

    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }

    /** Antes que el pipeline y, por lo tanto, antes que MQTT. */
    @Override public int getPhase() { return PipelineRunner.PHASE - 200; }
}
