package com.alpaca.trading.broker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AlpacaClient {

    private final WebClient rest;

    public AlpacaClient(
            @Value("${alpaca.base-url}") String baseUrl,
            @Value("${alpaca.key}") String key,
            @Value("${alpaca.secret}") String secret
    ){
        this.rest = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", key)
                .defaultHeader("APCA-API-SECRET-KEY", secret)
                .build();
    }

    public String submitBuyLimit(String symbol, int qty, double limitPrice, boolean extended){
        // TODO: Send an executable Limit Purchase Order
        return "ORDER_ID_TODO";
    }

    public void placeExitOco(String symbol, int qty, double tpLimit, double slStop){
        // TODO: Send TP/SL orders as OCO (after execution)
    }
}
