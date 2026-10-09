package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void cannotReserveZeroOrNegativeUnits(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", quantity));
        assertEquals(5, service.available("SKU-1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void cannotAddZeroOrNegativeStock(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", quantity));
        assertEquals(5, service.available("SKU-1"));
    }

    @Test
    void cannotAddStockToUnknownProduct() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 5));
        assertEquals(0, service.available("UNKNOWN"));
    }

    @Test
    void cannotOverflowStock() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", Integer.MAX_VALUE));
        assertEquals(5, service.available("SKU-1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void orderIdIsRequired(String orderId) {
        assertThrows(IllegalArgumentException.class, () -> service.reserve(orderId, "SKU-1", 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void skuIsRequiredToRegister(String sku) {
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct(sku, ProductCategory.STANDARD));
    }

    @Test
    void categoryIsRequiredToRegister() {
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("SKU-2", null));
    }

    @Test
    void registeringAgainKeepsStockAndReservations() {
        service.reserve("ORDER-1", "SKU-1", 2);

        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertEquals(3, service.available("SKU-1"));
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void registeringWithNewCategoryAppliesItsRulesToNewReservations() {
        service.registerProduct("SKU-1", ProductCategory.FLASH_SALE);

        assertThrows(OrderLimitExceededException.class,
                () -> service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(5, service.available("SKU-1"));
    }
}
