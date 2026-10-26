package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/* Стратегии выбора следующего потока.
 * FirstAlive — этап 1, Replay — этап 2, RandomPick — этап 3, Dfs — этап 4 (ограничение bound — этап 5).
 * enabled — номера потоков, которые могут сделать шаг, по возрастанию; last — прошлый выбранный (-1 в начале). */
public final class Strategies {
    private Strategies() { }

    /** Всегда первый поток из enabled. */
    public static final class FirstAlive implements Strategy {
        public int pick(List<Integer> enabled, int last) {
            return enabled.getFirst();
        }
    }

    /** Проигрывает трассу: на i-м решении выбирает поток trace[i] ('A' -> 0, 'B' -> 1). */
    public static final class Replay implements Strategy {
        private final String trace;
        private int pos;

        public Replay(String trace) {
            this.trace = trace;
        }

        /** Сбросить позицию в трассе перед прогоном. */
        public void beginRun() {
            pos = 0;
        }

        public int pick(List<Integer> enabled, int last) {
            if (pos >= trace.length())
                throw new IllegalStateException("трасса " + trace + " кончилась раньше прогона");
            char c = trace.charAt(pos);
            int next = c - 'A';
            if (!enabled.contains(next))
                throw new IllegalStateException("трасса " + trace + ", решение " + (pos + 1) + ": поток " + c + " сейчас не может ходить");
            pos++;
            return next;
        }
    }

    /** Случайный поток: enabled.get(rnd.nextInt(enabled.size())), rnd = new java.util.Random(seed). */
    public static final class RandomPick implements Strategy {
        private final long seed;
        private Random rnd;

        public RandomPick(long seed) {
            this.seed = seed;
        }

        /** Генератор заново с тем же seed: одинаковый seed — одинаковое расписание. */
        public void beginRun() {
            rnd = new Random(seed);
        }

        public int pick(List<Integer> enabled, int last) {
            return enabled.get(rnd.nextInt(enabled.size()));
        }
    }

    /** Stateless DFS по дереву выборов: каждый прогон с нуля, повторяет префикс прошлого и берёт следующий вариант.
     *  bound — максимум вытеснений (этап 5); для dfs runner передаёт Integer.MAX_VALUE. */
    public static final class Dfs implements Strategy {
        /** Развилка на пути: какие были варианты и какой из них выбран сейчас. */
        private static final class Branch {
            final List<Integer> options;
            int chosen;

            Branch(List<Integer> options) {
                this.options = options;
            }
        }

        private final int bound;                              // максимум вытеснений за прогон
        private final List<Branch> path = new ArrayList<>();  // развилки текущего прогона, от корня вниз
        private int depth;                                    // номер решения в текущем прогоне
        private int preemptions;                              // сколько вытеснений уже было в текущем прогоне

        public Dfs(int bound) {
            this.bound = bound;
        }

        /** Подготовиться к новому прогону (позиция в пути, счётчик вытеснений). */
        public void beginRun() {
            depth = 0;
            preemptions = 0;
        }

        public int pick(List<Integer> enabled, int last) {
            // вытеснение — выбрать другой поток, когда прошлый (last) ещё может идти дальше
            boolean lastCanGo = enabled.contains(last);
            if (depth == path.size()) {
                // новая развилка: если лимит вытеснений исчерпан, вариант один — продолжить last
                List<Integer> options = lastCanGo && preemptions >= bound ? List.of(last) : new ArrayList<>(enabled);
                path.add(new Branch(options));  // начинаем с первого варианта
            }
            Branch b = path.get(depth);
            depth++;
            int next = b.options.get(b.chosen);
            if (lastCanGo && next != last)
                preemptions++;
            return next;
        }

        /** Выбрать следующий путь; false — дерево обойдено. */
        public boolean endRun() {
            // с конца пути ищем развилку, где остались неиспробованные варианты
            while (!path.isEmpty()) {
                Branch b = path.getLast();
                if (b.chosen + 1 < b.options.size()) {
                    b.chosen++;  // префикс до неё повторим, а здесь возьмём следующий вариант
                    return true;
                }
                path.removeLast();  // здесь всё испробовано — поднимаемся выше
            }
            return false;
        }
    }
}
