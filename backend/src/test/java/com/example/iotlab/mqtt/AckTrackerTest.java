package com.example.iotlab.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AckTrackerTest {

    private final List<Integer> acked = Collections.synchronizedList(new ArrayList<>());
    private final List<Exception> failures = new ArrayList<>();
    private final AckTracker tracker = new AckTracker((id, qos) -> acked.add(id), new AckTracker.Listener() {
        @Override public void sent(int count) { }
        @Override public void failed(Exception e) { failures.add(e); }
    });

    @Test
    void releasesAcksInArrivalOrderEvenWhenCompletedOutOfOrder() {
        long t1 = tracker.register(11, 1);
        long t2 = tracker.register(12, 1);
        long t3 = tracker.register(13, 1);

        tracker.complete(t3);
        assertThat(acked).isEmpty();           // 11 y 12 siguen en curso
        tracker.complete(t1);
        assertThat(acked).containsExactly(11); // 12 todavía bloquea a 13
        tracker.complete(t2);
        assertThat(acked).containsExactly(11, 12, 13);
        assertThat(tracker.pending()).isZero();
    }

    @Test
    void qos0IsNeverAcked() {
        long token = tracker.register(1, 0);
        assertThat(token).isEqualTo(AckTracker.NO_ACK);
        tracker.complete(token);
        assertThat(acked).isEmpty();
        assertThat(tracker.pending()).isZero();
    }

    @Test
    void resetDiscardsPendingAndIgnoresLateCompletions() {
        long t1 = tracker.register(1, 1);
        long t2 = tracker.register(2, 1);
        assertThat(tracker.reset()).isEqualTo(2);
        // El packet id 1 se reutiliza en la conexión nueva:
        long fresh = tracker.register(1, 1);
        tracker.complete(t1); // completa tarde, de la conexión anterior
        tracker.complete(t2);
        assertThat(acked).isEmpty(); // no confirmó el mensaje nuevo por error
        tracker.complete(fresh);
        assertThat(acked).containsExactly(1);
    }

    @Test
    void sendFailureIsReportedAndDoesNotBlockTheRest() {
        AckTracker failing = new AckTracker((id, qos) -> {
            if (id == 2) throw new IllegalStateException("sin conexión");
            acked.add(id);
        }, new AckTracker.Listener() {
            @Override public void sent(int count) { }
            @Override public void failed(Exception e) { failures.add(e); }
        });
        long a = failing.register(1, 1);
        long b = failing.register(2, 1);
        long c = failing.register(3, 1);
        failing.complete(c);
        failing.complete(b);
        failing.complete(a);
        assertThat(acked).containsExactly(1, 3);
        assertThat(failures).hasSize(1);
    }

    @Test
    void concurrentCompletionsKeepGlobalOrder() throws Exception {
        int n = 10_000;
        long[] tokens = new long[n];
        for (int i = 0; i < n; i++) {
            tokens[i] = tracker.register(i, 1);
        }
        List<Long> shuffled = new ArrayList<>();
        for (long t : tokens) shuffled.add(t);
        Collections.shuffle(shuffled);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(n);
        for (long t : shuffled) {
            pool.execute(() -> { tracker.complete(t); done.countDown(); });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(acked).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(acked.get(i)).isEqualTo(i);
        }
    }
}
