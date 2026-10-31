package server.net;

import server.model.Response;
import server.model.RSAKeys;
import server.model.User;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Передающая нить: забирает готовые ответы из очереди и дописывает их в каналы клиентов.
 * Неблокирующая запись; если буфер сокета переполнен (клиент не читает) — нить мягко
 * ждёт, но генерирующие нити на этом не блокируются. Медленный клиент, не забирающий
 * ответ дольше WRITE_TIMEOUT_MS, отключается сервером.
 */
public class Sender implements Runnable {

    private static final long IDLE_PAUSE_NANOS = 1_000_000;
    private static final long WRITE_TIMEOUT_MS = 120_000;

    private final BlockingQueue<Response> responseQueue;

    public Sender(BlockingQueue<Response> responseQueue) {
        this.responseQueue = responseQueue;
    }

    @Override
    public void run() {
        while (true) {
            try {
                send(responseQueue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void send(Response response) {
        SocketChannel channel = response.channel();
        try {
            ByteBuffer frame = ByteBuffer.wrap(encodeResponse(response.user()));
            long deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS;
            synchronized (response.writeLock()) {
                while (frame.hasRemaining()) {
                    if (channel.write(frame) == 0) {
                        if (System.currentTimeMillis() > deadline) {
                            throw new IOException("write timeout, client is not reading");
                        }
                        LockSupport.parkNanos(IDLE_PAUSE_NANOS);
                    }
                }
            }
            if (response.user() == null) {
                channel.close();
            }
        } catch (IOException e) {
            System.out.printf("[send] client gone: %s%n", e.getMessage());
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Кадр ответа: [длина приватного ключа][PEM][длина публичного ключа][PEM][длина сертификата][PEM]. */
    private static byte[] encodeResponse(User user) {
        if (user == null) {
            return new byte[12];
        }
        RSAKeys keys = user.keys();
        byte[] privateKey = keys.privateKey().getBytes(StandardCharsets.US_ASCII);
        byte[] publicKey = keys.publicKey().getBytes(StandardCharsets.US_ASCII);
        byte[] certificate = keys.certificate().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(12 + privateKey.length + publicKey.length + certificate.length)
                .putInt(privateKey.length).put(privateKey)
                .putInt(publicKey.length).put(publicKey)
                .putInt(certificate.length).put(certificate)
                .array();
    }
}
