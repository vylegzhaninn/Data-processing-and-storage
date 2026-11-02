package synclist;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Вариант разделяемого состояния на собственном двусвязном списке
 * {@link LinkedStringList}: обмен записей выполняется переустановкой ссылок,
 * вывод по for-each не блокирует список целиком.
 */
public final class LinkedStringState implements SharedState {

    private final LinkedStringList list = new LinkedStringList();

    @Override
    public void addFirst(String value) {
        list.addFirst(value);
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public void printTo(PrintStream out) {
        for (String value : list) { // for-each по собственному Iterable
            out.println(value);
        }
    }

    @Override
    public List<String> snapshot() {
        List<String> values = new ArrayList<>();
        for (String value : list) {
            values.add(value);
        }
        return values;
    }

    @Override
    public Runnable sorter(long betweenStepsDelayMs, long insideStepDelayMs, AtomicLong steps) {
        return new ListSorter(list, betweenStepsDelayMs, insideStepDelayMs, steps);
    }
}
