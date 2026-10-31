package server.model;

import java.nio.channels.SocketChannel;

/**
 * Запрос на передачу результата клиенту (Producer–Consumer, producer стороны генераторов).
 * user == null означает ошибку генерации: клиенту уходит кадр из трёх нулевых длин (12 нулевых байтов),
 * после чего канал закрывается. writeLock сериализует запись ответов в один канал.
 */
public record Response(
        SocketChannel channel,
        Object writeLock,
        User user
) {
}
