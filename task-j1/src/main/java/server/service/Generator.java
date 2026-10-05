package server.service;

import server.model.GenRequest;

import java.util.concurrent.BlockingQueue;

/**
 * Генерирующая нить (Producer–Consumer, consumer очереди генерации): забирает запросы,
 * выпускает ключи через KeyIssuer и завершает ими future ожидающих клиентов.
 * Блокируется, только когда работы нет — передача результата клиентов её не касается.
 */
public class Generator implements Runnable {

    private final BlockingQueue<GenRequest> requestQueue;
    private final UserRegistry userRegistry;
    private final KeyIssuer keyIssuer;

    public Generator(BlockingQueue<GenRequest> requestQueue, UserRegistry userRegistry, KeyIssuer keyIssuer) {
        this.requestQueue = requestQueue;
        this.userRegistry = userRegistry;
        this.keyIssuer = keyIssuer;
    }

    @Override
    public void run() {
        while (true) {
            GenRequest request;
            try {
                request = requestQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                request.future().complete(keyIssuer.issue(request.name()));
            } catch (Exception e) {
                System.err.printf("[gen] generation failed for %s: %s%n", request.name(), e);
                request.future().completeExceptionally(e);
                userRegistry.discard(request);
            }
        }
    }
}
