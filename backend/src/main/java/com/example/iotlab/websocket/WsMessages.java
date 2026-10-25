package com.example.iotlab.websocket;

import com.example.iotlab.alerts.Alert;
import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Source;
import com.example.iotlab.domain.Telemetry;
import java.time.Instant;
import java.util.List;

/** Mensajes servidor → cliente (docs/websocket-contract.md). El campo `type` va primero. */
final class WsMessages {

    private WsMessages() { }

    record Item(String deviceId, double temperature, Double humidity, Instant measuredAt, Instant receivedAt,
                long sequence, Source source) {
        static Item of(Telemetry t) {
            return new Item(t.deviceId(), t.temperature(), t.humidity(), t.measuredAt(), t.receivedAt(), t.sequence(), t.source());
        }
    }

    record Subscribed(String type, Object devices, long intervalMs) {
        Subscribed(Object devices, long intervalMs) { this("subscribed", devices, intervalMs); }
    }

    record Items(String type, List<Item> items) {
        static Items snapshot(List<Telemetry> t) { return new Items("snapshot", t.stream().map(Item::of).toList()); }
        static Items telemetry(List<Telemetry> t) { return new Items("telemetry", t.stream().map(Item::of).toList()); }
    }

    record Status(String type, String deviceId, DeviceStatus.State status, Instant at) {
        static Status of(DeviceStatus s) { return new Status("status", s.deviceId(), s.state(), s.receivedAt()); }
    }

    record AlertMsg(String type, String deviceId, Alert.Kind kind, Alert.State state, double temperature,
                    double threshold, Instant at) {
        static AlertMsg of(Alert a) {
            return new AlertMsg("alert", a.deviceId(), a.kind(), a.state(), a.temperature(), a.threshold(), a.at());
        }
    }

    record Pong(String type) {
        static final Pong INSTANCE = new Pong("pong");
    }

    record Error(String type, String message) {
        Error(String message) { this("error", message); }
    }
}
