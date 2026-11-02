package synclist;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Дочерняя нить, бесконечно сортирующая {@link LinkedStringList} в
 * лексикографическом порядке вариантом алгоритма пузырька без раннего выхода:
 * после достижения хвоста список проходится снова и снова.
 *
 * <p><b>Шаг сортировки</b> — попытка обменять местами соседнюю пару записей
 * (независимо от того, состоялся обмен или нет). Каждый шаг учитывается
 * в переданном счётчике {@link AtomicLong}.</p>
 *
 * <p><b>Задержки.</b> Между шагами нить спит {@code betweenStepsDelayMs}
 * миллисекунд, полностью отпустив все замки, — в это время пользователь
 * немедленно видит текущее состояние списка. Внутри шага нить спит
 * {@code insideStepDelayMs} миллисекунд, удерживая замки захваченной четвёрки
 * соседних узлов, — это моделирует «медленное» сравнение и перестановку.</p>
 *
 * <p><b>Порядок захвата замков.</b> Нить работает с окном из четырёх соседних
 * узлов {@code (prev, a, b, c)} и захватывает их замки строго от головы
 * к хвосту, поэтому взаимная блокировка с другими нитями (которые следуют
 * тому же правилу) невозможна. После паузы между шагами соседство узлов могло
 * измениться из-за чужих обменов, поэтому перед шагом целостность цепочки
 * проверяется ({@link #chainIntact}); при неудаче проход начинается заново
 * с головы.</p>
 *
 * <p><b>Обмен.</b> При перестановке переустанавливаются ссылки {@code prev}
 * и {@code next} шести смежных связей; значения строк в узлах не трогаются.</p>
 */
public final class ListSorter implements Runnable {

    private final LinkedStringList list;
    private final long betweenStepsDelayMs;
    private final long insideStepDelayMs;
    private final AtomicLong steps;

    /**
     * Создаёт задачу сортировщика.
     *
     * @param list                сортируемый список
     * @param betweenStepsDelayMs задержка между шагами, мс (замки отпущены)
     * @param insideStepDelayMs   задержка внутри шага, мс (замки удерживаются)
     * @param steps               общий счётчик шагов всех сортировщиков
     */
    public ListSorter(LinkedStringList list, long betweenStepsDelayMs,
                      long insideStepDelayMs, AtomicLong steps) {
        this.list = list;
        this.betweenStepsDelayMs = betweenStepsDelayMs;
        this.insideStepDelayMs = insideStepDelayMs;
        this.steps = steps;
    }

    /**
     * Бесконечный цикл проходов пузырька; завершается по прерыванию нити.
     */
    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                singlePass();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt(); // штатное завершение по команде извне
        }
    }

    /**
     * Один полный проход пузырька от головы до хвоста: нить движется по парам
     * соседних записей, выполняя на каждой шаг сортировки.
     *
     * @throws InterruptedException если нить прервана во время задержки
     */
    private void singlePass() throws InterruptedException {
        Node prev = list.head();
        while (true) {
            Node nextPrev = step(prev);
            // Задержка между шагами выполняется без единого захваченного замка:
            // пользователь немедленно видит текущее состояние списка.
            Thread.sleep(betweenStepsDelayMs);
            if (nextPrev == null) {
                return; // конец списка или цепочка изменилась — начинаем проход заново
            }
            prev = nextPrev;
        }
    }

    /**
     * Выполняет один шаг сортировки для пары, следующей за узлом {@code prev}.
     *
     * @param prev узел-предшественник пары (замок не захвачен на входе)
     * @return узел-предшественник следующей пары, либо {@code null}, если
     *         проход нужно завершить (достигнут конец списка или цепочка
     *         изменилась под нитью)
     * @throws InterruptedException если нить прервана во время задержки внутри шага
     */
    private Node step(Node prev) throws InterruptedException {
        prev.lock();
        try {
            Node a = prev.next;
            if (a == list.tail()) {
                return null; // записей меньше двух — проход завершён
            }
            a.lock();
            try {
                Node b = a.next;
                if (b == list.tail()) {
                    return null; // осталась одна запись — проход завершён
                }
                b.lock();
                try {
                    Node c = b.next; // может быть часовым узлом хвоста
                    c.lock();
                    try {
                        if (!chainIntact(prev, a, b, c)) {
                            return null; // список изменился — проход заново
                        }
                        Thread.sleep(insideStepDelayMs); // задержка внутри шага
                        boolean swapped = a.value.compareTo(b.value) > 0;
                        steps.incrementAndGet(); // шаг = попытка обмена
                        if (swapped) {
                            relink(prev, a, b, c);
                        }
                        // После шага пара (i+1, i+2) — это узлы, оказавшиеся на
                        // второй позиции пары и за ней: b→a при обмене, иначе a→b.
                        return swapped ? b : a;
                    } finally {
                        c.unlock();
                    }
                } finally {
                    b.unlock();
                }
            } finally {
                a.unlock();
            }
        } finally {
            prev.unlock();
        }
    }

    /**
     * Проверяет, что четыре узла по-прежнему образуют неразрывную цепочку
     * {@code prev → a → b → c}. Соседство могло разрушиться, пока нить спала
     * между шагами и чужие нити выполняли свои обмены.
     */
    private static boolean chainIntact(Node prev, Node a, Node b, Node c) {
        return prev.next == a && a.prev == prev
                && a.next == b && b.prev == a
                && b.next == c && c.prev == b;
    }

    /**
     * Меняет местами соседние узлы {@code a} и {@code b} переустановкой ссылок:
     * цепочка {@code prev → a → b → c} превращается в {@code prev → b → a → c}.
     * Содержимое строк в узлах не изменяется.
     */
    private static void relink(Node prev, Node a, Node b, Node c) {
        prev.next = b;
        b.prev = prev;
        b.next = a;
        a.prev = b;
        a.next = c;
        c.prev = a;
    }
}
