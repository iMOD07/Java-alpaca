package com.alpaca.trading.broker;
import java.util.function.DoubleConsumer;

public interface AlpacaStream {
    void watchTrigger(String symbol, double trigger, DoubleConsumer onPrice); // onPrice receives last price
    void close();
}