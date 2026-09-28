import model.MetricsCollector;
import model.Snapshot;

import static model.Snapshot.percentile;

/**
 * Этап 0
 */
public final class SingleThreadCollector implements MetricsCollector {

    private final long[] buckets = new long[Snapshot.AMOUNT_OF_BUCKETS];
    private long count;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max = 0;

    @Override
    public void record(long value) {
        count++;
        sum += value;
        min = Math.min(min, value);
        max = Math.max(max, value);
        buckets[Snapshot.bucketIndexFor(value)]++;
    }

    @Override
    public Snapshot snapshot() {
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
}
