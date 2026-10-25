package com.example.iotlab.processing;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.mqtt.InboundMessage;

/** Resultado de interpretar un {@link InboundMessage}: telemetría, estado o rechazo. */
public final class ParseResult {

    public enum Kind {
        TELEMETRY,
        STATUS,
        INVALID
    }

    private final Kind kind;
    private final InboundMessage source;
    private final Telemetry telemetry;
    private final DeviceStatus status;
    private final InvalidReason reason;
    private final String detail;

    private ParseResult(Kind kind, InboundMessage source, Telemetry telemetry, DeviceStatus status,
                        InvalidReason reason, String detail) {
        this.kind = kind;
        this.source = source;
        this.telemetry = telemetry;
        this.status = status;
        this.reason = reason;
        this.detail = detail;
    }

    static ParseResult telemetry(InboundMessage source, Telemetry t) {
        return new ParseResult(Kind.TELEMETRY, source, t, null, null, null);
    }

    static ParseResult status(InboundMessage source, DeviceStatus s) {
        return new ParseResult(Kind.STATUS, source, null, s, null, null);
    }

    static ParseResult invalid(InboundMessage source, InvalidReason reason, String detail) {
        return new ParseResult(Kind.INVALID, source, null, null, reason, detail);
    }

    public Kind kind() { return kind; }
    public InboundMessage source() { return source; }
    public Telemetry telemetry() { return telemetry; }
    public DeviceStatus status() { return status; }
    public InvalidReason reason() { return reason; }
    public String detail() { return detail; }

    @Override
    public String toString() {
        return kind == Kind.INVALID
                ? "INVALID(" + reason + ": " + detail + ", topic=" + source.topic() + ")"
                : kind + "(" + source.topic() + ")";
    }
}
