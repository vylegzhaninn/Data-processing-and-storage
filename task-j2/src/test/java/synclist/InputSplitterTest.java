package synclist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Тесты разрезания длинных строк на записи по 80 символов.
 */
class InputSplitterTest {

    @Test
    void keepsLineNotLongerThanLimit() {
        assertEquals(List.of("a".repeat(80)), InputSplitter.split("a".repeat(80)));
    }

    @Test
    void splitsLineLongerThanLimitIntoChunks() {
        List<String> chunks = InputSplitter.split("a".repeat(200));
        assertEquals(List.of("a".repeat(80), "a".repeat(80), "a".repeat(40)), chunks);
    }

    @Test
    void splitsAtBoundary() {
        assertEquals(List.of("a".repeat(80), "b"), InputSplitter.split("a".repeat(80) + "b"));
    }

    @Test
    void concatenationRestoresOriginalLine() {
        String line = "x".repeat(179) + "tail";
        List<String> chunks = InputSplitter.split(line);
        assertEquals(String.join("", chunks), line);
        assertEquals(3, chunks.size());
    }
}
