package synclist;

import java.util.ArrayList;
import java.util.List;

/**
 * Разрезание вводимых строк на куски длиной не более 80 символов.
 *
 * <p>Строки длиннее 80 символов вставляются в список несколькими записями;
 * разрезание выполняется по последовательным 80-символьным кускам.</p>
 */
public final class InputSplitter {

    /** Максимальная длина одной записи списка. */
    public static final int MAX_CHUNK_LENGTH = 80;

    private InputSplitter() {
    }

    /**
     * Режет строку на куски не длиннее {@value #MAX_CHUNK_LENGTH} символов.
     * Конкатенация кусков в исходном порядке равна исходной строке.
     *
     * @param line непустая исходная строка
     * @return список кусков (для строки не длиннее лимита — один элемент)
     */
    public static List<String> split(String line) {
        List<String> chunks = new ArrayList<>();
        for (int begin = 0; begin < line.length(); begin += MAX_CHUNK_LENGTH) {
            int end = Math.min(line.length(), begin + MAX_CHUNK_LENGTH);
            chunks.add(line.substring(begin, end));
        }
        return chunks;
    }
}
