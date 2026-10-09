package com.store.inventory.policy;

import com.store.inventory.api.ProductCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryPoliciesTest {

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void defaultsDefineRulesForEveryCategory(ProductCategory category) {
        assertNotNull(CategoryPolicies.defaults().of(category),
                "add the rules of " + category + " to CategoryPolicies.defaults()");
    }

    @Test
    void defaultsMatchTheBusinessTable() {
        CategoryPolicies policies = CategoryPolicies.defaults();

        assertEquals(CategoryPolicy.unlimited(Duration.ofMinutes(15)), policies.of(ProductCategory.STANDARD));
        assertEquals(CategoryPolicy.unlimited(Duration.ofHours(24)), policies.of(ProductCategory.PRE_ORDER));
        assertEquals(CategoryPolicy.limited(Duration.ofMinutes(5), 2), policies.of(ProductCategory.FLASH_SALE));
    }

    @Test
    void missingCategoryIsRejectedAtStartup() {
        Map<ProductCategory, CategoryPolicy> incomplete = new EnumMap<>(ProductCategory.class);
        incomplete.put(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new CategoryPolicies(incomplete));
        assertTrue(error.getMessage().contains("FLASH_SALE"));
    }

    @Test
    void limitIsInclusive() {
        CategoryPolicy policy = CategoryPolicy.limited(Duration.ofMinutes(5), 2);

        assertFalse(policy.exceedsOrderLimit(2));
        assertTrue(policy.exceedsOrderLimit(3));
        assertFalse(CategoryPolicy.unlimited(Duration.ofMinutes(5)).exceedsOrderLimit(Integer.MAX_VALUE));
    }

    @Test
    void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.unlimited(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.limited(Duration.ofMinutes(5), 0));
        assertThrows(NullPointerException.class, () -> new CategoryPolicy(null, OptionalInt.empty()));
    }
}
