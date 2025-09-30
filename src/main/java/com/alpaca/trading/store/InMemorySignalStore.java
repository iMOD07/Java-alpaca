package com.alpaca.trading.store;
import com.alpaca.trading.model.*;
import java.util.*;

public class InMemorySignalStore implements SignalStore {
    private final Map<String, TradeSignal> byId = new HashMap<>();
    private final Set<String> hashes = new HashSet<>();
    public boolean saveIfNew(TradeSignal s, String messageHash) {
        if (hashes.contains(messageHash)) return false;
        hashes.add(messageHash); byId.put(s.id, s); return true;
    }
    public Optional<TradeSignal> findById(String id){ return Optional.ofNullable(byId.get(id)); }
    public void updateStatus(String id, SignalStatus status){ findById(id).ifPresent(x -> x.status = status); }
}
