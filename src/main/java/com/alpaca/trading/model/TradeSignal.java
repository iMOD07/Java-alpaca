package com.alpaca.trading.model;

import java.time.Instant;

public class TradeSignal {
    public final String id; // UUID/Hash
    public final String symbol;
    public final double trigger;
    public final double stopLoss;
    public final Instant createdAt;
    public SignalStatus status = SignalStatus.PENDING;

    // This matches the call you have in SignalParser:
    // new TradeSignal(id, symbol, trigger, stopLoss, createdAt)
    public TradeSignal(String id, String symbol, double trigger, double stopLoss, Instant createdAt) {
        this.id = id;
        this.symbol = symbol;
        this.trigger = trigger;
        this.stopLoss = stopLoss;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }
    public TradeSignal(String symbol, double trigger, double stopLoss) {
        this(java.util.UUID.randomUUID().toString(), symbol, trigger, stopLoss, Instant.now());
    }
}