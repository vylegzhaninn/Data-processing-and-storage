package spider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты «паука»: встроенный HTTP-сервер отдаёт детерминированный граф
 * из корня и 15 узлов с задержками 150–400 мс; проверяются полнота сбора сообщений,
 * лексикографический порядок результата и параллельность обхода.
 */
class SpiderTest {

    private HttpServer server;
    private int port;
    private ConcurrentHashMap<String, AtomicInteger> requestCounts;

    /** Детерминированный граф: у корня дубликат successors, у node-03 — цикл в корень,
     *  node-15 достижим от двух родителей, "node-04" приходит без ведущего слэша. */
    private static final Map<String, List<String>> GRAPH = buildGraph();

    private static Map<String, List<String>> buildGraph() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("/", List.of("/node-01", "/node-02", "/node-01", "/node-03", "node-04"));
        graph.put("/node-01", List.of("/node-05", "/node-06"));
        graph.put("/node-02", List.of("/node-07"));
        graph.put("/node-03", List.of("/"));
        graph.put("/node-04", List.of("/node-08", "/node-09"));
        graph.put("/node-05", List.of("/node-10"));
        graph.put("/node-06", List.of("/node-11"));
        graph.put("/node-07", List.of("/node-12"));
        graph.put("/node-08", List.of("/node-13"));
        graph.put("/node-09", List.of("/node-14", "/node-15"));
        graph.put("/node-10", List.of("/node-15"));
        graph.put("/node-11", List.of());
        graph.put("/node-12", List.of());
        graph.put("/node-13", List.of());
        graph.put("/node-14", List.of());
        graph.put("/node-15", List.of());
        return graph;
    }

    /** Детерминированная задержка обработки запроса: 150–400 мс по хэшу пути. */
    private static long delayOf(String path) {
        return 150 + (path.hashCode() & 0x7FFFFFFF) % 251;
    }

    private static String messageOf(String path) {
        return "message of " + path;
    }

    private static String bodyOf(String path) {
        StringBuilder successors = new StringBuilder();
        for (String successor : GRAPH.getOrDefault(path, List.of())) {
            if (successors.length() > 0) {
                successors.append(',');
            }
            successors.append('"').append(successor).append('"');
        }
        return "{\"message\":\"" + messageOf(path) + "\",\"successors\":[" + successors + "]}";
    }

    @BeforeEach
    void startServer() throws IOException {
        requestCounts = new ConcurrentHashMap<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestCounts.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(delayOf(path));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Обработка запроса прервана", e);
            }
            byte[] body = bodyOf(path).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Ожидаемые сообщения графа. */
    private static List<String> expectedMessages() {
        List<String> expected = new ArrayList<>();
        for (String path : GRAPH.keySet()) {
            expected.add(messageOf(path));
        }
        return expected;
    }

    @Test
    @Timeout(60)
    void crawlCollectsAllMessagesAndEachPathIsFetchedOnce() throws Exception {
        Spider spider = new Spider("localhost", port);
        List<String> messages = spider.crawl();

        List<String> expected = expectedMessages();
        assertEquals(expected.size(), messages.size(), "должны быть собраны все сообщения графа");
        assertTrue(expected.containsAll(messages) && messages.containsAll(expected),
                "набор сообщений должен совпадать с ожидаемым");

        // Обход завершился (не зациклился на цикле "/") и каждый путь скачан ровно один раз.
        assertEquals(GRAPH.keySet(), requestCounts.keySet(), "запрашивались только пути графа");
        for (String path : GRAPH.keySet()) {
            assertEquals(1, requestCounts.get(path).get(), "путь " + path + " должен быть скачан один раз");
        }
    }

    @Test
    @Timeout(60)
    void crawlReturnsMessagesSortedByCompareTo() throws Exception {
        Spider spider = new Spider("localhost", port);
        List<String> messages = spider.crawl();

        List<String> sorted = new ArrayList<>(messages);
        Collections.sort(sorted);
        assertEquals(sorted, messages, "результат должен быть отсортирован по String.compareTo");
    }

    @Test
    @Timeout(60)
    void crawlRunsInParallelAndIsFasterThanSumOfDelays() throws Exception {
        long sumOfDelays = GRAPH.keySet().stream().mapToLong(SpiderTest::delayOf).sum();

        Spider spider = new Spider("localhost", port);
        long start = System.nanoTime();
        spider.crawl();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < sumOfDelays,
                "обход (" + elapsedMs + " мс) должен идти параллельно и быть быстрее суммы задержек (" + sumOfDelays + " мс)");
    }
}
