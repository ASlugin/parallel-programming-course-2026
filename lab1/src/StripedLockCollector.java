import model.MetricsCollector;
import model.Snapshot;

import java.util.concurrent.atomic.AtomicLong;

import static model.Snapshot.percentile;

/**
 * Этап 2
 */
public final class StripedLockCollector implements MetricsCollector {

    private static final int GROUPS = 16;
    private static final int BUCKETS_PER_GROUP = Snapshot.AMOUNT_OF_BUCKETS / GROUPS;

    private static class Group {

        private final long[] buckets = new long[BUCKETS_PER_GROUP];

        public synchronized void increment(int bucket) {
            buckets[bucket / GROUPS]++;
        }

        public synchronized void copyTo(long[] out, int groupIndex) {
            for (int i = 0; i < buckets.length; i++)
                out[groupIndex + i * GROUPS] = buckets[i];
        }
    }

    private final Group[] groups = new Group[GROUPS];

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong sum = new AtomicLong();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(0);

    public StripedLockCollector() {
        for (int i = 0; i < GROUPS; i++)
            groups[i] = new Group();
    }

    @Override
    public void record(long value) {
        int bucket = Snapshot.bucketIndexFor(value);
        groups[bucket % GROUPS].increment(bucket);

        count.incrementAndGet();
        sum.addAndGet(value);
        updateMin(value);
        updateMax(value);
    }

    private void updateMin(long value) {
        long current = min.get();
        while (value < current && !min.compareAndSet(current, value))
            current = min.get();
    }

    private void updateMax(long value) {
        long current = max.get();
        while (value > current && !max.compareAndSet(current, value))
            current = max.get();
    }

    @Override
    public Snapshot snapshot() {
        long[] buckets = new long[Snapshot.AMOUNT_OF_BUCKETS];
        for (int i = 0; i < GROUPS; i++)
            groups[i].copyTo(buckets, i);

        long count = this.count.get();
        return new Snapshot(
                buckets,
                count,
                sum.get(),
                min.get(),
                max.get(),
                percentile(buckets, count, 0.50),
                percentile(buckets, count, 0.99)
        );
    }


    static void main() throws InterruptedException {
        int threads = 18;
        MetricsCollector collector = new StripedLockCollector();
        long[] values = ZipfGenerator.generate();

        Benchmark.measure(collector, values, threads);
    }
}
