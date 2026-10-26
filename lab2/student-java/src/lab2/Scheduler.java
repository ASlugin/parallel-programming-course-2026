package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/* Этап 1: планировщик, который ведёт настоящие потоки строго по одному (передача хода через семафоры).
 * Этап 2: обнаружение deadlock в главном цикле и корректное завершение застрявших потоков.
 * Внутреннее устройство (свои классы, семафоры, вспомогательные методы для Shared и SchedLock) придумываете сами. */
public final class Scheduler {
    /** Если true, каждая операция с общими данными добавляет строку в log. Выставляет runner. */
    boolean logging;
    /** Лог прогона: строки "<поток>  <операция>", например "A  lock mu", "A  balance -> 100". */
    final List<String> log = new ArrayList<>();
    /** null, если deadlock не было; иначе "held={a:A, b:B}" (этап 2). */
    String deadlock;

    /** Поток под управлением планировщика */
    private static final class Worker {
        final Scheduler scheduler;
        final int id;
        final char name;                          // A, B, C...
        final Runnable body;
        final Semaphore turn = new Semaphore(0);  // release() = "твой ход"
        Thread thread;
        boolean finished;
        boolean needPause = true;                 // трогал ли общее с прошлой паузы; перед первой операцией пауза нужна всегда
        BooleanSupplier waitFor;                  // занятый лок / await: пока условие ложно, потока нет в enabled

        Worker(Scheduler scheduler, int id, Runnable body) {
            this.scheduler = scheduler;
            this.id = id;
            this.name = (char) ('A' + id);
            this.body = body;
        }
    }

    /** Worker текущего потока: через него point(), await() и Shared находят свой планировщик. */
    private static final ThreadLocal<Worker> currentWorker = new ThreadLocal<>();

    private final List<Worker> workers = new ArrayList<>();
    private final Semaphore back = new Semaphore(0);  // поток отдал ход планировщику
    private final StringBuilder trace = new StringBuilder(); // траса прогона

    /** Все локи прогона в порядке создания (для held={...}). Заполняет конструктор SchedLock. */
    final List<SchedLock> locks = new ArrayList<>();
    /** Прогон прерван из-за deadlock: разбуженный поток не продолжает работу, а выходит. */
    private boolean aborted;

    /** Этим исключением раскручивается стек застрявшего потока после deadlock. */
    private static final class RunAborted extends RuntimeException { }

    public Scheduler() { }

    /** Зарегистрировать поток. Потоки получают буквы A, B, C… в порядке вызова spawn. */
    public void spawn(Runnable body) {
        workers.add(new Worker(this, workers.size(), body));
    }

    /** Запустить потоки и вести их по одному: на каждой паузе спросить strategy.pick(enabled, last). */
    public void run(Strategy strategy) {
        // старт: запускаем потоки по одному и ждём, пока каждый дойдёт до паузы перед своей первой операцией
        for (Worker w : workers) {
            w.thread = new Thread(() -> threadMain(w));
            w.thread.setDaemon(true);  // если прогон упадёт (например, неверная трасса), JVM не будет ждать уснувшие потоки
            w.thread.start();
            back.acquireUninterruptibly();
        }

        int last = -1;
        while (true) {
            List<Integer> enabled = new ArrayList<>();
            boolean alive = false;
            for (Worker w : workers) {
                if (w.finished)
                    continue;
                alive = true;
                if (w.waitFor == null || w.waitFor.getAsBoolean())
                    enabled.add(w.id);
            }
            if (!alive)
                break;
            if (enabled.isEmpty()) {
                // живые потоки есть, но никто не может сделать шаг
                deadlock = heldLocks();
                abortAll();
                break;
            }

            int next = strategy.pick(enabled, last);
            trace.append(workers.get(next).name);
            last = next;

            workers.get(next).turn.release();  // разбудить выбранный поток
            back.acquireUninterruptibly();     // и ждать, пока он дойдёт до следующей паузы или закончит
        }

        for (Worker w : workers) {
            try {
                w.thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Трасса прогона: буквы потоков, выбранных во всех решениях подряд, например "AAAABB". */
    public String traceString() {
        return trace.toString();
    }

    /** "held={a:A, b:B}": только занятые локи, в порядке создания. */
    private String heldLocks() {
        List<String> held = new ArrayList<>();
        for (SchedLock l : locks)
            if (l.owner != -1)
                held.add(l.name + ":" + workers.get(l.owner).name);
        return "held={" + String.join(", ", held) + "}";
    }

    /** Разбудить все застрявшие потоки, чтобы они вышли через RunAborted. Иначе join в run() повиснет. */
    private void abortAll() {
        aborted = true;
        for (Worker w : workers)
            if (!w.finished)
                w.turn.release();
    }

    private void threadMain(Worker w) {
        currentWorker.set(w);
        try {
            w.body.run();
        } catch (RunAborted e) {
            // прогон прерван (deadlock): поток просто выходит, в лог ничего не пишет
        } finally {
            w.finished = true;
            back.release();  // выход из потока тоже отдаёт ход планировщику
        }
    }

    /** Отдать ход планировщику и спать, пока он снова не выберет этот поток. */
    private void pause(Worker w) {
        back.release();
        w.turn.acquireUninterruptibly();
        if (aborted)
            throw new RunAborted();
    }

    // дальше всё вызывается изнутри потоков под управлением планировщика

    /** Перед каждой операцией с общими данными (Shared, SchedLock): пауза, если с прошлой паузы поток что-то трогал. */
    static void beforeOp() {
        beforeOp(() -> true);
    }

    /** То же, но операцию можно сделать, только когда canGo истинно (лок свободен, условие await выполнено).
     *  Если сейчас нельзя — пауза, и пока canGo ложно, потока нет в enabled. */
    static void beforeOp(BooleanSupplier canGo) {
        Worker worker = currentWorker.get();
        if (worker.needPause || !canGo.getAsBoolean()) {
            worker.waitFor = canGo;
            worker.scheduler.pause(worker);
            worker.waitFor = null;
        }
        worker.needPause = true;
    }

    /** Номер текущего потока: 0 = A, 1 = B, ... */
    static int currentId() {
        return currentWorker.get().id;
    }

    /** Добавить в лог строку "<поток>  <операция>" от имени текущего потока. */
    static void log(String op) {
        Worker worker = currentWorker.get();
        if (worker.scheduler.logging)
            worker.scheduler.log.add(worker.name + "  " + op);
    }

    /** window() из тестовых программ: пауза перед следующей операцией с общими данными обязательна. */
    public static void point() {
        currentWorker.get().needPause = true;
        log("window");
    }

    /** Ждать, пока cond не станет true. До этого поток не попадает в enabled. */
    public static void await(BooleanSupplier cond) {
        beforeOp(cond);  // после await, как после point(), перед следующей операцией нужна пауза
        log("await done");
    }
}
