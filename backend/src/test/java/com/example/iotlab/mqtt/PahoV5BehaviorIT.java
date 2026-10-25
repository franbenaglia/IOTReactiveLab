package com.example.iotlab.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.iotlab.support.MosquittoContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Valida los supuestos de ADR-009 / ADR-010 sobre Paho mqttv5 1.2.5 + Mosquitto:
 * (a) con confirmación manual, el broker nunca entrega más de ReceiveMaximum
 *     mensajes sin confirmar;
 * (b) lo no confirmado se reentrega al reconectar con sesión persistente,
 *     en el orden original (y con DUP=false: ver la aserción).
 * Si esta prueba falla, ADR-009 se revierte a MQTT 3.1.1.
 */
@Testcontainers
class PahoV5BehaviorIT {

    private static final String TOPIC = "iot/devices/it-001/telemetry";
    private static final int RECEIVE_MAXIMUM = 5;
    private static final int TOTAL = 20;

    @Container
    static final MosquittoContainer broker = new MosquittoContainer();

    record Received(int id, String payload, boolean duplicate) {}

    static class Collector implements MqttCallback {
        final List<Received> messages = new CopyOnWriteArrayList<>();

        @Override public void messageArrived(String topic, MqttMessage m) {
            messages.add(new Received(m.getId(), new String(m.getPayload(), StandardCharsets.UTF_8), m.isDuplicate()));
        }
        @Override public void disconnected(MqttDisconnectResponse r) {}
        @Override public void mqttErrorOccurred(MqttException e) {}
        @Override public void deliveryComplete(IMqttToken t) {}
        @Override public void connectComplete(boolean reconnect, String uri) {}
        @Override public void authPacketArrived(int reasonCode, MqttProperties p) {}
    }

    private MqttAsyncClient subscriber(Collector collector) throws MqttException {
        MqttAsyncClient c = new MqttAsyncClient(broker.serverUri(), "it-backend", new MemoryPersistence());
        c.setManualAcks(true);
        c.setCallback(collector);
        MqttConnectionOptions o = new MqttConnectionOptions();
        o.setCleanStart(false);
        o.setSessionExpiryInterval(300L);
        o.setReceiveMaximum(RECEIVE_MAXIMUM);
        c.connect(o).waitForCompletion(5000);
        c.subscribe(TOPIC, 1).waitForCompletion(5000);
        return c;
    }

    @Test
    void receiveMaximumLimitsUnackedDeliveriesAndUnackedAreRedeliveredOnReconnect() throws Exception {
        Collector first = new Collector();
        MqttAsyncClient sub = subscriber(first);

        MqttAsyncClient pub = new MqttAsyncClient(broker.serverUri(), "it-publisher", new MemoryPersistence());
        pub.connect().waitForCompletion(5000);
        for (int i = 1; i <= TOTAL; i++) {
            pub.publish(TOPIC, ("m" + i).getBytes(StandardCharsets.UTF_8), 1, false).waitForCompletion(5000);
        }

        // (a) Solo RECEIVE_MAXIMUM entregas sin confirmar, y se mantiene.
        await().atMost(Duration.ofSeconds(5)).until(() -> first.messages.size() == RECEIVE_MAXIMUM);
        Thread.sleep(1000);
        assertThat(first.messages).hasSize(RECEIVE_MAXIMUM);

        // Confirmar uno libera exactamente una ranura.
        Received m1 = first.messages.get(0);
        assertThat(m1.payload()).isEqualTo("m1");
        sub.messageArrivedComplete(m1.id(), 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.messages.size() == RECEIVE_MAXIMUM + 1);
        Thread.sleep(500);
        assertThat(first.messages).hasSize(RECEIVE_MAXIMUM + 1);

        // (b) Desconectar sin confirmar m2..m6 y reconectar con la misma sesión.
        sub.disconnect().waitForCompletion(5000);
        sub.close();
        Collector second = new Collector();
        MqttAsyncClient sub2 = subscriber(second);
        await().atMost(Duration.ofSeconds(5)).until(() -> second.messages.size() >= RECEIVE_MAXIMUM);
        assertThat(second.messages.subList(0, RECEIVE_MAXIMUM))
                .extracting(Received::payload)
                .containsExactly("m2", "m3", "m4", "m5", "m6");
        // Hallazgo: Mosquitto 2.0.22 + Paho v5 reentregan con DUP=false. El flag
        // DUP no sirve para detectar reentregas: la deduplicación es de la base
        // (ADR-011). Si un día cambia, esta aserción avisa.
        assertThat(second.messages.subList(0, RECEIVE_MAXIMUM)).noneMatch(Received::duplicate);

        // Confirmando todo, llega el resto en orden y nada se pierde.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            for (Received r : second.messages) {
                try { sub2.messageArrivedComplete(r.id(), 1); } catch (MqttException ignored) { }
            }
            return second.messages.stream().map(Received::payload).distinct().count() == TOTAL - 1;
        });
        sub2.disconnect().waitForCompletion(5000);
        pub.disconnect().waitForCompletion(5000);
    }
}
