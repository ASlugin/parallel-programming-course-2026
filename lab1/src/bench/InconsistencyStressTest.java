package bench;

import impl.*;
import api.MetricsCollector;
import api.Snapshot;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class InconsistencyStressTest {

    private static final List<MetricsCollector> COLLECTORS = List.of(
            new LockCollector(),
            new EmptyLockCollector(),
            new StripedLockCollector(),
            new ThreadLocalCollector(),
            new DoubleBufferCollector(),
            new DoubleBufferNoRecheckCollector()
    );

    private static final int WRITERS = 4;
    private static final int SNAPSHOTS = 10_000;
    static final int WARMUP_MS = 5000;

    private record Result(
            String collector,
            int bucketsLess,
            int bucketsMore,
            long finalCount,
            long calls
    ) {

        int broken() {
            return bucketsLess + bucketsMore;
        }

        double brokenPercent() {
            return 100.0 * broken() / SNAPSHOTS;
        }

        long differenceInCounts() {
            return finalCount - calls;
        }
    }

    private static Result run(MetricsCollector collector, long[] values) throws InterruptedException {
        long[] calls = new long[WRITERS];
        Thread[] writers = new Thread[WRITERS];

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch readyToStart = new CountDownLatch(WRITERS);

        for (int t = 0; t < WRITERS; t++) {
            int k = t;
            writers[k] = new Thread(() -> {
                long localCalls = 0;
                int i = (k * 1000) % values.length; // разносим точки старта по массиву значений
                readyToStart.countDown();

                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }

                while (!stop.get()) {
                    collector.record(values[i]);
                    localCalls++;
                    i++;
                    if (i == values.length)
                        i = 0;
                }

                calls[k] = localCalls;
            });
            writers[k].start();
        }

        readyToStart.await();
        start.countDown();
        Thread.sleep(WARMUP_MS);

        int bucketsLess = 0;
        int bucketsMore = 0;
        for (int n = 0; n < SNAPSHOTS; n++) {
            Snapshot s = collector.snapshot();
            long bucketsSum = Arrays.stream(s.buckets()).sum();
            if (bucketsSum < s.count())
                bucketsLess++;
            else if (bucketsSum > s.count())
                bucketsMore++;
        }

        stop.set(true);
        for (Thread w : writers)
            w.join();

        long finalCount = collector.snapshot().count();
        return new Result(
                collector.getClass().getSimpleName(),
                bucketsLess,
                bucketsMore,
                finalCount,
                Arrays.stream(calls).sum()
        );
    }

    private static void printResults(Result r) {
        System.out.println("Collector: " + r.collector());
        System.out.printf("    - Broken snapshots: %d from %d (%.2f %%)\n", r.broken(), SNAPSHOTS, r.brokenPercent());
        System.out.printf("    - sum < count: %d\n", r.bucketsLess());
        System.out.printf("    - sum > count: %d\n", r.bucketsMore());
        System.out.printf("    - After join: count = %d  |  calls = %d  |  diff = %d  \n\n",
                r.finalCount(), r.calls(), r.differenceInCounts());
    }


    static void main() throws InterruptedException {
        long[] values = ZipfGenerator.generate();

        System.out.println("================ Inconsistency Test ================");
        System.out.println("Writers: " + WRITERS);
        System.out.println("Snapshots: " + SNAPSHOTS);
        System.out.println("\n");

        for (MetricsCollector collector : COLLECTORS) {
            Result r = run(collector, values);
            printResults(r);
        }

        System.out.println("=====================================================");
    }
}
