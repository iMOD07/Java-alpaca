package com.alpaca.trading.parser;

import com.alpaca.trading.model.TradeSignal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class SignalParser {
    public Optional<TradeSignal> parseArabicFormat(String message) {
        // Later we add the regular expression, Now just the general structure.
        // Assume message contains: symbol in line, "on X exceed", "stop Y"
        return Optional.empty();
    }
    public static String hashMessage(String message){
        return Integer.toHexString(message.trim().hashCode());
    }
    public static TradeSignal draft(String symbol, double trigger, double sl){
        return new TradeSignal(UUID.randomUUID().toString(), symbol, trigger, sl, Instant.now());
    }
}
