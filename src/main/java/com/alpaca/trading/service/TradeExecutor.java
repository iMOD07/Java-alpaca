package com.alpaca.trading.service;

import com.alpaca.trading.broker.AlpacaClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TradeExecutor {

    private final AlpacaClient alpaca;
    private final double positionUsd;
    private final boolean extendedHours;

    public TradeExecutor(
            AlpacaClient alpaca,
            @Value("${trade.position-usd:200}") double positionUsd,
            @Value("${trade.extended-hours:true}") boolean extendedHours
    ) {
        this.alpaca = alpaca;
        this.positionUsd = positionUsd;
        this.extendedHours = extendedHours;
    }

    // Quantity calculation = ceil(200 / trigger)
    public int computeQty(double trigger){
        return Math.max(1, (int)Math.ceil(positionUsd / trigger));
    }

    // Call this method after you are sure that the price has reached/passed the trigger.
    public void executeBracketAtTrigger(String symbol, double trigger, double stopLoss) {
        int qty = computeQty(trigger);

        /*var resp = alpaca.submitBracketLimitAtTrigger(
                symbol,
                qty,
                trigger,
                stopLoss,
                extendedHours,
                // Make the client_order_id unique (from signal-id for example)
                symbol.toLowerCase() + "-trigger-" + System.currentTimeMillis()
        ); */

        // System.out.println("Order resp: " + resp);
    }
}
