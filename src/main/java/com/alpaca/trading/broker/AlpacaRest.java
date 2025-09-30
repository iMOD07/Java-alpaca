package com.alpaca.trading.broker;

import com.alpaca.trading.config.AppConfig;

public class AlpacaRest {
    private final AppConfig cfg;
    public AlpacaRest(AppConfig cfg){ this.cfg = cfg; }

    public String submitBuyMarketableLimit(String symbol, int qty, double limitPrice, boolean extended) {
        // TODO: REST call to make Limit order executable
        return "ORDER_ID_PLACEHOLDER";
    }

    public void placeExitOCO(String symbol, int qty, double tpLimit, double slStop) {
        // TODO: Calls REST for OCO (TP/SL) orders after purchase execution
    }
}