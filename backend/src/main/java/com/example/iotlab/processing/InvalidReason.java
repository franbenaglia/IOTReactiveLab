package com.example.iotlab.processing;

import java.util.Locale;

/** Motivos de rechazo. Conjunto cerrado: se usa como etiqueta de métrica. */
public enum InvalidReason {
    UNKNOWN_TOPIC,
    MALFORMED_JSON,
    NOT_AN_OBJECT,
    MISSING_FIELD,
    WRONG_TYPE,
    BAD_DEVICE_ID,
    ID_MISMATCH,
    OUT_OF_RANGE,
    BAD_SEQUENCE,
    BAD_TIMESTAMP,
    FUTURE_TIMESTAMP,
    BAD_SOURCE,
    BAD_STATUS;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
