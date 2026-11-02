package spider;

import java.util.List;

/**
 * Точка входа «паука»: обходит сервер и печатает все собранные сообщения
 * в stdout, по одному на строке, в лексикографическом порядке.
 *
 * <p>Аргументы: args[0] — хост (по умолчанию {@code localhost}),
 * args[1] — порт (по умолчанию {@code 8080}). Диагностика (число страниц,
 * время работы) выводится в stderr.</p>
 */
public class Main {

    /**
     * Запускает обход и печатает результат.
     *
     * @param args хост и порт сервера (оба необязательны)
     */
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        long start = System.nanoTime();
        try {
            Spider spider = new Spider(host, port);
            List<String> messages = spider.crawl();
            StringBuilder out = new StringBuilder();
            for (String message : messages) {
                out.append(message).append(System.lineSeparator());
            }
            System.out.print(out);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.err.println("Обход завершён: страниц " + messages.size() + ", время " + elapsedMs + " мс");
        } catch (Exception e) {
            System.err.println("Ошибка обхода сервера " + host + ":" + port + " — " + e.getMessage());
            System.exit(1);
        }
    }
}
