package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderRetryTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt("2026-01-01T10:00:00Z");
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-1", 5);
        service.addStock("SKU-2", 5);
    }

    @Test
    void resentOrderReturnsTheSameReservation() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(1));
        Reservation second = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, second, "keeps the original expiration");
        assertEquals(2, service.available("SKU-1"));
    }

    @Test
    void resentOrderSucceedsEvenIfNoUnitsAreLeft() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 5);
        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 5));
    }

    @Test
    void activeOrderIdCannotBeReusedForADifferentReservation() {
        service.reserve("ORDER-1", "SKU-1", 2);

        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-2", 2));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(3, service.available("SKU-1"));
        assertEquals(5, service.available("SKU-2"));
    }

    @Test
    void expiredOrderIdCanReserveAgain() {
        service.reserve("ORDER-1", "SKU-1", 2);
        clock.advance(Duration.ofMinutes(15));

        Reservation again = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(new Reservation("ORDER-1", "SKU-1", 3, clock.instant().plus(Duration.ofMinutes(15))), again);
        assertEquals(2, service.available("SKU-1"));
    }

    @Test
    void resentOrderAfterPaymentDoesNotReserveUnitsAgain() {
        Reservation reserved = service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");

        Reservation late = service.reserve("ORDER-1", "SKU-1", 2);

        assertEquals(reserved, late);
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void paidOrderIdCannotBeReusedForADifferentReservation() {
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        clock.advance(Duration.ofDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-2", 1));
        assertEquals(5, service.available("SKU-2"));
    }
}
