package com.alpaca.trading.service;

import com.alpaca.trading.SignalExtraction;
import com.openai.client.OpenAIClient;
import com.openai.errors.RateLimitException;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;

@Service
public class NluService {
    private static final Logger log = LoggerFactory.getLogger(NluService.class);

    private final OpenAIClient client;

    @Value("${ai.model:gpt-4o-mini}") private String modelName;
    @Value("${ai.max-retries:2}")     private int maxRetries;
    @Value("${ai.backoff-ms:600}")    private long backoffMs;
    @Value("${ai.rpm:30}")            private int rpm;

    // Simple: We set the rate limit locally (we don't stop the bot—we fall back to the fullback)
    private final Deque<Long> calls = new ArrayDeque<>();

    public NluService(OpenAIClient client) {
        this.client = client;
    }

    public Optional<SignalExtraction> extract(String message) {
        if (!allow()) {
            log.debug("AI rate-limited locally; falling back to regex.");
            return Optional.empty();
        }
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                StructuredChatCompletionCreateParams<SignalExtraction> params =
                        ChatCompletionCreateParams.builder()
                                .model(resolveModel(modelName))
                                .addSystemMessage("""
                    You are a trading signal parser. Extract structured fields from Arabic/English text:
                    - ticker: US stock symbol, uppercase (AAPL, TSLA, ...).
                    - trigger: entry/activation price (double).
                    - stop: stop-loss price (double).
                    - side: "buy" by default unless clearly says sell/short.
                    - extended_hours: true if pre/after-market implied.
                    - targets: up to 5 take-profit levels as doubles.
                    Constraints:
                    - Accept Arabic-Indic digits and '،' as decimal separator.
                    - Return ONLY the structured object; no prose; do not invent numbers.
                    - If any core field is truly missing, leave it null.
                    """)
                                .addUserMessage(message)
                                .responseFormat(SignalExtraction.class)
                                .build();

                StructuredChatCompletion<SignalExtraction> resp =
                        client.chat().completions().create(params);

                return resp.choices().stream()
                        .flatMap(c -> c.message().content().stream())
                        .findFirst();

            } catch (RateLimitException rle) {
                log.warn("NLU rate-limited (attempt {}/{}): {}", attempt, maxRetries, rle.getMessage());
                if (attempt > maxRetries) return Optional.empty();
                sleep(backoffMs * attempt);
            } catch (Exception e) {
                log.warn("NLU extract failed (attempt {}/{}): {}", attempt, maxRetries, e.toString());
                if (attempt > maxRetries) return Optional.empty();
                sleep(backoffMs * attempt);
            }
        }
    }

    private ChatModel resolveModel(String name) {
        // Quick support for some common names
        String m = (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
        return switch (m) {
            case "gpt-4.1", "gpt_4_1" -> ChatModel.GPT_4_1;
            case "gpt-4o", "gpt_4o" -> ChatModel.GPT_4O;
            case "gpt-4o-mini", "gpt_4o_mini", "4o-mini" -> ChatModel.GPT_4O_MINI;
            default -> ChatModel.GPT_4O_MINI;
        };
    }

    private boolean allow() {
        if (rpm <= 0) return true;
        long now = Instant.now().toEpochMilli();
        long window = 60_000L;
        synchronized (calls) {
            while (!calls.isEmpty() && now - calls.peekFirst() > window) {
                calls.pollFirst();
            }
            if (calls.size() >= rpm) return false;
            calls.addLast(now);
            return true;
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}