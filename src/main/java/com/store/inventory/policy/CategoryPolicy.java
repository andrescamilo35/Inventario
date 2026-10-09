package com.store.inventory.policy;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

public record CategoryPolicy(Duration paymentWindow, OptionalInt maxUnitsPerOrder) {

    public CategoryPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        Objects.requireNonNull(maxUnitsPerOrder, "maxUnitsPerOrder");
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("Payment window must be positive: " + paymentWindow);
        }
        if (maxUnitsPerOrder.isPresent() && maxUnitsPerOrder.getAsInt() <= 0) {
            throw new IllegalArgumentException("Order limit must be positive: " + maxUnitsPerOrder.getAsInt());
        }
    }

    public static CategoryPolicy unlimited(Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, OptionalInt.empty());
    }

    public static CategoryPolicy limited(Duration paymentWindow, int maxUnitsPerOrder) {
        return new CategoryPolicy(paymentWindow, OptionalInt.of(maxUnitsPerOrder));
    }

    public boolean exceedsOrderLimit(int quantity) {
        return maxUnitsPerOrder.isPresent() && quantity > maxUnitsPerOrder.getAsInt();
    }
}
