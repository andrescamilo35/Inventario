package com.store.inventory.service;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.policy.CategoryPolicy;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryInventoryService implements InventoryService {

    public static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;

    private static final System.Logger LOGGER = System.getLogger(InMemoryInventoryService.class.getName());

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final CategoryPolicies policies;
    private final int lowStockThreshold;

    private final Object lock = new Object();
    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Reservation> activeReservations = new HashMap<>();
    private final Map<String, Reservation> confirmedReservations = new HashMap<>();

    public InMemoryInventoryService(Clock clock, StockAlertListener alertListener) {
        this(clock, alertListener, CategoryPolicies.defaults(), DEFAULT_LOW_STOCK_THRESHOLD);
    }

    public InMemoryInventoryService(Clock clock,
                                    StockAlertListener alertListener,
                                    CategoryPolicies policies,
                                    int lowStockThreshold) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
        this.policies = Objects.requireNonNull(policies, "policies");
        if (lowStockThreshold < 0) {
            throw new IllegalArgumentException("Low stock threshold cannot be negative: " + lowStockThreshold);
        }
        this.lowStockThreshold = lowStockThreshold;
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        synchronized (lock) {
            Product existing = products.get(sku);
            if (existing == null) {
                products.put(sku, new Product(sku, category));
            } else {
                existing.changeCategory(category);
            }
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        requirePositive(quantity);
        Optional<LowStockAlert> alert;
        synchronized (lock) {
            Product product = products.get(sku);
            if (product == null) {
                throw new IllegalArgumentException("Product is not registered: " + sku);
            }
            releaseExpired(product, clock.instant());
            product.addStock(quantity);
            if (product.available() > lowStockThreshold) {
                product.resetLowStockAlert();
            }
            alert = checkLowStock(product);
        }
        alert.ifPresent(this::publish);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requirePositive(quantity);
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        Reservation reservation;
        Optional<LowStockAlert> alert;
        synchronized (lock) {
            Instant now = clock.instant();
            Optional<Reservation> previous = findOrder(orderId, now);
            if (previous.isPresent()) {
                return replay(previous.get(), sku, quantity);
            }
            Product product = products.get(sku);
            if (product == null) {
                throw new InsufficientStockException(sku, quantity, 0);
            }
            CategoryPolicy policy = policies.of(product.category());
            if (policy.exceedsOrderLimit(quantity)) {
                throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder().getAsInt());
            }
            releaseExpired(product, now);
            int available = product.available();
            if (quantity > available) {
                throw new InsufficientStockException(sku, quantity, available);
            }
            reservation = new Reservation(orderId, sku, quantity, now.plus(policy.paymentWindow()));
            product.hold(reservation);
            activeReservations.put(orderId, reservation);
            alert = checkLowStock(product);
        }
        alert.ifPresent(this::publish);
        return reservation;
    }

    @Override
    public void confirm(String orderId) {
        synchronized (lock) {
            Instant now = clock.instant();
            Reservation reservation = activeReservations.get(orderId);
            if (reservation == null || isExpired(reservation, now)) {
                if (reservation != null) {
                    releaseExpired(products.get(reservation.sku()), now);
                }
                throw new IllegalStateException("No active reservation for order: " + orderId);
            }
            products.get(reservation.sku()).sell(reservation);
            activeReservations.remove(orderId);
            confirmedReservations.put(orderId, reservation);
        }
    }

    @Override
    public int available(String sku) {
        synchronized (lock) {
            Product product = products.get(sku);
            if (product == null) {
                return 0;
            }
            releaseExpired(product, clock.instant());
            return product.available();
        }
    }

    private Optional<Reservation> findOrder(String orderId, Instant now) {
        Reservation confirmed = confirmedReservations.get(orderId);
        if (confirmed != null) {
            return Optional.of(confirmed);
        }
        Reservation active = activeReservations.get(orderId);
        if (active != null && isExpired(active, now)) {
            releaseExpired(products.get(active.sku()), now);
            return Optional.empty();
        }
        return Optional.ofNullable(active);
    }

    private static Reservation replay(Reservation previous, String sku, int quantity) {
        if (previous.sku().equals(sku) && previous.quantity() == quantity) {
            return previous;
        }
        throw new IllegalArgumentException("Order " + previous.orderId() + " already has a reservation of "
                + previous.quantity() + " x " + previous.sku());
    }

    private void releaseExpired(Product product, Instant now) {
        for (Reservation expired : product.releaseExpired(now)) {
            activeReservations.remove(expired.orderId(), expired);
        }
    }

    private Optional<LowStockAlert> checkLowStock(Product product) {
        int available = product.available();
        if (available <= lowStockThreshold && product.markLowStockAlerted()) {
            return Optional.of(new LowStockAlert(product.sku(), available));
        }
        return Optional.empty();
    }

    private void publish(LowStockAlert alert) {
        try {
            alertListener.onLowStock(alert.sku(), alert.availableUnits());
        } catch (RuntimeException e) {
            LOGGER.log(Level.ERROR, "Low stock alert for " + alert.sku() + " could not be delivered", e);
        }
    }

    private static boolean isExpired(Reservation reservation, Instant now) {
        return !now.isBefore(reservation.expiresAt());
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got " + quantity);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private record LowStockAlert(String sku, int availableUnits) {
    }
}
