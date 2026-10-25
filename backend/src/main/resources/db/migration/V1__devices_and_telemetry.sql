-- IoT Reactive Lab — esquema inicial (main.md §9, ADR-011, ADR-013).

CREATE TABLE devices (
    id                BIGSERIAL    PRIMARY KEY,
    device_id         VARCHAR(64)  NOT NULL UNIQUE,
    -- NULL hasta recibir la primera telemetría: un status no informa el origen.
    source            VARCHAR(16)  CHECK (source IN ('PHYSICAL', 'SIMULATED')),
    status            VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN'
                                   CHECK (status IN ('UNKNOWN', 'ONLINE', 'OFFLINE')),
    -- Hora de RECEPCIÓN del último status: ordena estados que llegan desordenados
    -- (el LWT no trae timestamp propio).
    status_changed_at TIMESTAMPTZ,
    -- Última telemetría recibida. Se actualiza por lotes cada pocos segundos
    -- (DeviceRegistry), no en cada mensaje: es aproximada por diseño.
    last_seen         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE telemetry (
    id          BIGSERIAL         PRIMARY KEY,
    device_id   VARCHAR(64)       NOT NULL REFERENCES devices (device_id),
    temperature DOUBLE PRECISION  NOT NULL,
    humidity    DOUBLE PRECISION  CHECK (humidity BETWEEN 0 AND 100),
    measured_at TIMESTAMPTZ       NOT NULL,
    received_at TIMESTAMPTZ       NOT NULL,
    sequence    BIGINT            NOT NULL CHECK (sequence >= 0),

    -- ADR-011: una reentrega MQTT repite (device_id, measured_at, sequence) y se
    -- descarta con ON CONFLICT DO NOTHING. Tras un reinicio, sequence se repite
    -- pero measured_at no: no hay conflicto.
    --
    -- El mismo índice resuelve las consultas de la API:
    --   historial: WHERE device_id = ? AND measured_at BETWEEN ? AND ? ORDER BY measured_at DESC
    --   última:    WHERE device_id = ? ORDER BY measured_at DESC LIMIT 1
    CONSTRAINT telemetry_dedup UNIQUE (device_id, measured_at, sequence)
);
