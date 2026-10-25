package com.example.iotlab.reactive;

import com.example.iotlab.config.MqttProperties;
import com.example.iotlab.mqtt.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Frontera callback → Reactor: convierte los mensajes que entrega Paho en un {@link Flux}.
 * Ver docs/reactor-concepts.md, "Sinks.Many".
 *
 * <p><b>Por qué unicast con cola acotada.</b> El único consumidor es el Pipeline A. Unicast
 * guarda los mensajes mientras ese consumidor no los pide (o todavía no se suscribió) en una cola
 * de tamaño fijo. La cola no puede desbordar por construcción: el broker nunca entrega más de
 * {@code receiveMaximum} mensajes sin confirmar, y uno solo se confirma cuando salió del pipeline
 * (ADR-010). Se dimensiona al doble como margen.
 *
 * <p><b>Hilos.</b> {@link #emit} se llama solo desde el hilo de callback de Paho: un único
 * productor, que es lo que exige la cola SPSC de {@link Queues#get(int)} y lo que evita
 * {@code FAIL_NON_SERIALIZED}.
 */
@Component
public class MqttReactorAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttReactorAdapter.class);

    private final Sinks.Many<InboundMessage> sink;
    private final int capacity;

    public MqttReactorAdapter(MqttProperties props) {
        // Queues.get(n) redondea a potencia de 2 con un mínimo de 8: se replica para informar
        // la capacidad real.
        this.capacity = Math.max(8, Queues.ceilingNextPowerOfTwo(props.receiveMaximum() * 2));
        this.sink = Sinks.many().unicast().onBackpressureBuffer(Queues.<InboundMessage>get(capacity).get());
    }

    /**
     * Entrega un mensaje al pipeline. Devuelve false si el Sink lo rechazó: el llamador decide qué
     * hacer con su confirmación. Nunca lanza excepciones: una excepción en el callback de Paho
     * cierra la conexión MQTT.
     */
    public boolean emit(InboundMessage message) {
        Sinks.EmitResult result = sink.tryEmitNext(message);
        if (result.isSuccess()) {
            return true;
        }
        // FAIL_OVERFLOW: cola llena (no debería pasar, ver javadoc de la clase). Ojo: con la cola
        // llena y todavía sin suscriptor, Reactor informa FAIL_ZERO_SUBSCRIBER.
        // FAIL_NON_SERIALIZED: dos hilos emitiendo a la vez (no debería pasar: un solo hilo).
        // FAIL_TERMINATED / FAIL_CANCELLED: el pipeline terminó (apagado o un bug).
        log.error("Sink rechazó el mensaje de {}: {}", message.topic(), result);
        return false;
    }

    /** El Flux del Pipeline A. Unicast: admite un solo suscriptor. */
    public Flux<InboundMessage> messages() {
        return sink.asFlux();
    }

    /** Señal de fin para el apagado: el pipeline procesa lo que tiene en cola y completa. */
    public void complete() {
        sink.tryEmitComplete();
    }

    public int capacity() {
        return capacity;
    }

    public int queued() {
        return sink.scanOrDefault(reactor.core.Scannable.Attr.BUFFERED, 0);
    }
}
