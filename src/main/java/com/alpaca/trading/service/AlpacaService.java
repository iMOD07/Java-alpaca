package com.alpaca.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AlpacaService {
    private static final Logger log = LoggerFactory.getLogger(AlpacaService.class);

    @Value("${alpaca.base-url}")   private String tradingBase;
    @Value("${alpaca.data-url}")   private String dataBase;
    @Value("${alpaca.key-id}")     private String keyId;
    @Value("${alpaca.secret-key}") private String secretKey;

    // We read it as text first to avoid conversion failure due to inline comments
    @Value("${trading.extended-hours:true}") private String allowExtendedRaw;
    @Value("${trading.max-spread-bps:50}")  private String maxSpreadBpsRaw;
    @Value("${trading.max-gap-bps:100}")    private String maxGapBpsRaw;
    @Value("${trading.poll-ms:1000}")       private String pollMsRaw;
    @Value("${trading.timeout-sec:1800}")   private String timeoutSecRaw;

    //Values actually used after conversion
    private boolean allowExtended;
    private int maxSpreadBps;
    private int maxGapBps;
    private long pollMs;
    private long timeoutSec;

    private final RestTemplate http = new RestTemplate();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @PostConstruct
    private void initParsedProps() {
        this.allowExtended = parseBool(allowExtendedRaw, true);
        this.maxSpreadBps  = parseInt(maxSpreadBpsRaw, 50);
        this.maxGapBps     = parseInt(maxGapBpsRaw, 100);
        this.pollMs        = parseLong(pollMsRaw, 1000L);
        this.timeoutSec    = parseLong(timeoutSecRaw, 1800L);

        log.info("Trading params => extendedHours={}, maxSpreadBps={}, maxGapBps={}, pollMs={}, timeoutSec={}",
                allowExtended, maxSpreadBps, maxGapBps, pollMs, timeoutSec);
    }

    /* ====================== API helpers ====================== */
    private HttpEntity<?> entityWithAuth(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("APCA-API-KEY-ID", keyId);
        h.set("APCA-API-SECRET-KEY", secretKey);
        return new HttpEntity<>(body, h);
    }

    private Map<String,Object> getJson(String url) {
        ResponseEntity<Map> r = http.exchange(url, HttpMethod.GET, entityWithAuth(null), Map.class);
        return r.getBody() != null ? r.getBody() : Map.of();
    }

    private Map<String,Object> postJson(String url, Map<String, Object> body) {
        ResponseEntity<Map> r = http.exchange(url, HttpMethod.POST, entityWithAuth(body), Map.class);
        return r.getBody() != null ? r.getBody() : Map.of();
    }

    private Map<String,Object> getOrderById(String id) {
        return getJson(tradingBase + "/v2/orders/" + id);
    }

    private Map<String,Object> createOrder(Map<String,Object> order) {
        log.info("POST /v2/orders {}", order);
        return postJson(tradingBase + "/v2/orders", order);
    }

    private Map<String,Object> getClock() {
        return getJson(tradingBase + "/v2/clock");
    }

    /* ================== Data: latest quote =================== */
    public record Quote(double bid, double ask) {}
    private Optional<Quote> latestQuote(String symbol) {
        try {
            String url = dataBase + "/v2/stocks/" + symbol + "/quotes/latest";
            Map<String,Object> root = getJson(url);
            Object q = root.get("quote");
            if (q instanceof Map<?,?> m) {
                Double ap = pickDouble(m, "ap", "ask_price");
                Double bp = pickDouble(m, "bp", "bid_price");
                if (ap != null && bp != null) return Optional.of(new Quote(bp, ap));
            }
            Object quotes = root.get("quotes");
            if (quotes instanceof Map<?,?> mq) {
                Object node = mq.get(symbol);
                if (node instanceof Map<?,?> m) {
                    Double ap = pickDouble(m, "ap", "ask_price");
                    Double bp = pickDouble(m, "bp", "bid_price");
                    if (ap != null && bp != null) return Optional.of(new Quote(bp, ap));
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("latestQuote error: {}", e.toString());
            return Optional.empty();
        }
    }

    private static Double pickDouble(Map<?,?> m, String... keys) {
        for (String k: keys) {
            Object v = m.get(k);
            if (v instanceof Number n) return n.doubleValue();
            try { if (v != null) return Double.parseDouble(v.toString()); } catch (Exception ignore) {}
        }
        return null;
    }

    /* ===================== Public entry ====================== */
    public void processSignal(String ticker, double trigger, double stop) {
        String key = ticker + "|" + trigger + "|" + stop;
        if (!inFlight.add(key)) {
            log.info("🔁 Duplicate signal ignored: {}", key);
            return;
        }
        new Thread(() -> {
            try {
                runSignal(ticker, trigger, stop);
            } catch (Exception e) {
                log.error("Signal failed", e);
            } finally {
                inFlight.remove(key);
            }
        }, "sig-"+key).start();
    }

    /* ================== Strategy implementation ============== */
    private void runSignal(String ticker, double trigger, double stop) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(timeoutSec).toMillis();

        while (System.currentTimeMillis() < deadline) {
            Optional<Quote> q = latestQuote(ticker);
            if (q.isEmpty()) { Thread.sleep(pollMs); continue; }
            double bid = q.get().bid();
            double ask = q.get().ask();
            double mid = (bid + ask) / 2.0;

            double spreadBps = (ask - bid) / mid * 10_000.0;
            if (spreadBps > maxSpreadBps) {
                log.info("⏳ Wide spread {}bps (> {}bps). Waiting…", Math.round(spreadBps), maxSpreadBps);
                Thread.sleep(pollMs);
                continue;
            }

            boolean priceReached = ask >= trigger;
            if (!priceReached) { Thread.sleep(pollMs); continue; }

            double gapBps = (ask - trigger) / trigger * 10_000.0;
            if (gapBps > maxGapBps) {
                log.info("⏳ Gap {}bps above trigger. Waiting…", Math.round(gapBps));
                Thread.sleep(pollMs);
                continue;
            }

            int qty = Math.max(1, ceilDiv(200.0, ask));

            boolean marketOpen = isMarketOpen();
            if (marketOpen) {
                double entryLimit = round(ask * 1.002, 4);
                double takeProfit = round(entryLimit * 1.05, 4);
                double stopPrice  = round(stop, 4);
                double stopLimit  = round(stop * 0.995, 4);

                Map<String,Object> order = new HashMap<>();
                order.put("symbol", ticker);
                order.put("side", "buy");
                order.put("type", "limit");
                order.put("time_in_force", "gtc");
                order.put("qty", String.valueOf(qty));
                order.put("limit_price", String.valueOf(entryLimit));
                order.put("order_class", "bracket");
                order.put("take_profit", Map.of("limit_price", String.valueOf(takeProfit)));
                order.put("stop_loss", Map.of(
                        "stop_price",  String.valueOf(stopPrice),
                        "limit_price", String.valueOf(stopLimit)
                ));
                Map<String,Object> res = createOrder(order);
                log.info("🟢 Bracket submitted: {}", res);
                return;
            } else {
                if (!allowExtended) {
                    log.warn("Extended-hours disabled; waiting for regular session…");
                    Thread.sleep(pollMs);
                    continue;
                }

                double entryLimit = round(ask * 1.002, 4);
                String clientOrderId = UUID.randomUUID().toString();

                Map<String,Object> entry = new HashMap<>();
                entry.put("symbol", ticker);
                entry.put("side", "buy");
                entry.put("type", "limit");
                entry.put("time_in_force", "day");
                entry.put("qty", String.valueOf(qty));
                entry.put("limit_price", String.valueOf(entryLimit));
                entry.put("extended_hours", true);
                entry.put("client_order_id", clientOrderId);

                Map<String,Object> placed = createOrder(entry);
                String id = String.valueOf(placed.get("id"));
                log.info("🟡 Extended entry placed (id={}): {}", id, placed);

                double fillPrice = waitForFillAvgPrice(id, Duration.ofMinutes(15));
                if (Double.isNaN(fillPrice)) {
                    log.warn("Entry not filled in extended-hours window; stopping.");
                    return;
                }
                int filledQty = parseIntSafe(placed.get("qty"), qty);
                double tp = round(fillPrice * 1.05, 4);
                double slStop = round(stop, 4);
                double slLimit = round(stop * 0.995, 4);

                Map<String,Object> oco = new HashMap<>();
                oco.put("symbol", ticker);
                oco.put("side", "sell");
                oco.put("type", "limit");
                oco.put("time_in_force", "gtc");
                oco.put("qty", String.valueOf(filledQty));
                oco.put("order_class", "oco");
                oco.put("take_profit", Map.of("limit_price", String.valueOf(tp)));
                oco.put("stop_loss", Map.of(
                        "stop_price",  String.valueOf(slStop),
                        "limit_price", String.valueOf(slLimit)
                ));

                Map<String,Object> ocoRes = createOrder(oco);
                log.info("🟢 OCO submitted after fill: {}", ocoRes);
                return;
            }
        }
        log.warn("⏹ Timeout: trigger not reached within {} sec", timeoutSec);
    }

    private boolean isMarketOpen() {
        try {
            Map<String,Object> c = getClock();
            Object o = c.get("is_open");
            if (o instanceof Boolean b) return b;
            if (o != null) return Boolean.parseBoolean(o.toString());
        } catch (Exception e) {
            log.warn("clock error: {}", e.toString());
        }
        return false;
    }

    private double waitForFillAvgPrice(String orderId, Duration maxWait) throws InterruptedException {
        long end = System.currentTimeMillis() + maxWait.toMillis();
        while (System.currentTimeMillis() < end) {
            try {
                Map<String,Object> ord = getOrderById(orderId);
                String status = String.valueOf(ord.get("status"));
                if ("filled".equalsIgnoreCase(status)) {
                    Double avg = toD(ord.get("filled_avg_price"));
                    return avg != null ? avg : Double.NaN;
                }
            } catch (Exception ignore) {}
            Thread.sleep(pollMs);
        }
        return Double.NaN;
    }

    /* ====================== utils ============================ */
    private static int ceilDiv(double dollars, double price) {
        return new BigDecimal(dollars / price).setScale(0, RoundingMode.CEILING).intValue();
    }
    private static double round(double v, int scale) {
        return new BigDecimal(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
    private static int parseIntSafe(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) { return def; }
    }
    private static Double toD(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    // Removes any comment or space after the number and converts it
    private static int parseInt(String raw, int def) {
        if (raw == null) return def;
        String s = raw.trim();
        int cut = firstOf(s, '#', ' ', '\t', ';');
        if (cut >= 0) s = s.substring(0, cut);
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
    private static long parseLong(String raw, long def) {
        if (raw == null) return def;
        String s = raw.trim();
        int cut = firstOf(s, '#', ' ', '\t', ';');
        if (cut >= 0) s = s.substring(0, cut);
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
    private static boolean parseBool(String raw, boolean def) {
        if (raw == null) return def;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int cut = firstOf(s, '#', ' ', '\t', ';');
        if (cut >= 0) s = s.substring(0, cut);
        return switch (s) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> def;
        };
    }
    private static int firstOf(String s, char... cs) {
        int min = -1;
        for (char c: cs) {
            int i = s.indexOf(c);
            if (i >= 0 && (min < 0 || i < min)) min = i;
        }
        return min;
    }
}
