package com.otapp.hmis.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.registration.application.MrNumberGenerator;
import com.otapp.hmis.support.AbstractIntegrationTest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrency golden-master for MR-number allocation (build-spec §2.1 DoD, CR-02).
 *
 * <p>The legacy save-then-{@code MAX(id)} pattern could race; the sequence-backed generator must
 * produce zero duplicates under contention. NOT {@code @Transactional}: each thread's
 * {@code next()} must COMMIT in its own writable transaction so the {@code seq_mrno} allocation is
 * genuinely concurrent (mirrors the inc-04 DocumentNumberConcurrencyIT).
 */
class MrNumberConcurrencyIT extends AbstractIntegrationTest {

    @Autowired MrNumberGenerator mrNumberGenerator;

    @Test
    void concurrent20Threads_produceDistinctMrNumbers() throws Exception {
        final int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        numbers.add(mrNumberGenerator.next());
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("no errors under contention").isEmpty();
        assertThat(numbers).as("20 distinct MR numbers, no duplicates").hasSize(n);
        assertThat(numbers).allMatch(s -> s.matches("^MRNO/\\d{4}/\\d+$"));
    }
}
