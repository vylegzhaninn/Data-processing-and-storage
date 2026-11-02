package synclist;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сортирующая нить для варианта списка на базе стандартного
 * {@link ArrayList}, обёрнутого в {@link Collections#synchronizedList(List)}.
 *
 * <p>Связанный список здесь заменить нечем: у {@code ArrayList} нет
 * узлов и ссылок, поэтому обмен выполняется обменом содержимого ячеек —
 * {@link Collections#swap(List, int, int)}. Чтобы попытка обмена была
 * атомарной (чтение пары + сравнение + обмен), шаг выполняется под общим
 * монитором списка {@code synchronized (list)} — тем же, каким пользуется
 * сама обёртка.</p>
 *
 * <p>Задержка между шагами выполняется вне монитора, поэтому пользователь
 * немедленно видит текущее состояние списка. Задержка внутри шага выполняется
 * под монитором: в отличие от собственного списка (где блокируются лишь
 * четыре соседних узла), здесь на время «медленного» шага блокируется весь
 * список — это ключевой наблюдаемый недостаток библиотечного варианта.</p>
 *
 * <p>Алгоритм — тот же бесконечный пузырёк: нить проходится по индексам
 * от 0 до {@code size()-2} и снова с начала, каждый проход учитывая попытку
 * обмена как шаг в переданном счётчике.</p>
 */
public final class SynchronizedArrayListSorter implements Runnable {

    private final List<String> list;
    private final long betweenStepsDelayMs;
    private final long insideStepDelayMs;
    private final AtomicLong steps;

    /**
     * Создаёт задачу сортировщика.
     *
     * @param list                список-обёртка {@code Collections.synchronizedList}
     * @param betweenStepsDelayMs задержка между шагами, мс (вне монитора)
     * @param insideStepDelayMs   задержка внутри шага, мс (под монитором списка)
     * @param steps               общий счётчик шагов всех сортировщиков
     */
    public SynchronizedArrayListSorter(List<String> list, long betweenStepsDelayMs,
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
     * Один полный проход пузырька по индексам списка.
     *
     * @throws InterruptedException если нить прервана во время задержки
     */
    private void singlePass() throws InterruptedException {
        int size = list.size(); // чтение размера под монитором самой обёртки
        for (int i = 0; i < size - 1; i++) {
            // Задержка между шагами — без монитора: список доступен пользователю.
            Thread.sleep(betweenStepsDelayMs);
            synchronized (list) {
                Thread.sleep(insideStepDelayMs); // задержка внутри шага, список заблокирован
                if (i + 1 < list.size()
                        && list.get(i).compareTo(list.get(i + 1)) > 0) {
                    Collections.swap(list, i, i + 1); // обмен содержимым — ссылок переустанавливать нечем
                }
                steps.incrementAndGet(); // шаг = попытка обмена
            }
        }
    }
}
