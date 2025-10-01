package com.alpaca.trading.service;

import com.alpaca.trading.broker.AlpacaClient;
import com.alpaca.trading.model.SignalStatus;
import com.alpaca.trading.model.TradeSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TradeEngine {

    private final AlpacaClient alpaca;
    private final double positionUsd;
    private final double tpPct;
    private final boolean extendedHours;

    public TradeEngine(
            AlpacaClient alpaca,
            @Value("${trade.position-usd}") double positionUsd,
            @Value("${trade.take-profit-pct}") double tpPct,
            @Value("${trade.extended-hours}") boolean extendedHours
    ){
        this.alpaca = alpaca;
        this.positionUsd = positionUsd;
        this.tpPct = tpPct;
        this.extendedHours = extendedHours;
    }

    public int computeQty(double trigger){
        return Math.max(1, (int)Math.ceil(positionUsd / trigger));
    }

    // Later: Streaming monitoring. Now we'll treat it as if the price arrived.

    public void simulateTriggerAndExecute(TradeSignal s) {
        int qty = computeQty(s.trigger);
        double limitPrice = s.trigger * 1.005; // Initial slippage 0.5% (adjustable)
       // String orderId = alpaca.submitBuyLimit(s.symbol, qty, limitPrice, extendedHours);
        s.status = SignalStatus.SUBMITTED;

        // After the Fill (later we hear it from the WebSocket), now we calculate the TP from the actual execution price.
        // Initially we calculate it from the trigger of the experiment:
        double tp = s.trigger * (1.0 + tpPct / 100.0);
        // alpaca.placeExitOco(s.symbol, qty, tp, s.stopLoss);
    }

}

