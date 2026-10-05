package server.model;

import java.util.concurrent.CompletableFuture;

/**
 * Запрос в очереди генерации: имя (что сгенерировать) и future, в который
 * генерирующая нить положит результат, когда он будет готов.
 */
public record GenRequest(
        String name,
        CompletableFuture<User> future
) {
}
