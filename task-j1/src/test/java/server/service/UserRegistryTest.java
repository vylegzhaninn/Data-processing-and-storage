package server.service;

import org.junit.jupiter.api.Test;
import server.model.GenRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class UserRegistryTest {

    private final LinkedBlockingQueue<GenRequest> requestQueue = new LinkedBlockingQueue<>();
    private final UserRegistry registry = new UserRegistry(requestQueue);

    @Test
    void sameNameReusesFutureAndEnqueuesRequestOnce() throws Exception {
        CompletableFuture<server.model.User> first = registry.getOrCreate("alice");
        CompletableFuture<server.model.User> second = registry.getOrCreate("alice");

        assertSame(first, second);
        GenRequest request = requestQueue.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("alice", request.name());
        assertSame(first, request.future());
        assertNull(requestQueue.poll(), "повторное имя не должно ставить второй запрос в очередь");
    }

    @Test
    void differentNamesGetDifferentFuturesAndRequests() throws Exception {
        CompletableFuture<server.model.User> alice = registry.getOrCreate("alice");
        CompletableFuture<server.model.User> bob = registry.getOrCreate("bob");

        assertNotSame(alice, bob);
        assertEquals(2, requestQueue.size());
        assertEquals("alice", requestQueue.poll().name());
        assertEquals("bob", requestQueue.poll().name());
    }

    @Test
    void discardRemovesFailedRequestSoNameCanBeRequestedAgain() throws Exception {
        CompletableFuture<server.model.User> failed = registry.getOrCreate("carol");
        GenRequest failedRequest = requestQueue.poll(1, TimeUnit.SECONDS);

        registry.discard(failedRequest);

        CompletableFuture<server.model.User> retry = registry.getOrCreate("carol");
        assertNotSame(failed, retry);
        GenRequest retryRequest = requestQueue.poll(1, TimeUnit.SECONDS);
        assertNotNull(retryRequest);
        assertSame(retry, retryRequest.future());
        assertNull(requestQueue.poll());
    }
}
