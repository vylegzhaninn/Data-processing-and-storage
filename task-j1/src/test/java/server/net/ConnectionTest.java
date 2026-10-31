package server.net;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionTest {

    private final Connection connection = new Connection();

    private static ByteBuffer bytes(String content) {
        return ByteBuffer.wrap(content.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void extractsSingleTerminatedName() {
        assertEquals(List.of("alice"), connection.extractNames(bytes("alice\0")));
    }

    @Test
    void extractsSeveralNamesFromOneChunk() {
        assertEquals(List.of("a", "b", "c"), connection.extractNames(bytes("a\0b\0c\0")));
    }

    @Test
    void assemblesNameArrivingInChunks() {
        assertEquals(List.of(), connection.extractNames(bytes("ali")));
        assertEquals(List.of("alice"), connection.extractNames(bytes("ce\0")));
    }

    @Test
    void keepsPendingBytesUntilTerminatorArrives() {
        assertEquals(List.of(), connection.extractNames(bytes("bo")));
        assertEquals(List.of(), connection.extractNames(bytes("b")));
        assertEquals(List.of("bob", "next"), connection.extractNames(bytes("\0next\0")));
    }

    @Test
    void emptyNameIsExtractedAsEmptyString() {
        assertEquals(List.of(""), connection.extractNames(bytes("\0")));
    }
}
