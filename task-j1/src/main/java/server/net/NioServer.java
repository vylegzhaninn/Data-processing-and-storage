package server.net;

import server.model.Response;
import server.service.UserRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;

/**
 * Коммуникационная нить — Reactor: selector-петля на прием соединений и чтение
 * запросов. Имя после разбора уходит в UserRegistry (очередь генерации); доставка
 * результата происходит через очередь ответов, которую разбирают передающие нити
 * (Sender).
 */
public class NioServer {

    private final int port;
    private final UserRegistry userRegistry;
    private final BlockingQueue<Response> responseQueue;

    public NioServer(int port, UserRegistry userRegistry, BlockingQueue<Response> responseQueue) {
        this.port = port;
        this.userRegistry = userRegistry;
        this.responseQueue = responseQueue;
    }

    public void run() throws IOException {
        try (Selector selector = Selector.open(); ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(port));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
            System.out.printf("[net] listening on port %d%n", port);

            ByteBuffer readBuffer = ByteBuffer.allocate(4096);
            while (true) {
                selector.select();
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    try {
                        if (key.isAcceptable()) {
                            accept(selector, server);
                        } else if (key.isReadable()) {
                            read(key, readBuffer);
                        }
                    } catch (IOException e) {
                        close(key);
                    }
                }
            }
        }
    }

    private void accept(Selector selector, ServerSocketChannel server) throws IOException {
        SocketChannel channel = server.accept();
        if (channel != null) {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ, new Connection());
        }
    }

    private void read(SelectionKey key, ByteBuffer readBuffer) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();

        readBuffer.clear();
        if (channel.read(readBuffer) < 0) {
            close(key);
            return;
        }
        readBuffer.flip();

        for (String name : connection.extractNames(readBuffer)) {
            userRegistry.getOrCreate(name).whenComplete((user, error) ->
                    responseQueue.add(new Response(channel, connection.writeLock, error == null ? user : null)));
        }
    }

    private void close(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
        }
        key.cancel();
    }
}
