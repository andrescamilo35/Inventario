package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LowStockAlertTest {

    private MutableClock clock;
    private RecordingAlertListener listener;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt("2026-01-01T12:00:00Z");
        listener = new RecordingAlertListener();
        service = Inventory.create(clock, listener);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void noAlertWhileMoreThanFiveUnitsAreAvailable() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(List.of(), listener.alerts());
    }

    @Test
    void alertWhenStockIsAddedAtFiveOrLess() {
        service.addStock("SKU-1", 5);

        assertEquals(List.of("SKU-1:5"), listener.alerts());
    }

    @Test
    void alertWhenReservationLeavesFiveUnits() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(List.of("SKU-1:5"), listener.alerts());
    }

    @Test
    void alertReportsActualUnitsWhenThresholdIsSkipped() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 9);

        assertEquals(List.of("SKU-1:1"), listener.alerts());
    }

    @Test
    void alertIsNotRepeatedByFurtherReservations() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.reserve("ORDER-3", "SKU-1", 1);

        assertEquals(List.of("SKU-1:5"), listener.alerts());
    }

    @Test
    void alertIsNotRepeatedByRestockThatStaysLow() {
        service.addStock("SKU-1", 3);
        service.addStock("SKU-1", 1);
        service.addStock("SKU-1", 1);

        assertEquals(List.of("SKU-1:3"), listener.alerts());
    }

    @Test
    void alertCanBeSentAgainAfterRestockAboveThreshold() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 10);

        assertEquals(List.of("SKU-1:5", "SKU-1:5"), listener.alerts());
    }

    @Test
    void expiredReservationDoesNotResetTheAlert() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);
        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.available("SKU-1"));

        service.reserve("ORDER-2", "SKU-1", 5);

        assertEquals(List.of("SKU-1:5"), listener.alerts(), "only a restock resets the alert");
    }

    @Test
    void confirmingDoesNotSendAlerts() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");

        assertEquals(List.of(), listener.alerts());
    }

    @Test
    void eachProductIsAlertedIndependently() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-1", 2);
        service.addStock("SKU-2", 4);

        assertEquals(List.of("SKU-1:2", "SKU-2:4"), listener.alerts());
    }

    @Test
    void failingAlertChannelDoesNotBreakInventoryOperations() {
        InventoryService failing = Inventory.create(clock, (sku, available) -> {
            throw new IllegalStateException("mail server down");
        });
        failing.registerProduct("SKU-1", ProductCategory.STANDARD);

        failing.addStock("SKU-1", 3);
        assertEquals(3, failing.available("SKU-1"), "stock is added exactly once");

        failing.addStock("SKU-1", 10);
        failing.reserve("ORDER-1", "SKU-1", 10);
        assertEquals(3, failing.available("SKU-1"), "reservation is kept");
    }
}
