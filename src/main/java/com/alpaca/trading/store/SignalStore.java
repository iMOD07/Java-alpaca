package com.alpaca.trading.store;
import com.alpaca.trading.model.TradeSignal;
import java.util.Optional;

public interface SignalStore {
    boolean saveIfNew(TradeSignal s, String messageHash);
    Optional<TradeSignal> findById(String id);
    void updateStatus(String id, com.alpaca.trading.model.SignalStatus status);
}
