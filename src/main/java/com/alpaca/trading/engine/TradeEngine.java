package com.alpaca.trading.engine;

import com.alpaca.trading.config.AppConfig;
import com.alpaca.trading.model.*;
import com.alpaca.trading.store.SignalStore;
import com.alpaca.trading.broker.AlpacaRest;
import com.alpaca.trading.broker.AlpacaStream;

public class TradeEngine {
    private final AppConfig cfg;
    private final SignalStore store;
    private final AlpacaRest rest;
    private final AlpacaStream stream;

    public TradeEngine(AppConfig cfg, SignalStore store, AlpacaRest rest, AlpacaStream stream){
        this.cfg = cfg; this.store = store; this.rest = rest; this.stream = stream;
    }

    public void armAndWait(TradeSignal s){
        // TODO: Subscribe on price, check guardrails, calculate qty = ceil(200 / trigger)
        // On breakout -> Submit buy -> After fill, place OCO -> Update status
    }
}
