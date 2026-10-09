package com.store.inventory;

import com.store.inventory.api.StockAlertListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class RecordingAlertListener implements StockAlertListener {

    private final List<String> alerts = new CopyOnWriteArrayList<>();

    @Override
    public void onLowStock(String sku, int availableUnits) {
        alerts.add(sku + ":" + availableUnits);
    }

    List<String> alerts() {
        return List.copyOf(alerts);
    }
}
