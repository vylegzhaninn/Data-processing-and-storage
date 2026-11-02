package spider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * «Паук» обхода HTTP-сервера: начиная с корневого пути {@code "/"} идёт по ссылкам
 * {@code successors} и собирает все сообщения. Каждый запрос выполняется в собственном
 * виртуальном потоке, поэтому обход большого графа идёт параллельно.
 */
public class Spider {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long RETRY_PAUSE_MS = 500;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> pages = new ConcurrentHashMap<>();

    /**
     * Создаёт паука для сервера по хосту и порту.
     *
     * @param host хост сервера
     * @param port порт сервера
     */
    public Spider(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
    }

    /**
     * Обходит граф страниц, начиная с корня {@code "/"}, и собирает все сообщения.
     * Каждый путь скачивается не более одного раза: путь помечается посещённым
     * в {@code visited} строго до отправки задачи на скачивание.
     *
     * @return все собранные сообщения, отсортированные по {@code String.compareTo}
     * @throws IOException          если обход не удалось завершить
     * @throws InterruptedException если поток был прерван во время ожидания задач
     */
    public List<String> crawl() throws IOException, InterruptedException {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            visited.add("/");
            Future<?> root = pool.submit(() -> crawlPath(pool, "/"));
            root.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Обход завершился ошибкой: " + e.getCause().getMessage(), e.getCause());
        }
        List<String> messages = new ArrayList<>(pages.values());
        Collections.sort(messages);
        return messages;
    }

    /**
     * Скачивает страницу по пути, сохраняет её сообщение и рекурсивно запускает обход
     * всех ещё не посещённых наследников.
     *
     * <p>Рекурсивное ожидание дочерних Future гарантирует полное завершение обхода:
     * задача считается выполненной только тогда, когда выполнены и она сама, и все
     * порождённые ею задачи-наследники (транзитивно). Поэтому возврат Future корневой
     * задачи означает, что весь достижимый граф скачан и все значения в {@code pages}
     * на месте.</p>
     *
     * <p>Каждый запрос выполняется в собственном виртуальном потоке: задача передаётся
     * в пул {@code Executors.newVirtualThreadPerTaskExecutor()}, который создаёт по
     * виртуальному потоку на каждую задачу. Виртуальные потоки дешёвые (миллионы
     * одновременно), а работа здесь почти целиком состоит из ожидания сетевого ответа,
     * поэтому при блокировке во время HTTP-запроса поток ОС не занят — сервер успевает
     * обрабатывать запросы параллельно, и суммарное время обхода близко к глубине
     * графа, а не к сумме времён всех страниц.</p>
     *
     * @param pool пул виртуальных потоков, в котором выполняются задачи обхода
     * @param path путь страницы для скачивания
     */
    private void crawlPath(ExecutorService pool, String path) {
        Page page = fetch(path);
        pages.put(path, page.message());

        List<Future<?>> futures = new ArrayList<>();
        for (String successor : page.successors()) {
            String normalized = successor.startsWith("/") ? successor : "/" + successor;
            if (visited.add(normalized)) {
                futures.add(pool.submit(() -> crawlPath(pool, normalized)));
            }
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание дочерних задач прервано", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Обход пути завершился ошибкой: " + e.getCause().getMessage(), e.getCause());
            }
        }
    }

    /**
     * Выполняет GET-запрос по пути и разбирает JSON-ответ.
     * До 3 попыток: сетевые ошибки и таймауты завершаются паузой 500 мс и повтором.
     *
     * @param path URL-путь страницы
     * @return разобранная страница
     */
    private Page fetch(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "GET " + path + ": HTTP " + response.statusCode() + ", тело: " + response.body());
                }
                return Page.parse(response.body(), mapper);
            } catch (IOException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_PAUSE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Ожидание повтора прервано", ie);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Запрос " + path + " прерван", e);
            }
        }
        throw new IllegalStateException("GET " + path + " не удалось после " + MAX_ATTEMPTS + " попыток", lastError);
    }
}
