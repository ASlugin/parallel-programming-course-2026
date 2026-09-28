package model;

public record Snapshot(
        long[] buckets, // ровно 256 элементов (глубокая копия, не ссылка!)
        long count,
        long sum,
        long min,
        long max,
        long p50,       // в мс: индекс_корзины * 4
        long p99
) {

    public static final int AMOUNT_OF_BUCKETS = 256;
    public static final int BUCKET_VOLUME = 4;

    public static int bucketIndexFor(long value) {
        return (int) Math.min(
            value / BUCKET_VOLUME,
            AMOUNT_OF_BUCKETS - 1
        );
    }

    public static long percentile(long[] buckets, long count, double q) {
        double threshold = count * q;
        long accumCount = 0;
        for (int i = 0; i < buckets.length; i++) {
            accumCount += buckets[i];
            if (accumCount >= threshold) {
                return (long) i * BUCKET_VOLUME;
            }
        }
        return (long) (buckets.length - 1) * BUCKET_VOLUME;
    }
}
