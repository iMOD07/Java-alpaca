package com.alpaca.trading.telegram;

import it.tdlight.client.*;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class TDLightClient {

    private static final Logger log = LoggerFactory.getLogger(TDLightClient.class);
    private final com.alpaca.trading.service.SignalParser parser;
    private final com.alpaca.trading.service.TradeExecutor executor;
    private final com.alpaca.trading.store.SignalStore store;

    public TDLightClient(com.alpaca.trading.service.SignalParser parser,
                         com.alpaca.trading.service.TradeExecutor executor,
                         com.alpaca.trading.store.SignalStore store) {
        this.parser = parser;
        this.executor = executor;
        this.store = store;
    }

    private SimpleTelegramClient client;

    public void start(int apiId, String apiHash, String sessionDir) throws Exception {
        Path sessionPath = Path.of(sessionDir);
        SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory();
        client = factory.build(apiId, apiHash, sessionPath);

        client.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> {
            TdApi.Message msg = update.message;
            if (msg.content instanceof TdApi.MessageText textMsg) {
                String text = textMsg.text.text;
                log.info("📩 Telegram: {}", text);

                parser.parse(text).ifPresent(signal -> {
                    String hash = com.alpaca.trading.service.SignalParser.hashMessage(text);
                    boolean isNew = store.saveIfNew(signal, hash);
                    if (!isNew) {
                        log.info("⛔️ duplicate signal ignored");
                        return;
                    }
                    // Only now we announce that we have armed the signal (ARMED).
                    store.updateStatus(signal.id, com.alpaca.trading.model.SignalStatus.ARMED);

                    //Temporarily: Execute directly for testing (later we replace it with price monitoring)
                    executor.executeBracketAtTrigger(signal.symbol, signal.trigger, signal.stopLoss);
                });
            }
        });

        client.login().join();
        log.info("✅ TDLight client started and logged in.");
    }

    public void stop() { if (client != null) client.close(); }
}
