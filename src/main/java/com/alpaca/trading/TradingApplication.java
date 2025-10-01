package com.alpaca.trading;

import com.alpaca.trading.telegram.TDLightClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TradingApplication {
    public static void main(String[] args) { SpringApplication.run(TradingApplication.class, args); }

    @Bean
    CommandLineRunner tdlightRunner(
            @Value("${telegram.api-id:}") String apiIdStr,
            @Value("${telegram.api-hash:}") String apiHash,
            @Value("${telegram.session-dir:tdlight-session}") String sessionDir,
            com.alpaca.trading.service.SignalParser parser,
            com.alpaca.trading.service.TradeExecutor executor,
            com.alpaca.trading.store.SignalStore store // We will make Bean for him under
    ) {
        return args -> {
            if (apiIdStr == null || apiIdStr.isBlank() || apiHash == null || apiHash.isBlank()) {
                System.err.println("❌ TELEGRAM API keys missing. Set telegram.api-id / telegram.api-hash.");
                return;
            }
            int apiId = Integer.parseInt(apiIdStr.trim());

            // Pass the dependencies to the customer
            TDLightClient td = new TDLightClient(parser, executor, store);
            td.start(apiId, apiHash, sessionDir);
        };
    }
}