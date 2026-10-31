package client;

import java.io.DataInputStream;
import java.io.EOFException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientMain {

    private static final Path OUTPUT_DIR = Path.of("resources");

    /**
     * Аргументы: <имя> <хост или DNS-имя сервера> <порт> [--delay <сек>] [--abort]
     * --delay  — пауза между отправкой запроса и чтением ответа (медленный клиент);
     * --abort  — завершиться сразу после отправки запроса, не читая ответ.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: client <name> <host> <port> [--delay <seconds>] [--abort]");
            System.exit(2);
        }
        String name = args[0];
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int delaySeconds = 0;
        boolean abort = false;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--delay" -> delaySeconds = Integer.parseInt(args[++i]);
                case "--abort" -> abort = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(2);
                }
            }
        }

        try (Socket socket = new Socket(host, port)) {
            socket.getOutputStream().write((name + "\0").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            if (abort) {
                System.out.println("request sent, aborting without reading the response");
                System.exit(0);
            }
            if (delaySeconds > 0) {
                Thread.sleep(delaySeconds * 1000L);
            }

            DataInputStream serverResponse = new DataInputStream(socket.getInputStream());
            int keyLen = serverResponse.readInt();
            if (keyLen == 0) {
                System.err.println("server failed to generate keys");
                System.exit(1);
            }
            String privateKey = new String(readFully(serverResponse, keyLen), StandardCharsets.US_ASCII);
            int publicKeyLen = serverResponse.readInt();
            String publicKey = new String(readFully(serverResponse, publicKeyLen), StandardCharsets.US_ASCII);
            int certLen = serverResponse.readInt();
            String certificate = new String(readFully(serverResponse, certLen), StandardCharsets.US_ASCII);

            Files.createDirectories(OUTPUT_DIR);
            Files.writeString(OUTPUT_DIR.resolve(name + ".key"), privateKey);
            Files.writeString(OUTPUT_DIR.resolve(name + ".pub"), publicKey);
            Files.writeString(OUTPUT_DIR.resolve(name + ".crt"), certificate);
            System.out.println("saved " + OUTPUT_DIR.resolve(name + ".key") + ", "
                    + OUTPUT_DIR.resolve(name + ".pub") + " and " + OUTPUT_DIR.resolve(name + ".crt"));
        }
    }

    private static byte[] readFully(DataInputStream in, int length) throws Exception {
        byte[] data = in.readNBytes(length);
        if (data.length < length) {
            throw new EOFException("connection closed before the full response arrived");
        }
        return data;
    }
}
