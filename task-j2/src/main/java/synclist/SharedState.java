package synclist;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Общий для главной и дочерних нитей разделяемый список строк.
 *
 * <p>Интерфейс объединяет обе реализации задания: собственный двусвязный
 * список и стандартный {@code ArrayList} с обёрткой
 * {@code Collections.synchronizedList()}. Главная нить вставляет записи,
 * дочерние нити сортируют, вывод выполняется циклом for-each.</p>
 */
public interface SharedState {

    /**
     * Вставляет запись в начало списка.
     *
     * @param value вставляемая строка
     */
    void addFirst(String value);

    /**
     * Число записей в списке на момент вызова.
     *
     * @return количество записей
     */
    int size();

    /**
     * Выводит текущее состояние списка построчно; вывод реализован
     * стандартным циклом for-each и не требует полной блокировки списка.
     *
     * @param out приёмник вывода
     */
    void printTo(PrintStream out);

    /**
     * Снимок списка: записи в текущем порядке от головы к хвосту.
     *
     * @return список-копию текущего содержимого
     */
    List<String> snapshot();

    /**
     * Создаёт задачу сортирующей нити для этой реализации списка.
     *
     * @param betweenStepsDelayMs задержка между шагами сортировки, мс
     * @param insideStepDelayMs   задержка внутри шага сортировки, мс
     * @param steps               общий счётчик шагов всех сортировщиков
     * @return задача для запуска в отдельной нити
     */
    Runnable sorter(long betweenStepsDelayMs, long insideStepDelayMs, AtomicLong steps);
}
