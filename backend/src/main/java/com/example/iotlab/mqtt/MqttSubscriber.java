package com.example.iotlab.mqtt;

import com.example.iotlab.config.MqttProperties;
import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.processing.PipelineRunner;
import com.example.iotlab.reactive.MqttReactorAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Conexión MQTT del backend con Paho v5 (ADR-009): sesión persistente, {@code ReceiveMaximum} y
 * confirmación manual (ADR-010).
 *
 * <p>Paho entrega los mensajes en un único hilo propio ("MQTT Call"). {@link #messageArrived}
 * solo registra el mensaje en el {@link AckTracker} y lo pasa al Sink: no procesa, no bloquea y
 * nunca lanza (una excepción ahí hace que Paho cierre la conexión).
 *
 * <p>Reconexión: Paho reconecta solo después de una conexión exitosa. La primera conexión (con el
 * broker caído al arrancar) se reintenta acá, con backoff de Reactor.
 */
@Component
public class MqttSubscriber implements SmartLifecycle, MqttCallback, Acknowledger {

    public static final int PHASE = PipelineRunner.PHASE + 100;
    private static final Logger log = LoggerFactory.getLogger(MqttSubscriber.class);

    private final MqttProperties props;
    private final MqttReactorAdapter adapter;
    private final PipelineMetrics metrics;
    private final AckTracker tracker;
    private volatile MqttAsyncClient client;
    private volatile Disposable initialConnect;
    private volatile boolean running;

    public MqttSubscriber(MqttProperties props, MqttReactorAdapter adapter,
                          PipelineMetrics metrics) {
        this.props = props;
        this.adapter = adapter;
        this.metrics = metrics;
        this.tracker = new AckTracker(this::sendAck, new AckTracker.Listener() {
            @Override public void sent(int count) { metrics.acksSent(count); }
            @Override public void failed(Exception e) {
                metrics.ackFailed();
                log.debug("No se pudo enviar PUBACK: {}", e.toString());
            }
        });
        metrics.gauge("mqtt.acks.pending", "Mensajes recibidos aún sin PUBACK", tracker::pending);
        metrics.gauge("mqtt.sink.queued", "Mensajes en la cola del Sink esperando al pipeline", adapter::queued);
    }

    // ------------------------------------------------------------ ciclo de vida

    @Override
    public void start() {
        try {
            client = new MqttAsyncClient(props.serverUri(), props.clientId(), new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException("URI MQTT inválida: " + props.serverUri(), e);
        }
        client.setManualAcks(true);
        client.setCallback(this);
        running = true;
        // Mono.defer + retryWhen: cada reintento crea un CONNECT nuevo. subscribe() aquí es un
        // punto de arranque de larga duración, liberado en stop().
        initialConnect = Mono.defer(this::connect)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("No se pudo conectar a {} (intento {}): {}",
                                props.serverUri(), s.totalRetries() + 1, s.failure().getMessage())))
                .subscribe(v -> { }, e -> log.error("Conexión MQTT abandonada", e));
    }

    /** Puente callback → Mono: el token asíncrono de Paho se convierte en un Publisher. */
    private Mono<Void> connect() {
        return Mono.create(sink -> {
            try {
                client.connect(options(), null, new MqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { sink.success(); }
                    @Override public void onFailure(IMqttToken token, Throwable e) { sink.error(e); }
                });
            } catch (MqttException e) {
                sink.error(e);
            }
        });
    }

    private MqttConnectionOptions options() {
        MqttConnectionOptions o = new MqttConnectionOptions();
        o.setCleanStart(false); // sesión persistente: lo no confirmado sobrevive a una caída
        o.setSessionExpiryInterval(props.sessionExpirySeconds());
        o.setReceiveMaximum(props.receiveMaximum());
        o.setKeepAliveInterval(props.keepAliveSeconds());
        o.setConnectionTimeout(props.connectTimeoutSeconds());
        o.setAutomaticReconnect(true);
        o.setAutomaticReconnectDelay(1, 30);
        if (!props.password().isBlank()) {
            o.setUserName(props.username());
            o.setPassword(props.password().getBytes(StandardCharsets.UTF_8));
        }
        return o;
    }

    @Override
    public void stop() {
        running = false;
        if (initialConnect != null) {
            initialConnect.dispose();
        }
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(5000).waitForCompletion(6000);
            }
        } catch (MqttException e) {
            log.warn("Desconexión MQTT con error: {}", e.toString());
        } finally {
            metrics.connectionLost();
            discardPendingAcks();
            try {
                client.close(true);
            } catch (MqttException e) {
                log.debug("close: {}", e.toString());
            }
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return PHASE; }

    // ------------------------------------------------------------ callbacks de Paho

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT {} a {} (receiveMaximum={}, sesión persistente {} s)",
                reconnect ? "reconectado" : "conectado", serverURI, props.receiveMaximum(), props.sessionExpirySeconds());
        // Con sesión persistente la suscripción sobrevive, pero suscribirse de nuevo es
        // idempotente y cubre el primer arranque y una sesión expirada. "Listo" (health UP)
        // recién con el SUBACK: antes, lo que se publique no le llega a esta sesión.
        //
        // retainHandling=1 (MQTT 5): los retained de status se envían solo si la suscripción
        // es nueva. Sin esto, cada reconexión volvería a recibir el último status de TODOS los
        // dispositivos conocidos (5.000 en las pruebas de carga).
        org.eclipse.paho.mqttv5.common.MqttSubscription[] subs = props.topics().stream().map(t -> {
            org.eclipse.paho.mqttv5.common.MqttSubscription sub = new org.eclipse.paho.mqttv5.common.MqttSubscription(t, 1);
            sub.setRetainHandling(1);
            return sub;
        }).toArray(org.eclipse.paho.mqttv5.common.MqttSubscription[]::new);
        try {
            client.subscribe(subs, null, new MqttActionListener() {
                @Override public void onSuccess(IMqttToken token) {
                    int[] granted = token.getReasonCodes();
                    boolean ok = granted != null && java.util.Arrays.stream(granted).allMatch(rc -> rc <= 2);
                    if (ok) {
                        log.info("Suscripto a {} (QoS {})", props.topics(), java.util.Arrays.toString(granted));
                        metrics.connected();
                    } else {
                        log.error("El broker rechazó la suscripción a {}: códigos {}", props.topics(),
                                java.util.Arrays.toString(granted));
                    }
                }
                @Override public void onFailure(IMqttToken token, Throwable e) {
                    log.error("No se pudo suscribir a {}", props.topics(), e);
                }
            }, new org.eclipse.paho.mqttv5.common.packet.MqttProperties());
        } catch (MqttException e) {
            log.error("No se pudo suscribir a {}", props.topics(), e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            long token = tracker.register(message.getId(), message.getQos());
            InboundMessage in = new InboundMessage(topic, message.getPayload(), token, Instant.now(), System.nanoTime());
            if (!adapter.emit(in)) {
                // No debería ocurrir (ver MqttReactorAdapter). Se confirma igual: si no, este
                // mensaje bloquearía para siempre las confirmaciones siguientes.
                metrics.sinkDropped();
                tracker.complete(token);
            }
        } catch (RuntimeException e) {
            log.error("Error inesperado recibiendo de {}", topic, e);
        }
    }

    @Override
    public void disconnected(MqttDisconnectResponse response) {
        log.warn("MQTT desconectado: {}", response);
        metrics.connectionLost();
        discardPendingAcks();
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.error("Error MQTT", exception);
    }

    @Override public void deliveryComplete(IMqttToken token) { }
    @Override public void authPacketArrived(int reasonCode, org.eclipse.paho.mqttv5.common.packet.MqttProperties properties) { }

    // ------------------------------------------------------------ confirmaciones

    @Override
    public void acknowledge(long ackToken) {
        tracker.complete(ackToken);
    }

    private void sendAck(int messageId, int qos) throws MqttException {
        client.messageArrivedComplete(messageId, qos);
    }

    private void discardPendingAcks() {
        int discarded = tracker.reset();
        if (discarded > 0) {
            metrics.acksDiscarded(discarded);
            log.info("{} mensajes sin confirmar quedan para reentrega del broker", discarded);
        }
    }

    /** Conectado y con la suscripción confirmada por el broker. */
    public boolean isConnected() {
        return client != null && client.isConnected() && metrics.isConnected();
    }
}
