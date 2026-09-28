import model.MetricsCollector;
import model.Snapshot;

import static model.Snapshot.percentile;

/**
 * Этап 1
 */
public final class LockCollector implements MetricsCollector {

    private final long[] buckets = new long[Snapshot.AMOUNT_OF_BUCKETS];
    private long count;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max = 0;

    @Override
    public synchronized void record(long value) {
        count++;
        sum += value;
        min = Math.min(min, value);
        max = Math.max(max, value);
        buckets[Snapshot.bucketIndexFor(value)]++;
    }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                buckets.clone(),
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
        MetricsCollector collector = new LockCollector();
        long[] values = ZipfGenerator.generate();

        Benchmark.measure(collector, values, threads);
    }
}
