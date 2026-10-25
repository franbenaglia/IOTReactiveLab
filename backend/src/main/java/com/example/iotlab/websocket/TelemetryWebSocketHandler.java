package com.example.iotlab.websocket;

import com.example.iotlab.alerts.AlertService;
import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.reactive.LatestTelemetryCache;
import com.example.iotlab.reactive.RealtimeBus;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pipeline B: {@link RealtimeBus} → filtro por dispositivo → agregación por intervalo → WebSocket.
 * Contrato: docs/websocket-contract.md. Operadores: docs/reactor-concepts.md (fase 4).
 *
 * <p>Una conexión son dos flujos independientes. Lo que entra ({@code session.receive()}) son
 * comandos; lo que sale ({@code session.send(...)}) son datos. Reactor Netty pide al Flux de
 * salida a medida que puede escribir en el socket: <b>un cliente lento genera menos demanda</b>,
 * y eso se traduce en descartes ({@code onBackpressureLatest}) solo para ese cliente.
 */
@Component
public class TelemetryWebSocketHandler implements WebSocketHandler {

    private final RealtimeBus bus;
    private final LatestTelemetryCache cache;
    private final AlertService alerts;
    private final WsCodec codec;
    private final PipelineMetrics metrics;

    public TelemetryWebSocketHandler(RealtimeBus bus, LatestTelemetryCache cache, AlertService alerts,
                                     WsCodec codec, PipelineMetrics metrics) {
        this.bus = bus;
        this.cache = cache;
        this.alerts = alerts;
        this.codec = codec;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // publish().autoConnect(3): un solo receive() compartido por los tres consumidores de
        // abajo; recién se conecta cuando los tres se suscribieron, para no perder el primer
        // comando.
        Flux<ClientCommand> commands = session.receive()
                .map(m -> codec.parse(m.getPayloadAsText()))
                .publish()
                .autoConnect(3);

        Flux<Object> replies = commands
                .filter(c -> !(c instanceof ClientCommand.Subscribe))
                .map(c -> c instanceof ClientCommand.Ping
                        ? WsMessages.Pong.INSTANCE
                        : new WsMessages.Error(((ClientCommand.Invalid) c).message()));

        // switchMap: cada subscribe nuevo cancela el stream anterior y arranca otro. Es la
        // semántica "una suscripción activa por conexión" del contrato.
        Flux<Object> data = commands
                .ofType(ClientCommand.Subscribe.class)
                .switchMap(this::stream, 1);

        Flux<String> outbound = Flux.merge(1, replies, data)
                // Cuando el cliente cierra, receive() completa: se corta también la salida.
                .takeUntilOther(commands.then())
                .map(codec::write)
                .doOnNext(m -> metrics.wsMessage());

        return session.send(outbound.map(session::textMessage))
                .doOnSubscribe(s -> metrics.wsConnected())
                .doFinally(s -> metrics.wsDisconnected());
    }

    Flux<Object> stream(ClientCommand.Subscribe sub) {
        Flux<Object> header = Flux.just(
                new WsMessages.Subscribed(sub.all() ? "*" : List.copyOf(sub.devices()), sub.interval().toMillis()));
        // defer: la foto se toma al suscribirse, no al armar el Flux.
        Flux<Object> snapshot = Flux.defer(() ->
                Flux.just(WsMessages.Items.snapshot(cache.latest(sub::matches))));

        Flux<Telemetry> filtered = bus.telemetry().filter(t -> sub.matches(t.deviceId()));
        Flux<List<Telemetry>> aggregated;
        if (!sub.all() && sub.devices().size() == 1) {
            // Un dispositivo: sample() emite la última medición de cada intervalo y descarta las
            // intermedias. Sin mediciones nuevas no emite nada.
            aggregated = filtered.sample(sub.interval()).map(List::of);
        } else {
            // Varios dispositivos: sample() sobre el Flux mezclado se quedaría con UNA medición
            // de UN dispositivo por intervalo. window() corta el flujo en ventanas de tiempo y
            // collectMap conserva la última de cada dispositivo (la clave se sobrescribe).
            aggregated = filtered.window(sub.interval())
                    .concatMap(w -> w.collectMap(Telemetry::deviceId))
                    .filter(m -> !m.isEmpty())
                    .map((Map<String, Telemetry> m) -> List.copyOf(m.values()));
        }
        Flux<Object> telemetry = aggregated
                // Cliente lento: si el lote anterior no se pudo enviar, se reemplaza por el más
                // nuevo. Memoria acotada a un lote por cliente, y nunca un atraso creciente.
                .onBackpressureLatest()
                .doOnDiscard(List.class, d -> metrics.wsDropped())
                .map(WsMessages.Items::telemetry);

        // Estados y alertas son pocos y no conviene perderlos: buffer chico, y si aun así se
        // llena, se descarta el más viejo (sin error).
        Flux<Object> statuses = bus.statuses()
                .filter(s -> sub.matches(s.deviceId()))
                .<Object>map(WsMessages.Status::of)
                .onBackpressureBuffer(256, d -> metrics.wsDropped(), BufferOverflowStrategy.DROP_OLDEST);
        Flux<Object> alertEvents = alerts.events()
                .filter(a -> sub.matches(a.deviceId()))
                .<Object>map(WsMessages.AlertMsg::of)
                .onBackpressureBuffer(256, d -> metrics.wsDropped(), BufferOverflowStrategy.DROP_OLDEST);

        // prefetch 1: merge pide por adelantado a cada fuente (32 por defecto) aunque el cliente no
        // haya pedido nada, y esos lotes viejos quedarían retenidos en su cola interna, sin que
        // onBackpressureLatest se entere. Con 1, un cliente lento retiene a lo sumo un lote
        // viejo; después recibe siempre el más nuevo.
        return Flux.concat(header, snapshot, Flux.merge(1, telemetry, statuses, alertEvents));
    }
}
