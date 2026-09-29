package impl;

import api.MetricsCollector;
import api.Snapshot;
import bench.Benchmark;
import bench.ZipfGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static api.Snapshot.percentile;

/**
 * Этап 3
 */
public final class ThreadLocalCollector implements MetricsCollector {

    private static final class ThreadState {
        final AtomicLongArray buckets = new AtomicLongArray(Snapshot.AMOUNT_OF_BUCKETS);
        final AtomicLong count = new AtomicLong();
        final AtomicLong sum = new AtomicLong();
        final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong max = new AtomicLong(0);
    }

    private final List<ThreadState> allStates = new ArrayList<>();
    private final Object listLock = new Object();

    private final ThreadLocal<ThreadState> myState = ThreadLocal.withInitial(() -> {
        ThreadState s = new ThreadState();
        synchronized (listLock) {
            allStates.add(s);
        }
        return s;
    });

    @Override
    public void record(long value) {
        ThreadState s = myState.get();
        int bucketIndex = Snapshot.bucketIndexFor(value);

        s.buckets.setRelease(bucketIndex, s.buckets.getPlain(bucketIndex) + 1);
        s.count.setRelease(s.count.getPlain() + 1);
        s.sum.setRelease(s.sum.getPlain() + value);

        if (value < s.min.getPlain())
            s.min.setRelease(value);
        if (value > s.max.getPlain())
            s.max.setRelease(value);
    }

    @Override
    public Snapshot snapshot() {
        List<ThreadState> states;
        synchronized (listLock) {
            states = new ArrayList<>(allStates);
        }

        long[] buckets = new long[Snapshot.AMOUNT_OF_BUCKETS];
        long count = 0;
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (ThreadState s : states) {
            for (int i = 0; i < buckets.length; i++)
                buckets[i] += s.buckets.get(i);
            count += s.count.get();
            sum += s.sum.get();
            min = Math.min(min, s.min.get());
            max = Math.max(max, s.max.get());
        }

        return new Snapshot(
                buckets,
                count,
                sum,
                min,
                max,
                percentile(buckets, count, 0.50),
                percentile(buckets, count, 0.99)
        );
    }


    static void main() throws InterruptedException {
        int threads = 1;
        MetricsCollector collector = new ThreadLocalCollector();
        long[] values = ZipfGenerator.generate();

        Benchmark.measure(collector, values, threads);
    }
}
