package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ConcurrencyTest {

    private static final int THREADS = 32;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @RepeatedTest(20)
    void concurrentCustomersNeverOversell() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 50);
        AtomicInteger reservedUnits = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(200, i -> () -> {
            int quantity = 1 + i % 3;
            try {
                service.reserve("ORDER-" + i, "SKU-1", quantity);
                reservedUnits.addAndGet(quantity);
            } catch (InsufficientStockException e) {
                rejected.incrementAndGet();
            }
            return null;
        });

        assertTrue(reservedUnits.get() <= 50, "reserved " + reservedUnits.get() + " of 50 units");
        assertEquals(50 - reservedUnits.get(), service.available("SKU-1"));
        assertTrue(rejected.get() > 0, "demand exceeded stock, so some orders must be rejected");
    }

    @RepeatedTest(20)
    void concurrentRetriesOfOneOrderReserveOnce() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        List<Reservation> results = runConcurrently(100, i -> () -> service.reserve("ORDER-1", "SKU-1", 3));

        assertEquals(1, new HashSet<>(results).size(), "every retry gets the same reservation");
        assertEquals(7, service.available("SKU-1"));
    }

    @RepeatedTest(20)
    void concurrentConfirmationsSellOnce() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);
        AtomicInteger confirmed = new AtomicInteger();

        runConcurrently(50, i -> () -> {
            try {
                service.confirm("ORDER-1");
                confirmed.incrementAndGet();
            } catch (IllegalStateException ignored) {
            }
            return null;
        });

        assertEquals(1, confirmed.get());
        assertEquals(6, service.available("SKU-1"));
    }

    @RepeatedTest(20)
    void lowStockAlertIsSentOnceUnderContention() throws Exception {
        RecordingAlertListener listener = new RecordingAlertListener();
        InventoryService service = Inventory.create(Clock.systemUTC(), listener);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 100);

        runConcurrently(100, i -> () -> service.reserve("ORDER-" + i, "SKU-1", 1));

        assertEquals(0, service.available("SKU-1"));
        assertEquals(1, listener.alerts().size(), "alerts: " + listener.alerts());
    }

    @Test
    void slowAlertChannelDoesNotBlockOtherOperations() throws Exception {
        CountDownLatch alertStarted = new CountDownLatch(1);
        CountDownLatch releaseAlert = new CountDownLatch(1);
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> {
            alertStarted.countDown();
            await(releaseAlert);
        });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 100);

        Future<?> slowAlert = executor.submit(() -> service.addStock("SKU-1", 1));
        assertTrue(alertStarted.await(5, TimeUnit.SECONDS));

        Future<Reservation> other = executor.submit(() -> service.reserve("ORDER-1", "SKU-2", 1));
        other.get(5, TimeUnit.SECONDS);
        assertEquals(99, service.available("SKU-2"));

        releaseAlert.countDown();
        slowAlert.get(5, TimeUnit.SECONDS);
    }

    private <T> List<T> runConcurrently(int tasks, TaskFactory<T> factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            Callable<T> task = factory.create(i);
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get(10, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                throw assertInstanceOf(Exception.class, e.getCause());
            }
        }
        return results;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface TaskFactory<T> {
        Callable<T> create(int index);
    }
}
