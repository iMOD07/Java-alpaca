package com.alpaca.trading.service;

import com.alpaca.trading.model.TradeSignal;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SignalParser {
    private static final Pattern P_SYMBOL = Pattern.compile("^([A-Z.\\-]{1,10})$", Pattern.MULTILINE);
    private static final Pattern P_TRIGGER = Pattern.compile("(?i)(عند\\s*تجاوز|trigger|entry)\\s*[:\\-]*\\s*([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern P_SL = Pattern.compile("(?i)(وقف|stop)\\s*[:\\-]*\\s*([0-9]+(?:[.,][0-9]+)?)");

    public Optional<TradeSignal> parse(String message) {
        String msg = message.replace(',', '.');
        String symbol = findFirst(P_SYMBOL, msg);
        String tr = findFirst(P_TRIGGER, msg);
        String sl = findFirst(P_SL, msg);
        if (symbol == null || tr == null || sl == null) return Optional.empty();
        return Optional.of(new TradeSignal(symbol, Double.parseDouble(tr), Double.parseDouble(sl)));
    }

    private static String findFirst(Pattern p, String text){
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        if (m.groupCount() >= 2 && m.group(2) != null) return m.group(2); // Like trigger/stop: number in group 2
        if (m.groupCount() >= 1 && m.group(1) != null) return m.group(1); // Like symbol: in group 1
        return m.group(); // fallback safe
    }
}
