package server;

import server.net.NioServer;
import server.net.Sender;
import server.model.GenRequest;
import server.model.Response;
import server.service.Generator;
import server.service.KeyIssuer;
import server.service.UserRegistry;
import server.model.GenRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Composition Root: единственное место, знающее обо всех частях системы.
 * Конвейер (Producer–Consumer):
 * NioServer (Reactor) -> generationQueue -> Generator -> sendQueue -> Sender.
 */
public class ServerMain {

    private static final int SENDER_THREADS = 2;
    private static final Path DEFAULT_CA_KEY = Path.of("resources", "ca.pem");

    /**
     * Аргументы: <порт> <число генерирующих нитей> <issuer name, например CN=Lab CA> [ca-key.pem]
     * Без последнего аргумента ключ CA читается из resources/ca.pem.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: server <port> <gen-threads> <issuer-name> [ca-key.pem]");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        int generatorThreadCount = Integer.parseInt(args[1]);
        if (generatorThreadCount < 1) {
            throw new IllegalArgumentException("gen-threads must be >= 1");
        }
        Path caKeyFile = args.length == 4 ? Path.of(args[3]) : DEFAULT_CA_KEY;
        if (!Files.isRegularFile(caKeyFile)) {
            System.err.printf("CA key %s not found, generate it first: openssl genrsa -out %s 2048%n",
                    caKeyFile, caKeyFile);
            System.exit(2);
        }
        System.out.printf("[ca] signing key: %s%n", caKeyFile);

        LinkedBlockingQueue<GenRequest> requestQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Response> responseQueue = new LinkedBlockingQueue<>();
        UserRegistry userRegistry = new UserRegistry(requestQueue);
        KeyIssuer keyIssuer = new KeyIssuer(args[2], KeyIssuer.loadCaKey(caKeyFile));

        ExecutorService generators = Executors.newFixedThreadPool(generatorThreadCount, namedThreads("generator"));
        ExecutorService senders = Executors.newFixedThreadPool(SENDER_THREADS, namedThreads("sender"));
        for (int i = 0; i < generatorThreadCount; i++) {
            generators.execute(new Generator(requestQueue, userRegistry, keyIssuer));
        }
        for (int i = 0; i < SENDER_THREADS; i++) {
            senders.execute(new Sender(responseQueue));
        }

        new NioServer(port, userRegistry, responseQueue).run();
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> new Thread(task, prefix + "-" + counter.incrementAndGet());
    }
}
