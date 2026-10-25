package com.example.iotlab.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.iotlab.support.IntegrationTest;
import com.example.iotlab.support.Messages;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cliente WebSocket real contra la aplicación, con datos que entran por MQTT. */
class WebSocketIT extends IntegrationTest {

    @Value("${local.server.port}") int port;
    final JsonMapper json = JsonMapper.builder().build();
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final Sinks.Many<String> toServer = Sinks.many().unicast().onBackpressureBuffer();
    Disposable connection;
    MqttAsyncClient pub;

    @BeforeEach
    void connect() throws Exception {
        connection = new ReactorNettyWebSocketClient()
                .execute(URI.create("ws://localhost:" + port + "/ws/telemetry"), session ->
                        session.send(toServer.asFlux().map(session::textMessage))
                                .and(session.receive().map(m -> json.readTree(m.getPayloadAsText())).doOnNext(received::add)))
                .subscribe();
        await().atMost(Duration.ofSeconds(5)).until(() -> gauge("ws.clients") >= 1);
        pub = publisher("it-ws-sim");
    }

    @AfterEach
    void close() throws Exception {
        connection.dispose();
        pub.disconnect().waitForCompletion(5000);
    }

    void send(String cmd) {
        toServer.tryEmitNext(cmd);
    }

    List<JsonNode> ofType(String type) {
        return received.stream().filter(n -> type.equals(n.get("type").stringValue())).toList();
    }

    void telemetry(String id, double temp, long seq) throws Exception {
        publish(pub, "iot/devices/" + id + "/telemetry", Messages.telemetryJson(id, temp, seq, now()));
    }

    @Test
    void subscribeReceivesAckSnapshotAndSampledTelemetryOfOnlyThatDevice() throws Exception {
        send("{\"type\":\"subscribe\",\"devices\":[\"ws-001\"],\"intervalMs\":250}");
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("snapshot").size() == 1);
        assertThat(ofType("subscribed").get(0).get("intervalMs").longValue()).isEqualTo(250);

        telemetry("ws-001", 20.5, 1);
        telemetry("ws-999", 99, 1);   // no suscripto
        await().atMost(Duration.ofSeconds(5)).until(() -> !ofType("telemetry").isEmpty());
        JsonNode item = ofType("telemetry").get(0).get("items").get(0);
        assertThat(item.get("deviceId").stringValue()).isEqualTo("ws-001");
        assertThat(item.get("temperature").doubleValue()).isEqualTo(20.5);
        assertThat(received).noneMatch(n -> n.toString().contains("ws-999"));
    }

    @Test
    void invalidCommandAnswersErrorAndKeepsTheConnection() {
        send("esto no es json");
        send("{\"type\":\"ping\"}");
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("pong").size() == 1);
        assertThat(ofType("error")).hasSize(1);
    }

    @Test
    void newSubscribeReplacesThePreviousOne() throws Exception {
        send("{\"type\":\"subscribe\",\"devices\":[\"ws-101\"],\"intervalMs\":250}");
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("snapshot").size() == 1);
        send("{\"type\":\"subscribe\",\"devices\":[\"ws-102\"],\"intervalMs\":250}");
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("snapshot").size() == 2);
        telemetry("ws-101", 20, 1);
        telemetry("ws-102", 21, 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !ofType("telemetry").isEmpty());
        Thread.sleep(600);
        assertThat(ofType("telemetry")).allMatch(n -> n.get("items").get(0).get("deviceId").stringValue().equals("ws-102"));
    }

    @Test
    void alertIsRaisedOnceWithDebounceAndClearedWithHysteresis() throws Exception {
        WebTestClient web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        send("{\"type\":\"subscribe\",\"devices\":[\"ws-hot\"],\"intervalMs\":250}");
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("snapshot").size() == 1);

        telemetry("ws-hot", 36, 1);   // 1 de 2: no alcanza
        telemetry("ws-hot", 37, 2);   // dispara
        telemetry("ws-hot", 38, 3);   // ya activa: no repite
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("alert").size() == 1);
        assertThat(ofType("alert").get(0).get("state").stringValue()).isEqualTo("RAISED");
        web.get().uri("/api/alerts").exchange().expectStatus().isOk()
                .expectBody(String.class).value(b -> assertThat(b).contains("ws-hot"));

        telemetry("ws-hot", 34.5, 4); // dentro de la histéresis: sigue activa
        telemetry("ws-hot", 33, 5);   // resuelve
        await().atMost(Duration.ofSeconds(5)).until(() -> ofType("alert").size() == 2);
        assertThat(ofType("alert").get(1).get("state").stringValue()).isEqualTo("CLEARED");
        web.get().uri("/api/alerts").exchange().expectBody(String.class).value(b -> assertThat(b).doesNotContain("ws-hot"));
    }

    @Test
    void monitorEndpointExposesRealInstrumentation() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/metrics/monitor").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.gauges.wsClients").isEqualTo(1.0)
                .jsonPath("$.gauges.r2dbcAcquired").exists()
                .jsonPath("$.gauges.jvmHeapUsed").exists()
                .jsonPath("$.counters.mqttReceived").exists();
    }
}
