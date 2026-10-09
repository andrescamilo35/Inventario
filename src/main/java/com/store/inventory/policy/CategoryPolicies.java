package com.store.inventory.policy;

import com.store.inventory.api.ProductCategory;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    public CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        List<ProductCategory> missing = Arrays.stream(ProductCategory.values())
                .filter(category -> !policies.containsKey(category) || policies.get(category) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing policy for categories: " + missing);
        }
        this.policies = new EnumMap<>(policies);
    }

    public static CategoryPolicies defaults() {
        Map<ProductCategory, CategoryPolicy> policies = new EnumMap<>(ProductCategory.class);
        policies.put(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)));
        policies.put(ProductCategory.PRE_ORDER, CategoryPolicy.unlimited(Duration.ofHours(24)));
        policies.put(ProductCategory.FLASH_SALE, CategoryPolicy.limited(Duration.ofMinutes(5), 2));
        return new CategoryPolicies(policies);
    }

    public CategoryPolicy of(ProductCategory category) {
        return policies.get(category);
    }
}
