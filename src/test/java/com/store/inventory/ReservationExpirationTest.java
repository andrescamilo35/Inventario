package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReservationExpirationTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt("2026-01-01T12:00:00Z");
        service = Inventory.create(clock, (sku, available) -> { });
    }

    @ParameterizedTest(name = "{0} holds units for {1} minutes")
    @CsvSource({
            "STANDARD,   15",
            "PRE_ORDER,  1440",
            "FLASH_SALE, 5"
    })
    void reservationIsReleasedExactlyWhenPaymentWindowEnds(ProductCategory category, long minutes) {
        service.registerProduct("SKU-1", category);
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);

        clock.advance(Duration.ofMinutes(minutes).minusSeconds(1));
        assertEquals(3, service.available("SKU-1"), "still reserved one second before expiring");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(5, service.available("SKU-1"), "released when the window ends");
    }

    @Test
    void releasedUnitsCanBeReservedByAnotherOrder() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 3);
        service.reserve("ORDER-1", "SKU-1", 3);

        clock.advance(Duration.ofMinutes(15));

        service.reserve("ORDER-2", "SKU-1", 3);
        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void reservationsExpireIndependently() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 2);
        clock.advance(Duration.ofMinutes(10));
        service.reserve("ORDER-2", "SKU-1", 3);

        clock.advance(Duration.ofMinutes(5));
        assertEquals(7, service.available("SKU-1"), "only ORDER-1 expired");

        clock.advance(Duration.ofMinutes(10));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void expirationUsesCategoryAtReservationTime() {
        service.registerProduct("SKU-1", ProductCategory.PRE_ORDER);
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);

        service.registerProduct("SKU-1", ProductCategory.FLASH_SALE);
        clock.advance(Duration.ofMinutes(5));

        assertEquals(3, service.available("SKU-1"), "keeps the 24 hours it was given");
    }
}
