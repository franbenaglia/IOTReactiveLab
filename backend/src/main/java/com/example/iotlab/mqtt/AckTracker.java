package com.example.iotlab.mqtt;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Libera los PUBACK en el orden de llegada (MQTT §4.6), aunque los mensajes terminen fuera de
 * orden: {@code flatMap} con concurrencia 20 completa en cualquier orden. Es la misma idea que el
 * commit de offsets en Kafka: solo se confirma el prefijo contiguo de mensajes terminados.
 *
 * <p>Consecuencia: un mensaje que nunca termina bloquea las confirmaciones de todos los
 * siguientes. Por eso todo camino del pipeline termina en un {@code Outcome}.
 *
 * <p>Thread-safe. {@link #register} se llama desde el hilo de callback de Paho y
 * {@link #complete} desde cualquier hilo (el de Paho, los event loops de R2DBC). Las
 * confirmaciones se envían dentro del lock: así dos hilos que liberan prefijos consecutivos no
 * pueden invertir su orden. El envío de Paho solo encola el paquete; no bloquea por red.
 */
public final class AckTracker {

    /** Envía el PUBACK real. */
    @FunctionalInterface
    public interface Sender {
        void send(int messageId, int qos) throws Exception;
    }

    /** Resultado de los envíos, para métricas. */
    public interface Listener {
        void sent(int count);

        void failed(Exception e);
    }

    private static final class Entry {
        final long token;
        final int messageId;
        final int qos;
        boolean done;

        Entry(long token, int messageId, int qos) {
            this.token = token;
            this.messageId = messageId;
            this.qos = qos;
        }
    }

    public static final long NO_ACK = -1L;

    private final Sender sender;
    private final Listener listener;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final Map<Long, Entry> byToken = new HashMap<>();
    private long nextToken = 0;

    public AckTracker(Sender sender, Listener listener) {
        this.sender = sender;
        this.listener = listener;
    }

    /** Registra un mensaje recién llegado. QoS 0 no se confirma: devuelve {@link #NO_ACK}. */
    public synchronized long register(int messageId, int qos) {
        if (qos == 0) {
            return NO_ACK;
        }
        Entry e = new Entry(nextToken++, messageId, qos);
        queue.addLast(e);
        byToken.put(e.token, e);
        return e.token;
    }

    /**
     * Marca un mensaje como terminado y envía los PUBACK del prefijo contiguo ya terminado.
     * Tokens desconocidos (anteriores a un {@link #reset}) se ignoran.
     */
    public synchronized void complete(long token) {
        Entry e = byToken.get(token);
        if (e == null) {
            return;
        }
        e.done = true;
        int sent = 0;
        while (!queue.isEmpty() && queue.peekFirst().done) {
            Entry head = queue.pollFirst();
            byToken.remove(head.token);
            try {
                sender.send(head.messageId, head.qos);
                sent++;
            } catch (Exception ex) {
                listener.failed(ex);
            }
        }
        if (sent > 0) {
            listener.sent(sent);
        }
    }

    /**
     * Olvida todo lo pendiente. Se llama al perderse la conexión: los packet id pertenecen a la
     * conexión anterior y confirmarlos en la nueva podría confirmar otro mensaje. El broker
     * reentrega lo no confirmado (sesión persistente) y la base descarta los duplicados.
     *
     * @return cantidad de confirmaciones descartadas
     */
    public synchronized int reset() {
        int discarded = queue.size();
        queue.clear();
        byToken.clear();
        return discarded;
    }

    public synchronized int pending() {
        return queue.size();
    }
}
