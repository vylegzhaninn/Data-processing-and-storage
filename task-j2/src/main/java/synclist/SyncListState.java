package synclist;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Вариант разделяемого состояния на стандартном {@link ArrayList},
 * обёрнутом в {@link Collections#synchronizedList(List)}.
 *
 * <p>Обход итератором обёртки не синхронизируется ею автоматически,
 * поэтому вывод списка выполняется под ручным захватом монитора
 * {@code synchronized (list)} — требование документации {@code synchronizedList}.
 * Вывод остаётся циклом for-each.</p>
 */
public final class SyncListState implements SharedState {

    private final List<String> list = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void addFirst(String value) {
        list.add(0, value);
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public void printTo(PrintStream out) {
        synchronized (list) { // итерация по synchronizedList требует ручной синхронизации
            for (String value : list) { // for-each
                out.println(value);
            }
        }
    }

    @Override
    public List<String> snapshot() {
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    @Override
    public Runnable sorter(long betweenStepsDelayMs, long insideStepDelayMs, AtomicLong steps) {
        return new SynchronizedArrayListSorter(list, betweenStepsDelayMs, insideStepDelayMs, steps);
    }
}
