package com.example.iotlab.processing;

/** Resultado de persistir una medición. */
public enum StoreResult {
    INSERTED,
    /** La clave (device_id, measured_at, sequence) ya existía (ADR-011). */
    DUPLICATE
}
