package synclist;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Точка входа программы «Синхронизированный доступ к списку».
 *
 * <p>Родительская (главная) нить читает строки пользователя и вставляет их
 * в начало списка; строки длиннее 80 символов разрезаются на несколько записей.
 * Пустая строка выводит текущее состояние списка (количество и содержимое
 * записей всегда соответствуют ранее введённым строкам). Настраиваемое число
 * дочерних нитей бесконечно сортирует список «пузырьком» с регулируемыми
 * задержками; блокировка всего списка на время сортировки не используется,
 * поэтому пользователь немедленно видит частично отсортированное состояние.</p>
 *
 * <p><b>Интерактивный режим</b> (по умолчанию): команды вводятся в stdin;
 * пустая строка — печать списка; {@code :quit} или конец ввода (Ctrl+D) —
 * остановка сортировщиков, печать финального состояния и суммарного числа
 * шагов. Служебные сообщения выводятся в stderr, содержимое списка — в stdout.</p>
 *
 * <p><b>Демонстрационный режим</b> ({@code --demo}) наполняет список
 * заданным числом записей в худшем (обратном) порядке, запускает сортировщики
 * на заданное время, останавливает их и печатает фактическое и теоретическое
 * число шагов — для сравнения обеих реализаций списка.</p>
 *
 * <p>Параметры командной строки (см. {@link Options#USAGE}):</p>
 * <pre>
 *   ./gradlew run --args="--impl=own --threads=3 --delay=1000 --inside=1000"
 *   ./gradlew run --args="--demo --impl=sync-array --elements=40 --threads=4 --delay=50 --inside=50 --duration=5000"
 * </pre>
 */
public final class Main {

    private Main() {
    }

    /**
     * Запускает программу в интерактивном или демонстрационном режиме.
     *
     * @param args параметры командной строки
     * @throws IOException           при ошибке чтения stdin
     * @throws InterruptedException  при прерывании демонстрационного ожидания
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        if (options.demo()) {
            runDemo(options);
        } else {
            runInteractive(options);
        }
    }

    /**
     * Интерактивный режим: главная нить читает ввод, дочерние сортируют.
     *
     * @param options параметры запуска
     * @throws IOException          при ошибке чтения stdin
     * @throws InterruptedException при ошибке ожидания завершения сортировщиков
     */
    private static void runInteractive(Options options) throws IOException, InterruptedException {
        System.err.printf("Реализация=%s, сортировщиков=%d, задержка между шагами=%d мс, внутри шага=%d мс%n",
                options.impl(), options.threads(), options.betweenMs(), options.insideMs());
        System.err.println("Введите строку; пустая строка — печать списка; :quit — выход.");

        SharedState state = options.impl().newState();
        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(options, state, steps);

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(":quit")) {
                    break;
                }
                if (line.isEmpty()) {
                    System.out.printf("--- Список (%d записей):%n", state.size());
                    state.printTo(System.out);
                    System.out.println("---");
                    continue;
                }
                List<String> chunks = InputSplitter.split(line);
                // Вставляем куски с конца, чтобы первый кусок строки оказался
                // ближе к голове списка (последним вытесняет ранее введённые).
                for (int i = chunks.size() - 1; i >= 0; i--) {
                    state.addFirst(chunks.get(i));
                }
                System.err.printf("Добавлено записей: %d%n", chunks.size());
            }
        } finally {
            stopSorters(sorters);
            System.out.printf("--- Финальный список (%d записей):%n", state.size());
            state.printTo(System.out);
            System.out.println("---");
            System.err.printf("Суммарно шагов сортировки: %d%n", steps.get());
        }
    }

    /**
     * Демонстрационный режим: измерение числа шагов сортировки.
     *
     * @param options параметры запуска
     * @throws InterruptedException при прерывании ожидания
     */
    private static void runDemo(Options options) throws InterruptedException {
        SharedState state = options.impl().newState();
        List<String> ascending = demoValues(options.elements());
        for (String value : ascending) {
            state.addFirst(value); // в списке получается обратный (худший) порядок
        }

        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(options, state, steps);
        Thread.sleep(options.durationMs());
        stopSorters(sorters);

        long expected = options.threads() * options.durationMs()
                / (options.betweenMs() + options.insideMs());
        System.out.printf("Реализация=%s, потоков=%d, элементов=%d, задержки: между шагами=%d мс, внутри шага=%d мс, время=%d мс%n",
                options.impl(), options.threads(), options.elements(),
                options.betweenMs(), options.insideMs(), options.durationMs());
        System.out.printf("Теоретическая оценка шагов: ~%d%n", expected);
        System.out.printf("Фактическое число шагов:    %d%n", steps.get());
        System.out.printf("Финальный список (%d записей):%n", state.size());
        state.printTo(System.out);
    }

    /**
     * Запускает настраиваемое число сортирующих нитей.
     */
    private static List<Thread> startSorters(Options options, SharedState state, AtomicLong steps) {
        List<Thread> sorters = new ArrayList<>();
        for (int i = 0; i < options.threads(); i++) {
            Thread thread = new Thread(
                    state.sorter(options.betweenMs(), options.insideMs(), steps),
                    "sorter-" + i);
            thread.setDaemon(true);
            thread.start();
            sorters.add(thread);
        }
        return sorters;
    }

    /**
     * Прерывает сортирующие нити и дожидается их завершения.
     */
    private static void stopSorters(List<Thread> sorters) throws InterruptedException {
        for (Thread sorter : sorters) {
            sorter.interrupt();
        }
        for (Thread sorter : sorters) {
            sorter.join(5000);
        }
    }

    /**
     * Детерминированный набор строк {@code value-000 ... value-(n-1)}
     * в лексикографическом порядке.
     */
    private static List<String> demoValues(int count) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(String.format("value-%03d", i));
        }
        return values;
    }
}
