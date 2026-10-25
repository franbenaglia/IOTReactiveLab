package com.example.iotlab.processing;

/**
 * Estado final de un mensaje en el Pipeline A. Todo mensaje termina en exactamente uno, y
 * recién entonces se confirma al broker (ADR-010).
 */
public record Outcome(Type type, long ackToken, long receivedNanos, InvalidReason reason) {

    public enum Type {
        PERSISTED,
        DUPLICATE,
        INVALID,
        STATUS,
        /** Error no transitorio de persistencia: se confirma y se pierde, contado y logueado. */
        FAILED
    }
}
