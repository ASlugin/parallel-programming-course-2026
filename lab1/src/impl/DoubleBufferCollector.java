package impl;

import api.MetricsCollector;
import api.Snapshot;
import bench.Benchmark;
import bench.ZipfGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static api.Snapshot.percentile;

/**
 * Этап 4
 */
public class DoubleBufferCollector implements MetricsCollector {

    private static final int NOWHERE = -1;

    static final class ThreadBuffers {
        final long[][] buckets = new long[2][Snapshot.AMOUNT_OF_BUCKETS];
        final long[] count = new long[2];
        final long[] sum = new long[2];
        final long[] min = {Long.MAX_VALUE, Long.MAX_VALUE};
        final long[] max = {0, 0};

        // Флаг входа: -1 = вне буферов, 0 = запись в буфер 0, 1 = запись в буфер 1
        final AtomicInteger inside = new AtomicInteger(NOWHERE);
    }

    protected volatile int active = 0; // в какой буфер сейчас пишут ВСЕ потоки

    private final List<ThreadBuffers> allBuffers = new ArrayList<>();
    private final Object snapLock = new Object();

    private final ThreadLocal<ThreadBuffers> myBuffers = ThreadLocal.withInitial(() -> {
        ThreadBuffers b = new ThreadBuffers();
        synchronized (snapLock) {
            allBuffers.add(b);
        }
        return b;
    });

    // Накопленный итог за всё время: меняется только в snapshot() под snapLock
    private final long[] totalBuckets = new long[Snapshot.AMOUNT_OF_BUCKETS];
    private long totalCount;
    private long totalSum;
    private long totalMin = Long.MAX_VALUE;
    private long totalMax = 0;


    @Override
    public final void record(long value) {
        ThreadBuffers my = myBuffers.get();
        int b = enterActiveBuffer(my);

        // Здесь буфер my.buf[b] гарантированно принадлежит нам:
        int bucketIndex = Snapshot.bucketIndexFor(value);
        my.buckets[b][bucketIndex]++;
        my.count[b]++;
        my.sum[b] += value;
        if (value < my.min[b])
            my.min[b] = value;
        if (value > my.max[b])
            my.max[b] = value;

        my.inside.setRelease(NOWHERE);
    }

    // Рукопожатие Деккера
    int enterActiveBuffer(ThreadBuffers my) {
        while (true) {
            int b = active;         // 1. Посмотрели, какой буфер сейчас активен (seq_cst)
            my.inside.set(b);       // 2. Объявляем намерение писать в буфер b (seq_cst)
            if (active == b)        // 3. Проверка: пока мы ставили inside, читатель не сменил active?
                return b;
            my.inside.setRelease(NOWHERE); // 4. Читатель успел сменить active! Сбрасываем inside и пробуем снова
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (snapLock) {
            int old = active;
            active = 1 - old;

            for (ThreadBuffers s : allBuffers) {
                while (s.inside.get() == old)
                    Thread.onSpinWait();

                countAndReset(s, old);
            }

            return new Snapshot(
                    totalBuckets.clone(),
                    totalCount,
                    totalSum,
                    totalMin,
                    totalMax,
                    percentile(totalBuckets, totalCount, 0.50),
                    percentile(totalBuckets, totalCount, 0.99)
            );
        }
    }


    private void countAndReset(ThreadBuffers buffers, int oldActive) {
        long[] buckets = buffers.buckets[oldActive];
        for (int i = 0; i < buckets.length; i++)
            totalBuckets[i] += buckets[i];
        totalCount += buffers.count[oldActive];
        totalSum += buffers.sum[oldActive];
        totalMin = Math.min(totalMin, buffers.min[oldActive]);
        totalMax = Math.max(totalMax, buffers.max[oldActive]);

        Arrays.fill(buckets, 0);
        buffers.count[oldActive] = 0;
        buffers.sum[oldActive] = 0;
        buffers.min[oldActive] = Long.MAX_VALUE;
        buffers.max[oldActive] = 0;
    }


    static void main() throws InterruptedException {
        int threads = 22;
        MetricsCollector collector = new DoubleBufferCollector();
        long[] values = ZipfGenerator.generate();

        Benchmark.measure(collector, values, threads);
    }
}
