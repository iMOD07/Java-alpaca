package com.alpaca.trading;
import java.util.List;
import java.util.Optional;

public class SignalExtraction {
    // Basic fields
    public String ticker;
    public Double trigger;
    public Double stop;

    // Optional fields
    public Optional<String> side; // "buy" | "sell"
    public Optional<Boolean> extended_hours;
    public List<Double> targets;

    // For explanation or warning (optional)
    public Optional<String> reason;
}
