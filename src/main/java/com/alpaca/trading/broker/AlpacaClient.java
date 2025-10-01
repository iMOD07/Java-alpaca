package com.alpaca.trading.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class AlpacaClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaClient.class);
    private final WebClient client;

    public AlpacaClient(
            @Value("${alpaca.base-url}") String baseUrl,
            @Value("${alpaca.key}") String key,
            @Value("${alpaca.secret}") String secret
    ) {
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", key)
                .defaultHeader("APCA-API-SECRET-KEY", secret)
                .build();
    }

    /** Sends a Bracket command: Limit entry on trigger, constant TP = trigger * 1.05, SL from recommendation. */
    public Map<String, Object> submitBracketLimitAtTrigger(
            String symbol,
            int qty,
            double trigger,
            double stopLoss,
            boolean extended,
            String clientOrderId
    ) {
        BigDecimal trig = bd(trigger);
        BigDecimal tp   = bd(trigger * 1.05); // Fixed on the trigger
        BigDecimal sl   = bd(stopLoss);

        var body = Map.of(
                "symbol", symbol,
                "qty", String.valueOf(qty),
                "side", "buy",
                "type", "limit",
                "time_in_force", "gtc",
                "limit_price", trig,
                "extended_hours", extended,
                "client_order_id", clientOrderId,
                "order_class", "bracket",
                "take_profit", Map.of("limit_price", tp),
                "stop_loss",   Map.of("stop_price", sl)
        );

        log.info("Submitting bracket order: {}", body);

        return client.post()
                .uri("/v2/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnNext(resp -> log.info("Alpaca response: {}", resp))
                .onErrorResume(ex -> {
                    log.error("Alpaca order error: {}", ex.toString());
                    return Mono.just(Map.of("error", ex.getMessage()));
                })
                .block();
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
