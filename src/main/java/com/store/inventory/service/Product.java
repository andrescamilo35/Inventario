package com.store.inventory.service;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

final class Product {

    private static final Comparator<Reservation> BY_EXPIRATION =
            Comparator.comparing(Reservation::expiresAt).thenComparing(Reservation::orderId);

    private final String sku;
    private ProductCategory category;
    private int stock;
    private int reservedUnits;
    private boolean lowStockAlerted;
    private final NavigableSet<Reservation> reservationsByExpiration = new TreeSet<>(BY_EXPIRATION);

    Product(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    String sku() {
        return sku;
    }

    ProductCategory category() {
        return category;
    }

    void changeCategory(ProductCategory category) {
        this.category = category;
    }

    int available() {
        return stock - reservedUnits;
    }

    void addStock(int quantity) {
        try {
            stock = Math.addExact(stock, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stock of " + sku + " would overflow", e);
        }
    }

    void hold(Reservation reservation) {
        reservationsByExpiration.add(reservation);
        reservedUnits += reservation.quantity();
    }

    void sell(Reservation reservation) {
        release(reservation);
        stock -= reservation.quantity();
    }

    List<Reservation> releaseExpired(Instant now) {
        List<Reservation> expired = new ArrayList<>();
        while (!reservationsByExpiration.isEmpty()
                && !now.isBefore(reservationsByExpiration.first().expiresAt())) {
            Reservation reservation = reservationsByExpiration.first();
            release(reservation);
            expired.add(reservation);
        }
        return expired;
    }

    boolean markLowStockAlerted() {
        if (lowStockAlerted) {
            return false;
        }
        lowStockAlerted = true;
        return true;
    }

    void resetLowStockAlert() {
        lowStockAlerted = false;
    }

    private void release(Reservation reservation) {
        reservationsByExpiration.remove(reservation);
        reservedUnits -= reservation.quantity();
    }
}
