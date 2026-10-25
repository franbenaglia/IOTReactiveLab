package com.example.iotlab.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WsCodecTest {

    final WsCodec codec = new WsCodec(JsonMapper.builder().build());

    @Test
    void parsesSubscribeToListAndToAll() {
        var list = (ClientCommand.Subscribe) codec.parse("{\"type\":\"subscribe\",\"devices\":[\"dev-001\",\"dev-002\"],\"intervalMs\":500}");
        assertThat(list.devices()).containsExactlyInAnyOrder("dev-001", "dev-002");
        assertThat(list.interval()).isEqualTo(Duration.ofMillis(500));
        assertThat(list.matches("dev-001")).isTrue();
        assertThat(list.matches("dev-003")).isFalse();

        var all = (ClientCommand.Subscribe) codec.parse("{\"type\":\"subscribe\",\"devices\":\"*\"}");
        assertThat(all.all()).isTrue();
        assertThat(all.interval()).isEqualTo(Duration.ofSeconds(1)); // por defecto
        assertThat(all.matches("cualquiera")).isTrue();
    }

    @Test
    void parsesPing() {
        assertThat(codec.parse("{\"type\":\"ping\"}")).isInstanceOf(ClientCommand.Ping.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "no es json",
        "[1]",
        "{\"devices\":\"*\"}",
        "{\"type\":\"bailar\"}",
        "{\"type\":\"subscribe\"}",
        "{\"type\":\"subscribe\",\"devices\":[]}",
        "{\"type\":\"subscribe\",\"devices\":[\"MAYUS\"]}",
        "{\"type\":\"subscribe\",\"devices\":\"*\",\"intervalMs\":10}",
        "{\"type\":\"subscribe\",\"devices\":\"*\",\"intervalMs\":\"1000\"}",
    })
    void invalidCommandsBecomeInvalidNotExceptions(String text) {
        assertThat(codec.parse(text)).isInstanceOf(ClientCommand.Invalid.class);
    }

    @Test
    void writesTypeFirst() {
        assertThat(codec.write(WsMessages.Pong.INSTANCE)).isEqualTo("{\"type\":\"pong\"}");
    }
}
