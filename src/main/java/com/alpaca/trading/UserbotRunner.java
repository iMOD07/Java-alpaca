package com.alpaca.trading;

import com.alpaca.trading.service.NluService;
import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UserbotRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(UserbotRunner.class);

    // ====== Config (application.properties) ======
    @Value("${telegram.api-id}")   private int apiId;
    @Value("${telegram.api-hash}") private String apiHash;
    @Value("${telegram.phone}")    private String phoneNumber;
    @Value("${telegram.session-dir:tdlight-session}") private String sessionDir;

    // telegram.target = @username | -100xxxxxxxx | auto
    @Value("${telegram.target:auto}") private String target;
    @Value("${executor.url}")         private String executorUrl;

    // NLU optional
    @Value("${ai.enabled:false}") private boolean aiEnabled;
    @Autowired(required = false)  private NluService nluService;

    // ====== TDLight ======
    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    // Use primitive to avoid Null Pointer / equals
    private volatile long targetChatId = 0L;

    private final RestTemplate http = new RestTemplate();
    private Path baseDir;
    private Path targetFile;

    @Override
    public void run(String... args) throws Exception {

        // 1 Initialize TDLib
        Init.init();
        Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());

        // 2 Storage paths
        baseDir = Paths.get(sessionDir);
        targetFile = baseDir.resolve("target.chatid");

        // 3 Setting TDLib
        clientFactory = new SimpleTelegramClientFactory();
        APIToken token = new APIToken(apiId, apiHash);
        TDLibSettings settings = TDLibSettings.create(token);
        settings.setDatabaseDirectoryPath(baseDir.resolve("db"));
        settings.setDownloadedFilesDirectoryPath(baseDir.resolve("downloads"));
        settings.setDeviceModel("SpringBoot-Userbot");
        settings.setSystemLanguageCode("en");
        settings.setApplicationVersion("1.0.0");


        // 4 Build client + handlers
        SimpleTelegramClientBuilder builder = clientFactory.builder(settings);

        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, update -> {
            var st = update.authorizationState;
            if (st instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                log.info("Waiting for phone number…");
            } else if (st instanceof TdApi.AuthorizationStateWaitCode) {
                log.info("Waiting for login code (SMS/Telegram)…");
            } else if (st instanceof TdApi.AuthorizationStateReady) {
                log.info("Authorized ✅");
                initTarget(); // Initialize target (file/property/discovery)
            } else if (st instanceof TdApi.AuthorizationStateClosed) {
                log.info("Client closed.");
            }
        });

        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> {
            TdApi.Message msg = update.message;
            if (msg == null || msg.isOutgoing) return;
            if (!(msg.content instanceof TdApi.MessageText t)) return;

            String text = t.text.text == null ? "" : t.text.text.trim();

            // Test command
            if ("/ping".equalsIgnoreCase(text)) {
                sendText(msg.chatId, "pong");
                return;
            }

            // Discovery mode: We capture the first channel message and save the chatId.
            if (targetChatId == 0L && isDiscoverMode()) {
                discoverAndPersistTarget(msg.chatId);
            }

            // Filter on target channel
            if (targetChatId == 0L || msg.chatId != targetChatId) return;

            // Build the payload (AI first then fallback regex)
            Map<String, Object> payload = buildPayload(text);
            if (payload == null || payload.get("parsed") == null) {
                log.warn("Skip: couldn't parse signal: {}", text.replaceAll("\\s+"," ").trim());
                return; // Do not send to /trades so that it does not give 400
            }

            try {
                http.postForEntity(executorUrl, payload, String.class);
                log.info("[FWD] sent to {}: {}", executorUrl, payload);
            } catch (Exception e) {
                log.error("[HTTP] failed to send: {}", e.getMessage(), e);
            }
        });

        // 5 Login (User)
        client = builder.build(AuthenticationSupplier.user(phoneNumber));

        // 6 Clean close
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (client != null) client.close();
                if (clientFactory != null) clientFactory.close();
            } catch (Exception e) {
                log.warn("Error on shutdown", e);
            }
        }));

        log.info("Userbot started. Waiting for channel posts… (ai.enabled={})", aiEnabled);
    }

    // ===================== Target handling =====================

    private void initTarget() {
        try {
            Files.createDirectories(baseDir);

            // Priority 1: Direct numeric value
            if (target != null && !target.isBlank() && isNumeric(target)) {
                targetChatId = Long.parseLong(target.trim());
                log.info("Target chatId set from properties: {}", targetChatId);
                return;
            }
            // Priority 2: @username public
            if (target != null && target.startsWith("@")) {
                resolvePublicUsername(target.substring(1));
                return;
            }
            // Priority 3: auto or empty Try a saved file
            if (Files.exists(targetFile)) {
                String s = Files.readString(targetFile, StandardCharsets.UTF_8).trim();
                if (isNumeric(s)) {
                    targetChatId = Long.parseLong(s);
                    log.info("Target chatId loaded from {} = {}", targetFile, targetChatId);
                    return;
                }
            }

            // Auto-detection
            log.warn("Target not set. Discovery mode is ON. Post any message in the target channel to capture its chatId.");
        } catch (Exception e) {
            log.error("initTarget error", e);
        }
    }

    private void resolvePublicUsername(String username) {
        client.send(new TdApi.SearchPublicChat(username))
                .whenComplete((chat, err) -> {
                    if (err != null) {
                        log.error("Failed to resolve @{}: {}", username, err.getMessage(), err);
                        return;
                    }
                    if (chat == null) {
                        log.error("Failed to resolve @{}: result is null", username);
                        return;
                    }
                    // chat type TdApi.Chat here
                    targetChatId = chat.id;
                    log.info("Target resolved: @{} -> chatId {}", username, targetChatId);
                    client.send(new TdApi.JoinChat(targetChatId));
                    persistTarget(targetChatId);
                });
    }


    private void discoverAndPersistTarget(long chatId) {
        client.send(new TdApi.GetChat(chatId)).whenComplete((obj, err) -> {
            if (err != null || !(obj instanceof TdApi.Chat)) {
                log.error("Discovery failed for chatId {}: {}", chatId, err != null ? err.getMessage() : "null");
                return;
            }
            TdApi.Chat c = (TdApi.Chat) obj;
            boolean isChannel = false;
            if (c.type instanceof TdApi.ChatTypeSupergroup sg) {
                isChannel = sg.isChannel;
            }
            if (!isChannel) {
                log.info("Discovered chat '{}' (id={}) but it's not a channel; ignoring.", c.title, c.id);
                return;
            }
            targetChatId = c.id;
            log.info("DISCOVERED channel: title='{}', id={}", c.title, targetChatId);
            persistTarget(targetChatId);
        });
    }

    private void persistTarget(long id) {
        try {
            Files.createDirectories(baseDir);
            Files.writeString(
                    targetFile,
                    Long.toString(id),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            log.info("Saved target chatId to {}", targetFile);
        } catch (IOException e) {
            log.warn("Failed to save target chatId: {}", e.getMessage());
        }
    }

    private boolean isDiscoverMode() {
        return target == null || target.isBlank() || "auto".equalsIgnoreCase(target.trim());
    }

    private boolean isNumeric(String s) {
        try { Long.parseLong(s.trim()); return true; } catch (Exception e) { return false; }
    }

    // ===================== Send helper =====================

    private void sendText(long chatId, String msg) {
        TdApi.InputMessageText payload = new TdApi.InputMessageText(
                new TdApi.FormattedText(msg, new TdApi.TextEntity[0]),
                /* linkPreviewOptions */ null,
                /* clearDraft */ true
        );
        TdApi.SendMessage req = new TdApi.SendMessage(
                chatId, 0, /* replyTo */ null, new TdApi.MessageSendOptions(), null, payload
        );
        client.send(req);
    }

    // ===================== Payload builder (AI + Regex) =====================

    private Map<String, Object> buildPayload(String t) {
        String text = (t == null) ? "" : t.trim();
        if (text.isEmpty()) return null;

        // 1 NLU (Structured) if enabled and available
        if (aiEnabled && nluService != null) {
            try {
                var nluOpt = nluService.extract(text);
                if (nluOpt.isPresent()) {
                    var s = nluOpt.get();
                    if (s.ticker != null && s.trigger != null && s.stop != null) {
                        String side = "buy";
                        try { if (s.side != null && s.side.isPresent()) side = s.side.get(); } catch (Exception ignored) {}
                        boolean extended = true;
                        try { if (s.extended_hours != null && s.extended_hours.isPresent()) extended = s.extended_hours.get(); } catch (Exception ignored) {}

                        var parsed = new HashMap<String, Object>();
                        parsed.put("ticker", s.ticker.toUpperCase());
                        parsed.put("trigger", s.trigger);
                        parsed.put("stop", s.stop);
                        parsed.put("side", side);
                        parsed.put("extended_hours", extended);
                        try { if (s.targets != null && !s.targets.isEmpty()) parsed.put("targets", s.targets); } catch (Exception ignored) {}

                        var body = new HashMap<String, Object>();
                        body.put("raw", text);
                        body.put("parsed", parsed);
                        return body;
                    }
                }
            } catch (Exception e) {
                log.warn("NLU failed, fallback to regex: {}", e.toString());
            }
        }

        // 2 Regex fallback
        return buildPayloadFallbackRegex(text);
    }

    // ===================== Regex Fallback =====================

    private Map<String, Object> buildPayloadFallbackRegex(String t) {
        String text = (t == null) ? "" : t.trim();
        if (text.isEmpty()) return null;

        String norm = normalizeDigits(text);

        String ticker = detectTicker(norm);
        Double trigger = findTrigger(norm);
        Double stop    = findStop(norm);

        // Heuristic: If one of them is missing and we have two numbers → the larger is trigger and the smaller is stop
        if ((trigger == null || stop == null)) {
            double[] nums = allNumbers(norm);
            if (nums.length >= 2) {
                double hi = Math.max(nums[0], nums[1]);
                double lo = Math.min(nums[0], nums[1]);
                if (trigger == null) trigger = hi;
                if (stop == null)    stop    = lo;
            }
        }

        if (ticker == null || trigger == null || stop == null) return null;

        List<Double> targets = extractTargets(norm);

        String raw = String.format("%s | trigger %.4f | stop %.4f | side buy | extended_hours=1",
                ticker, trigger, stop);

        Map<String, Object> parsed = new HashMap<>();
        parsed.put("ticker", ticker);
        parsed.put("trigger", trigger);
        parsed.put("stop", stop);
        parsed.put("side", "buy");
        parsed.put("extended_hours", true);
        if (!targets.isEmpty()) parsed.put("targets", targets);

        Map<String, Object> body = new HashMap<>();
        body.put("raw", raw);
        body.put("parsed", parsed);
        return body;
    }

    // ===================== Parser helpers =====================

    private String detectTicker(String text) {
        String[] lines = text.split("\\R+");
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.matches("^[A-Za-z][A-Za-z0-9._-]{0,9}$")) return s.toUpperCase();
            Matcher m = Pattern.compile("\\b([A-Za-z][A-Za-z0-9._-]{0,9})\\b").matcher(s);
            if (m.find()) {
                String cand = m.group(1).toUpperCase();
                if (isTickerLike(cand)) return cand;
            }
        }
        Matcher m = Pattern.compile("\\b([A-Za-z][A-Za-z0-9._-]{0,9})\\b").matcher(text);
        while (m.find()) {
            String cand = m.group(1).toUpperCase();
            if (isTickerLike(cand)) return cand;
        }
        return null;
    }

    private boolean isTickerLike(String cand) {
        Set<String> bad = Set.of("BUY","ENTRY","TRIGGER","STOP","SL","TP","TARGET","T1","T2","T3");
        return !bad.contains(cand) && cand.length() >= 1 && cand.length() <= 10;
    }

    private Double findTrigger(String text) {
        String NUM = "([\\d۰-۹٠-٩.,،]+)";
        String TRIG_WORDS = "(?i)(?:بتجاوز|عند\\s*تجاوز|تجاوز|دخول|شراء(?:\\s*فوق|\\s*عند)?|buy|entry|trigger|above|break(?:out)?|cross(?:es|ing)?)";
        Double v = findNumberInText(text, TRIG_WORDS + "\\s*[:=]??\\s*" + NUM);
        if (v != null) return v;
        v = findNumberInText(text, "(?:>=|>|فوق)\\s*" + NUM);
        if (v != null) return v;
        v = findNumberInText(text, "(?i)(?:شراء|buy|entry)\\s*" + NUM);
        return v;
    }

    private Double findStop(String text) {
        String NUM = "([\\d۰-۹٠-٩.,،]+)";
        String STOP_WORDS = "(?i)(?:وقف(?:\\s*خسارة)?|إ?يقاف|ستوب|stop(?:\\s*loss)?|\\bsl\\b)";
        Double v = findNumberInText(text, STOP_WORDS + "\\s*[:=]??\\s*" + NUM);
        if (v != null) return v;
        v = findNumberInText(text, "(?i)\\bsl\\b\\s*[:=]?\\s*" + NUM);
        return v;
    }

    private Double findNumberInText(String text, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        return parseNumberFlexible(m.group(1));
    }

    private double[] allNumbers(String text) {
        List<Double> list = new ArrayList<>();
        Matcher m = Pattern.compile("([\\d۰-۹٠-٩.,،]+)").matcher(text);
        while (m.find()) {
            Double d = parseNumberFlexible(m.group(1));
            if (d != null) list.add(d);
        }
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private String normalizeDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\u0660' && ch <= '\u0669') { sb.append((char)('0' + (ch - '\u0660'))); }
            else if (ch >= '\u06F0' && ch <= '\u06F9') { sb.append((char)('0' + (ch - '\u06F0'))); }
            else { sb.append(ch); }
        }
        return sb.toString();
    }

    private Double parseNumberFlexible(String s) {
        if (s == null) return null;
        String norm = s.replace('،','.')
                .replace(',', '.')
                .replaceAll("[^0-9.]", "");
        if (norm.isEmpty()) return null;
        int firstDot = norm.indexOf('.');
        if (firstDot >= 0) {
            int nextDot = norm.indexOf('.', firstDot + 1);
            if (nextDot >= 0) norm = norm.substring(0, nextDot).replaceAll("\\.+$", "");
        }
        try { return Double.parseDouble(norm); } catch (Exception e) { return null; }
    }

    private List<Double> extractTargets(String text) {
        List<Double> list = new ArrayList<>();
        Set<Double> seen = new HashSet<>();

        String[] lines = text.split("\\R+");
        boolean inBlock = false;
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) continue;

            if (!inBlock) {
                if (l.matches("(?i)^(أهداف|اهداف|الاهداف|targets|tps?)\\b.*")) {
                    inBlock = true;
                    Matcher m = Pattern.compile("([\\d۰-۹٠-٩.,،]+)").matcher(l);
                    while (m.find()) addUnique(list, seen, parseNumberFlexible(m.group(1)));
                    continue;
                }
            } else {
                Double v = parseNumberFlexible(l);
                if (v != null) { addUnique(list, seen, v); continue; }
                break; // The first line is not a block-terminating number.
            }

            Matcher inline = Pattern.compile("(?i)\\b(?:tp\\d*|t\\d+|هدف)\\s*[:=]??\\s*([\\d۰-۹٠-٩.,،]+)").matcher(l);
            while (inline.find()) addUnique(list, seen, parseNumberFlexible(inline.group(1)));
        }
        if (list.size() > 5) return list.subList(0, 5);
        return list;
    }

    private void addUnique(List<Double> list, Set<Double> seen, Double v) {
        if (v == null) return;
        double key = Math.round(v * 10000d) / 10000d;
        if (seen.add(key)) list.add(v);
    }
}
