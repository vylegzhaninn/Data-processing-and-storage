package synclist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Конкурентные тесты сортировки: и собственный список, и обёртка
 * {@code Collections.synchronizedList} должны сохранять состав записей
 * и сходиться к отсортированному состоянию, не взаимно блокируясь
 * с параллельными вставками.
 */
class ConcurrentSortingTest {

    @Test
    @Timeout(60)
    void ownListSortersConvergeToSortedState() throws Exception {
        List<String> ascending = sampleAscending(30);
        SharedState state = new LinkedStringState();
        insertReverse(state, ascending); // худший случай: список в обратном порядке

        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(state, 3, 0, 0, steps);
        waitForSorted(state, ascending);
        stopSorters(sorters);

        assertEquals(ascending, collect(state), "список должен стать отсортированным");
        assertTrue(steps.get() > 0, "шаги сортировки должны учитываться");
    }

    @Test
    @Timeout(60)
    void synchronizedArrayListSortersConvergeToSortedState() throws Exception {
        List<String> ascending = sampleAscending(30);
        SharedState state = new SyncListState();
        insertReverse(state, ascending);

        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(state, 3, 0, 0, steps);
        waitForSorted(state, ascending);
        stopSorters(sorters);

        assertEquals(ascending, collect(state), "список должен стать отсортированным");
        assertTrue(steps.get() > 0, "шаги сортировки должны учитываться");
    }

    @Test
    @Timeout(60)
    void ownListSurvivesConcurrentInsertionsWithoutDeadlock() throws Exception {
        SharedState state = new LinkedStringState();
        insertReverse(state, sampleAscending(10));

        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(state, 4, 1, 1, steps);
        for (int i = 0; i < 100; i++) {
            state.addFirst("extra-" + i); // вставки параллельно с сортировкой
            Thread.sleep(2);
        }
        stopSorters(sorters);

        assertEquals(110, state.size(), "все вставленные записи должны остаться в списке");
        List<String> seen = collect(state);
        assertEquals(110, seen.size());
        for (String value : seen) {
            assertFalse(value == null, "записи не должны теряться или дублироваться");
        }
    }

    @Test
    @Timeout(60)
    void synchronizedArrayListSurvivesConcurrentInsertionsWithoutDeadlock() throws Exception {
        SharedState state = new SyncListState();
        insertReverse(state, sampleAscending(10));

        AtomicLong steps = new AtomicLong();
        List<Thread> sorters = startSorters(state, 4, 1, 1, steps);
        for (int i = 0; i < 100; i++) {
            state.addFirst("extra-" + i);
            Thread.sleep(2);
        }
        stopSorters(sorters);

        assertEquals(110, state.size());
    }

    /** Детерминированный набор строк в лексикографическом порядке. */
    private static List<String> sampleAscending(int count) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(String.format("value-%03d", i));
        }
        Collections.shuffle(values, new Random(42));
        Collections.sort(values);
        return values;
    }

    /** Вставляет отсортированный набор так, что в списке он оказывается обратным. */
    private static void insertReverse(SharedState state, List<String> ascending) {
        for (String value : ascending) {
            state.addFirst(value);
        }
    }

    private static List<Thread> startSorters(SharedState state, int count,
                                             long betweenMs, long insideMs, AtomicLong steps) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(state.sorter(betweenMs, insideMs, steps), "sorter-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        return threads;
    }

    /** Ожидает, пока бесконечный пузырёк отсортирует список (с нулевыми задержками это быстро). */
    private static void waitForSorted(SharedState state, List<String> ascending) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (collect(state).equals(ascending)) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static void stopSorters(List<Thread> sorters) throws InterruptedException {
        for (Thread sorter : sorters) {
            sorter.interrupt();
        }
        for (Thread sorter : sorters) {
            sorter.join(20_000);
            assertFalse(sorter.isAlive(), "сортировщик должен завершиться по прерыванию");
        }
    }

    /** Снимок списка через стандартный for-each. */
    private static List<String> collect(SharedState state) {
        return state.snapshot();
    }
}
