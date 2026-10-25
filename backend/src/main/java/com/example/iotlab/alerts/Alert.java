package com.example.iotlab.alerts;

import java.time.Instant;

/** Evento de alerta (docs/websocket-contract.md). */
public record Alert(String deviceId, Kind kind, State state, double temperature, double threshold, Instant at) {

    public enum Kind { HIGH_TEMPERATURE }

    public enum State { RAISED, CLEARED }
}
