package server;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.model.GenRequest;
import server.model.Response;
import server.net.NioServer;
import server.net.Sender;
import server.service.Generator;
import server.service.KeyIssuer;
import server.service.UserRegistry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сквозной тест: поднимается весь конвейер (NioServer -> Generator -> Sender)
 * на свободном порту, клиентские сценарии идут через настоящий TCP-сокет.
 * Ключи в тесте 1024-битные — генерация укладывается в миллисекунды.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class EndToEndTest {

    private static final String ISSUER = "CN=Test CA";
    private static final int TEST_KEY_SIZE = 1024;

    private record ClientReply(byte[] keyPem, byte[] publicKeyPem, byte[] certificatePem) {
    }

    @Test
    void pipelineServesNormalSlowAbortedAndConcurrentClients() throws Exception {
        KeyPair ca = generateKeyPair(2048);
        int port = startServer(ca);

        // 1. обычный запрос: приходит валидный сертификат на запрошенное имя
        ClientReply first = request(port, "alice", 0);
        assertValidReply(first, "alice");

        // 2. повторный запрос — тот же самый ответ (кэш)
        ClientReply cached = request(port, "alice", 0);
        assertArrayEquals(first.keyPem(), cached.keyPem());
        assertArrayEquals(first.publicKeyPem(), cached.publicKeyPem());
        assertArrayEquals(first.certificatePem(), cached.certificatePem());

        // 3. «медленный» клиент: пауза между запросом и чтением не ломает протокол
        ClientReply slow = request(port, "slow", 500);
        assertValidReply(slow, "slow");

        // 4. «аварийный» клиент: закрыл сокет не читая ответ, сервер продолжает работать
        abortRequest(port, "dave");
        ClientReply afterAbort = request(port, "dave", 0);
        assertValidReply(afterAbort, "dave");

        // 5. гонка одинаковых имён: все получают одну и ту же пару
        List<ClientReply> concurrent = requestConcurrently(port, "carol", 5);
        for (ClientReply reply : concurrent) {
            assertValidReply(reply, "carol");
            assertArrayEquals(concurrent.get(0).keyPem(), reply.keyPem());
            assertArrayEquals(concurrent.get(0).publicKeyPem(), reply.publicKeyPem());
            assertArrayEquals(concurrent.get(0).certificatePem(), reply.certificatePem());
        }
    }

    // --- запуск сервера ---

    private static int startServer(KeyPair ca) throws Exception {
        int port = freePort();
        LinkedBlockingQueue<GenRequest> requestQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Response> responseQueue = new LinkedBlockingQueue<>();
        UserRegistry registry = new UserRegistry(requestQueue);
        KeyIssuer issuer = new KeyIssuer(ISSUER, ca.getPrivate(), TEST_KEY_SIZE);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        executor.execute(new Generator(requestQueue, registry, issuer));
        executor.execute(new Sender(responseQueue));
        Thread reactor = new Thread(() -> {
            try {
                new NioServer(port, registry, responseQueue).run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "reactor-test");
        reactor.setDaemon(true);
        reactor.start();
        waitUntilListening(port);
        return port;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitUntilListening(int port) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Socket probe = new Socket("localhost", port)) {
                return;
            } catch (IOException notReadyYet) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
        }
        throw new IllegalStateException("server did not start listening on port " + port);
    }

    // --- клиентские сценарии (тот же протокол, что у client.ClientMain) ---

    private static ClientReply request(int port, String name, long delayMillis) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout((int) TimeUnit.MINUTES.toMillis(2));
            socket.getOutputStream().write((name + "\0").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            if (delayMillis > 0) {
                TimeUnit.MILLISECONDS.sleep(delayMillis);
            }
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int keyLength = in.readInt();
            byte[] keyPem = readFully(in, keyLength);
            int publicKeyLength = in.readInt();
            byte[] publicKeyPem = readFully(in, publicKeyLength);
            int certificateLength = in.readInt();
            byte[] certificatePem = readFully(in, certificateLength);
            return new ClientReply(keyPem, publicKeyPem, certificatePem);
        }
    }

    private static void abortRequest(int port, String name) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write((name + "\0").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } // сокет закрывается сразу, не читая ответ
    }

    private static List<ClientReply> requestConcurrently(int port, String name, int clients) throws Exception {
        List<ClientReply> replies = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(clients);
        for (int i = 0; i < clients; i++) {
            new Thread(() -> {
                try {
                    replies.add(request(port, name, 0));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(done.await(1, TimeUnit.MINUTES), "не все параллельные клиенты получили ответ");
        return replies;
    }

    private static byte[] readFully(DataInputStream in, int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length < length) {
            throw new EOFException("connection closed before the full response arrived");
        }
        return data;
    }

    // --- проверки ответа ---

    private static void assertValidReply(ClientReply reply, String expectedName) throws Exception {
        String keyPem = new String(reply.keyPem(), StandardCharsets.US_ASCII);
        String certificatePem = new String(reply.certificatePem(), StandardCharsets.US_ASCII);
        assertTrue(keyPem.contains("PRIVATE KEY-----"), "ключ не PEM: " + keyPem);
        assertTrue(certificatePem.contains("BEGIN CERTIFICATE"), "сертификат не PEM: " + certificatePem);

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(reply.certificatePem()));
        assertEquals("CN=" + expectedName, certificate.getSubjectX500Principal().getName());
        assertEquals(ISSUER, certificate.getIssuerX500Principal().getName());

        String publicKeyPem = new String(reply.publicKeyPem(), StandardCharsets.US_ASCII);
        assertTrue(publicKeyPem.contains("PUBLIC KEY-----"), "публичный ключ не PEM: " + publicKeyPem);
        assertArrayEquals(parsePublicKey(publicKeyPem).getEncoded(), certificate.getPublicKey().getEncoded());
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            SubjectPublicKeyInfo info = (SubjectPublicKeyInfo) parser.readObject();
            return new JcaPEMKeyConverter().getPublicKey(info);
        }
    }

    private static KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        return generator.generateKeyPair();
    }

    private static final class UncheckedIOException extends RuntimeException {
        private UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
