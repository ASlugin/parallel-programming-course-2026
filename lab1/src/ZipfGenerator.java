import model.MetricsCollector;
import model.Snapshot;

import java.util.Random;

public class ZipfGenerator {

    public static final int SIZE = Math.powExact(2, 20);
    public static final long SEED = 42;

    public static final int MAX_VALUE = 1023;
    public static final double EXPONENT = 1.15;

    public static long[] generate() {
        return generate(SIZE, SEED);
    }

    // https://en.wikipedia.org/wiki/Zipf%27s_law
    public static long[] generate(int size, long seed) {

        int N = MAX_VALUE;     // количество возможных значений
        double s = EXPONENT;
        double H_N_s = 0;     // normalization constant

        for (int k = 1; k <= N; k++) {
            H_N_s += 1.0 / Math.pow(k, s);
        }

        double[] distr = new double[N];       // распределение
        for (int k = 1; k <= N; k++) {
            distr[k - 1] = (1.0 / H_N_s) * (1.0 / Math.pow(k, s));
        }

        var random = new Random(seed);
        long[] resultValues = new long[size];
        for (int i = 0; i < size; i++) {
            resultValues[i] = sample(distr, random.nextDouble());
        }
        return resultValues;
    }

    // distr - распределение
    // randValue - значение от 0 до 1
    private static long sample(double[] distr, double randValue) {
        double remaining = randValue;
        for (int k = 1; k <= distr.length; k++) {
            if (remaining < distr[k - 1]) {
                return k;
            }
            remaining -= distr[k - 1];
        }
        return distr.length;
    }

    static void main() {
        long[] values = generate();

        long bucket0 = 0, upTo10 = 0, over100 = 0;
        MetricsCollector collector = new SingleThreadCollector();
        for (long v : values) {
            if (v <= 3) bucket0++;
            if (v <= 10) upTo10++;
            if (v > 100) over100++;
            collector.record(v);
        }
        Snapshot s = collector.snapshot();
        double n = values.length;
        System.out.print("=========== Zipf generator test ===========\n");
        System.out.printf("values        : %d (seed %d, s = %.2f)\n", values.length, SEED, EXPONENT);
        System.out.printf("0..3 ms       : %5.1f %%  (expected ~1/3)\n", 100 * bucket0 / n);
        System.out.printf("<= 10 ms      : %5.1f %%  (expected ~1/2)\n", 100 * upTo10 / n);
        System.out.printf("> 100 ms      : %5.1f %%  (expected ~1/5)\n", 100 * over100 / n);
        System.out.printf("mean          : %.1f ms\n", (double) s.sum() / s.count());
        System.out.printf("min | max     : %d | %d ms\n", s.min(), s.max());
        System.out.printf("p50 | p99     : %d | %d ms\n", s.p50(), s.p99());
        System.out.print("===========================================\n");
    }
}
