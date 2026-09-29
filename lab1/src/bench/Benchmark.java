package bench;

import impl.SingleThreadCollector;
import api.MetricsCollector;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class Benchmark {

    private static final int RECORD_TIME_IN_SECONDS = 5;
    private static final int AMOUNT_OF_RUNS = 5;

    public static double measure(MetricsCollector collector, long[] values, int threads) throws InterruptedException {
        return measure(collector, values, threads, RECORD_TIME_IN_SECONDS, AMOUNT_OF_RUNS);
    }

    public static double measure(
            MetricsCollector collector,
            long[] values,
            int threads,
            int recordTimeInSeconds,
            int amountOfRuns
    ) throws InterruptedException {

        System.out.println("================ MEASURE ================");
        System.out.println("Collector: " + collector.getClass().getSimpleName());
        System.out.println("Amount of threads : " + threads);
        System.out.println("Record time : " + recordTimeInSeconds + " sec");

        double warmupOperationsPerSec = run(collector, values, threads, recordTimeInSeconds);
        System.out.printf("\nWarmup: %10.2f Mops/s\n", warmupOperationsPerSec / 1e6);

        double[] results = new double[amountOfRuns];
        for (int i = 0; i < amountOfRuns; i++) {
            results[i] = run(collector, values, threads, recordTimeInSeconds);
            System.out.printf("Run %d:  %10.2f Mops/s\n", i + 1, results[i] / 1e6);
        }

        double median = median(results);
        System.out.printf("\nMedian result: %10.2f Mops/s\n", median / 1e6);
        System.out.printf("Amount of record : %d\n", collector.snapshot().count());
        System.out.println("=========================================");
        return median;
    }

    // Один забег: threads потоков крутят record() ровно seconds секунд.
    // Возвращает опер/сек
    private static double run(
            MetricsCollector collector,
            long[] values,
            int threads,
            int seconds
    ) throws InterruptedException {

        long[] globalCountOperations = new long[threads];
        Thread[] workers = new Thread[threads];

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch readyToStart = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int k = t;
            workers[k] = new Thread(() -> {
                long localCountOperations = 0;
                int i = (k * 1000) % values.length; // разносим точки старта по массиву значений
                readyToStart.countDown();

                awaitUninterruptibly(start);

                while (!stop.get()) {
                    collector.record(values[i]);
                    localCountOperations++;
                    i++;
                    if (i == values.length)
                        i = 0;
                }

                globalCountOperations[k] = localCountOperations;
            });
            workers[k].start();
        }

        readyToStart.await();

        long t0 = System.nanoTime();

        start.countDown();
        Thread.sleep(seconds * 1000L);
        stop.set(true);

        long t1 = System.nanoTime();
        for (Thread w : workers)
            w.join();

        double spentSeconds = (t1 - t0) / 1e9;
        return Arrays.stream(globalCountOperations).sum() / spentSeconds;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {}
        }
    }

    private static double median(double[] array) {
        double[] sorted = array.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ?
                sorted[n / 2]
                :
                (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }


    static void main() throws InterruptedException {
        int threads = 1;
        MetricsCollector collector = new SingleThreadCollector();
        long[] values = ZipfGenerator.generate();
        int recordTimeInSeconds = 5;
        int amountOfRuns = 5;

        measure(collector, values, threads, recordTimeInSeconds, amountOfRuns);
    }
}
