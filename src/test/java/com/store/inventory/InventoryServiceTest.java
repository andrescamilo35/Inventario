package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryServiceTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void reservingReducesAvailableUnits() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        service.addStock("SKU-1", 2);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
    }

    @Test
    void confirmedUnitsStaySold() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void canReserveExactlyAllAvailableUnits() {
        service.addStock("SKU-1", 4);
        service.reserve("ORDER-1", "SKU-1", 4);
        assertEquals(0, service.available("SKU-1"));
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));
    }

    @Test
    void reservationCarriesOrderSkuQuantityAndExpiration() {
        MutableClock clock = MutableClock.startingAt("2026-01-01T12:00:00Z");
        InventoryService testService = Inventory.create(clock, (sku, available) -> { });
        testService.registerProduct("SKU-1", ProductCategory.STANDARD);
        testService.addStock("SKU-1", 5);

        Reservation reservation = testService.reserve("ORDER-1", "SKU-1", 2);

        assertEquals(new Reservation("ORDER-1", "SKU-1", 2, Instant.parse("2026-01-01T12:15:00Z")), reservation);
    }

    @Test
    void reservingUnknownProductFailsWithNoAvailableUnits() {
        InsufficientStockException error = assertThrows(InsufficientStockException.class,
                () -> service.reserve("ORDER-1", "UNKNOWN", 1));
        assertEquals("Insufficient stock for UNKNOWN: requested 1, available 0", error.getMessage());
    }

    @Test
    void availableIsZeroForUnknownProduct() {
        assertEquals(0, service.available("UNKNOWN"));
    }

    @Test
    void cannotConfirmSameOrderTwice() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void cannotConfirmUnknownOrder() {
        assertThrows(IllegalStateException.class, () -> service.confirm("NOPE"));
    }

    @Test
    void cannotConfirmExpiredReservation() {
        MutableClock clock = MutableClock.startingAt("2026-01-01T12:00:00Z");
        InventoryService testService = Inventory.create(clock, (sku, available) -> { });
        testService.registerProduct("SKU-1", ProductCategory.STANDARD);
        testService.addStock("SKU-1", 5);
        testService.reserve("ORDER-1", "SKU-1", 3);

        clock.advance(Duration.ofMinutes(16));

        assertThrows(IllegalStateException.class, () -> testService.confirm("ORDER-1"));
        assertEquals(5, testService.available("SKU-1"));
    }

    @Test
    void confirmingOneOrderDoesNotTouchOtherReservations() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.reserve("ORDER-2", "SKU-1", 3);

        service.confirm("ORDER-1");

        assertEquals(5, service.available("SKU-1"));
        service.confirm("ORDER-2");
        assertEquals(5, service.available("SKU-1"));
    }

    @Test
    void confirmedUnitsDoNotReturnWhenTimePasses() {
        MutableClock clock = MutableClock.startingAt("2026-01-01T12:00:00Z");
        InventoryService testService = Inventory.create(clock, (sku, available) -> { });
        testService.registerProduct("SKU-1", ProductCategory.STANDARD);
        testService.addStock("SKU-1", 5);
        testService.reserve("ORDER-1", "SKU-1", 2);
        testService.confirm("ORDER-1");

        clock.advance(Duration.ofDays(2));

        assertEquals(3, testService.available("SKU-1"));
    }
}
