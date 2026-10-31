package server.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Состояние клиентского соединения на стороне selector-нити: накопитель прочитанных
 * байтов (запрос — имя до нулевого байта) и замок, сериализующий запись ответов в канал.
 */
class Connection {

    final Object writeLock = new Object();

    private final ByteArrayOutputStream pendingNameBytes = new ByteArrayOutputStream();

    /** Поглощает прочитанные байты, возвращает имена, завершившиеся нулевым байтом. */
    List<String> extractNames(ByteBuffer src) {
        List<String> names = new ArrayList<>();
        while (src.hasRemaining()) {
            int readByte = src.get() & 0xFF;
            if (readByte == 0) {
                names.add(pendingNameBytes.toString(StandardCharsets.US_ASCII));
                pendingNameBytes.reset();
            } else {
                pendingNameBytes.write(readByte);
            }
        }
        return names;
    }
}
