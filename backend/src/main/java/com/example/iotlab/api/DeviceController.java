package com.example.iotlab.api;

import com.example.iotlab.persistence.DeviceRepository;
import com.example.iotlab.persistence.TelemetryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * API de dispositivos (main.md §10). Modelo anotado de WebFlux (no Spring MVC): cada método
 * devuelve un Publisher y WebFlux se suscribe al escribir la respuesta. Ningún block().
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    static final Pattern DEVICE_ID = Pattern.compile("^[a-z0-9-]{3,64}$");
    static final int MAX_PAGE_SIZE = 1000;
    static final Duration DEFAULT_RANGE = Duration.ofHours(24);

    private final DeviceRepository devices;
    private final TelemetryRepository telemetry;

    public DeviceController(DeviceRepository devices, TelemetryRepository telemetry) {
        this.devices = devices;
        this.telemetry = telemetry;
    }

    /** Flux: se serializa como un arreglo JSON a medida que llegan las filas. */
    @GetMapping
    public Flux<DeviceView> list() {
        return devices.findAll();
    }

    @GetMapping("/{deviceId}")
    public Mono<DeviceView> get(@PathVariable String deviceId) {
        return devices.findById(validId(deviceId)).switchIfEmpty(notFound(deviceId));
    }

    @GetMapping("/{deviceId}/latest")
    public Mono<TelemetryView> latest(@PathVariable String deviceId) {
        return telemetry.latest(validId(deviceId)).switchIfEmpty(notFound(deviceId));
    }

    /**
     * Historial en [from, to), por defecto las últimas 24 h. Se pide size + 1 filas: si llega la
     * fila extra, hay más páginas (sin un COUNT(*) aparte).
     */
    @GetMapping("/{deviceId}/history")
    public Mono<HistoryPage> history(@PathVariable String deviceId,
                                     @RequestParam(required = false) Instant from,
                                     @RequestParam(required = false) Instant to,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "100") int size) {
        String id = validId(deviceId);
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end)) {
            throw new BadRequestException("from debe ser anterior a to");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page >= 0 y 1 <= size <= " + MAX_PAGE_SIZE);
        }
        return devices.findById(id).switchIfEmpty(notFound(id))
                .then(telemetry.history(id, start, end, size + 1, (long) page * size).collectList())
                .map(rows -> new HistoryPage(id, start, end, page, size, rows.size() > size,
                        rows.size() > size ? rows.subList(0, size) : rows));
    }

    private static String validId(String deviceId) {
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new BadRequestException("deviceId inválido: " + deviceId);
        }
        return deviceId;
    }

    private static <T> Mono<T> notFound(String deviceId) {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "dispositivo desconocido: " + deviceId));
    }
}
