package server.service;

import server.model.GenRequest;
import server.model.User;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш выпущенных пользователей:
 * повторный запрос с тем же именем, даже пришедший до завершения генерации,
 * получает тот же future, поэтому пара ключей создаётся ровно один раз.
 * Единственная ответственность (SRP) — дедупликация запросов по имени.
 */
public class UserRegistry {

    private final Map<String, CompletableFuture<User>> usersByName = new ConcurrentHashMap<>();
    private final BlockingQueue<GenRequest> requestQueue;

    public UserRegistry(BlockingQueue<GenRequest> requestQueue) {
        this.requestQueue = requestQueue;
    }

    public CompletableFuture<User> getOrCreate(String name) {
        return usersByName.computeIfAbsent(name, n -> {
            CompletableFuture<User> future = new CompletableFuture<>();
            requestQueue.add(new GenRequest(n, future));
            return future;
        });
    }

    /** Убирает неудавшийся запрос, чтобы имя можно было запросить повторно. */
    public void discard(GenRequest request) {
        usersByName.remove(request.name(), request.future());
    }
}
