package com.alpaca.trading.controller;

import com.alpaca.trading.service.AlpacaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class TradeController {
    private static final Logger log = LoggerFactory.getLogger(TradeController.class);
    private final AlpacaService alpaca;

    public TradeController(AlpacaService alpaca) {
        this.alpaca = alpaca;
    }

    @PostMapping("/trades")
    public ResponseEntity<?> receive(@RequestBody Map<String, Object> body) {
        log.info("✅ /trades received: {}", body);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) body.get("parsed");
            if (parsed == null) parsed = Map.of();

            String ticker = String.valueOf(parsed.getOrDefault("ticker", ""));
            Double trigger = toD(parsed.get("trigger"));
            Double stop = toD(parsed.get("stop"));

            if (ticker.isBlank() || trigger == null || stop == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "missing ticker/trigger/stop"));
            }

            //Applies protections and logic and launches the access controller
            alpaca.processSignal(ticker.toUpperCase(), trigger, stop);
            return ResponseEntity.ok(Map.of("status", "accepted", "ticker", ticker));
        } catch (Exception e) {
            log.error("Trade error", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private static Double toD(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }

    @GetMapping("/health")
    public String health() { return "OK"; }
}
