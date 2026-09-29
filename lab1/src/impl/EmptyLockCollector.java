package impl;

import api.MetricsCollector;
import api.Snapshot;
import bench.Benchmark;
import bench.ZipfGenerator;

/**
 * Этап 1
 */
public final class EmptyLockCollector implements MetricsCollector {

    private final long[] buckets = new long[Snapshot.AMOUNT_OF_BUCKETS];
    private long count;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max = 0;

    @Override
    public synchronized void record(long value) {

    }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(new long[Snapshot.AMOUNT_OF_BUCKETS], 0L,0L,0L,0L,0L, 0L);
    }


    static void main() throws InterruptedException {
        int threads = 1;
        MetricsCollector collector = new EmptyLockCollector();
        long[] values = ZipfGenerator.generate();

        Benchmark.measure(collector, values, threads);
    }
}
