package synclist;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Потокобезопасный двусвязный список строк с двумя часовыми узлами
 * (голова и хвост), реализующий {@link Iterable}.
 *
 * <p>Синхронизация построена на «замке каждого узла» (lock coupling):
 * обходчик, удерживающий замок текущего узла, захватывает замок следующего
 * и лишь затем отпускает текущий. Все захваты выполняются в порядке
 * «ближе к голове — раньше», поэтому взаимные блокировки невозможны:
 * цикл ожидания замков в графе захвата не образуется.</p>
 *
 * <p>Список никогда не блокируется целиком: в каждый момент замкнуто лишь
 * несколько соседних узлов, поэтому пользователь видит текущее (частично
 * отсортированное) состояние списка практически без задержек.</p>
 *
 * <p>Итератор слабосогласованный ({@code weakly consistent}): он не бросает
 * {@code ConcurrentModificationException} и отражает те записи, которые были
 * достижимы от его курсора на момент прохода. Так как записи из списка
 * не удаляются, а узлы при обмене только переставляются, итератор всегда
 * возвращает реальные записи списка, включая вставленные в начало.</p>
 */
public final class LinkedStringList implements Iterable<String> {

    private final Node head;
    private final Node tail;

    /** Создаёт пустой список с часовыми узлами головы и хвоста. */
    public LinkedStringList() {
        this.head = new Node(null);
        this.tail = new Node(null);
        head.next = tail;
        tail.prev = head;
    }

    /** Часовой узел головы; доступен сортировщикам в пределах пакета. */
    Node head() {
        return head;
    }

    /** Часовой узел хвоста; доступен сортировщикам в пределах пакета. */
    Node tail() {
        return tail;
    }

    /**
     * Вставляет строку в начало списка (сразу за часовым узлом головы).
     *
     * <p>Захватываются только два замка — головы и её текущего первого узла,
     * причём в правильном порядке (г раньше), поэтому вставка безопасно
     * выполняется параллельно с сортирующими нитями.</p>
     *
     * @param value вставляемая строка
     */
    public void addFirst(String value) {
        Node node = new Node(value);
        head.lock();
        try {
            Node next = head.next;
            next.lock();
            try {
                node.prev = head;     // узел ещё не опубликован — пишем свободно
                node.next = next;
                next.prev = node;     // под замком next
                head.next = node;     // публикация под замком головы
            } finally {
                next.unlock();
            }
        } finally {
            head.unlock();
        }
    }

    /**
     * Возвращает число записей в списке на момент обхода.
     *
     * @return количество записей
     */
    public int size() {
        int count = 0;
        for (String ignored : this) {
            count++;
        }
        return count;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Каждое обращение к {@code hasNext()}/{@code next()} захватывает замки
     * на короткое время и отпускает их до возврата, поэтому длительный вывод
     * списка не мешает сортировке, а сортировка не блокирует вывод целиком.</p>
     */
    @Override
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private Node cursor = head;

            @Override
            public boolean hasNext() {
                cursor.lock();
                try {
                    return cursor.next != tail;
                } finally {
                    cursor.unlock();
                }
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Node previous = cursor; // локальная ссылка: поле cursor сменится ниже
                previous.lock();
                try {
                    Node result = previous.next;
                    result.lock();
                    try {
                        cursor = result;
                        return result.value;
                    } finally {
                        result.unlock();
                    }
                } finally {
                    previous.unlock();
                }
            }
        };
    }
}
