package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderLimitTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
    }

    @Test
    void flashSaleAllowsUpToTwoUnits() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 10);

        service.reserve("ORDER-1", "SKU-FLASH", 2);

        assertEquals(8, service.available("SKU-FLASH"));
    }

    @Test
    void flashSaleRejectsThreeUnitsWithoutReserving() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 10);

        OrderLimitExceededException error = assertThrows(OrderLimitExceededException.class,
                () -> service.reserve("ORDER-1", "SKU-FLASH", 3));

        assertEquals("Order limit for SKU-FLASH is 2 units, requested 3", error.getMessage());
        assertEquals(10, service.available("SKU-FLASH"));
    }

    @Test
    void orderLimitIsReportedBeforeMissingStock() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-FLASH", 3));
    }

    @Test
    void limitIsPerOrderNotPerCustomer() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 10);

        service.reserve("ORDER-1", "SKU-FLASH", 2);
        service.reserve("ORDER-2", "SKU-FLASH", 2);

        assertEquals(6, service.available("SKU-FLASH"));
    }

    @ParameterizedTest
    @EnumSource(value = ProductCategory.class, names = {"STANDARD", "PRE_ORDER"})
    void categoriesWithoutLimitAcceptLargeOrders(ProductCategory category) {
        service.registerProduct("SKU-1", category);
        service.addStock("SKU-1", 1_000);

        service.reserve("ORDER-1", "SKU-1", 1_000);

        assertEquals(0, service.available("SKU-1"));
    }
}
