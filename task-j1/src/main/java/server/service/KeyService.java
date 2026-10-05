package server.service;

import server.model.User;

import java.util.concurrent.CompletableFuture;

/**
 * Абстракция домена, от которой зависит транспортный слой (DIP): транспорт не знает,
 * как именно выдаются пользователи — из кэша, генерацией или как-то ещё.
 */
public interface KeyService {

    /** Возвращает future на пользователя по имени; генерация запускается не более одного раза. */
    CompletableFuture<User> getOrCreate(String name);
}
