package synclist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Тесты однопоточного поведения собственного двусвязного списка.
 */
class LinkedStringListTest {

    @Test
    void addFirstPlacesNewestValueAtHead() {
        LinkedStringList list = new LinkedStringList();
        list.addFirst("a");
        list.addFirst("b");
        list.addFirst("c");
        assertEquals(List.of("c", "b", "a"), collect(list));
    }

    @Test
    void emptyListIteratesNothing() {
        assertFalse(new LinkedStringList().iterator().hasNext());
        assertEquals(0, new LinkedStringList().size());
    }

    @Test
    void sizeCountsInsertedValues() {
        LinkedStringList list = new LinkedStringList();
        list.addFirst("a");
        list.addFirst("b");
        assertEquals(2, list.size());
    }

    @Test
    void forEachMatchesIteratorOrder() {
        LinkedStringList list = new LinkedStringList();
        list.addFirst("1");
        list.addFirst("2");
        List<String> seen = new ArrayList<>();
        for (String value : list) { // стандартный for-each
            seen.add(value);
        }
        assertEquals(List.of("2", "1"), seen);
    }

    private static List<String> collect(LinkedStringList list) {
        List<String> values = new ArrayList<>();
        for (String value : list) {
            values.add(value);
        }
        return values;
    }
}
