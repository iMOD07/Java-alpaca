package com.alpaca.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.alpaca.trading.service.SignalParser;
import com.alpaca.trading.service.TradeEngine;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TradingApplication {
    public static void main(String[] args) { SpringApplication.run(TradingApplication.class, args); }

    @Bean
    CommandLineRunner demo(SignalParser parser, TradeEngine engine){
        return args -> {
            String msg = """
        FGNX
        عند تجاوز 9.16
        وقف 8.25
        اهداف
        10.00
        11.16
        12.57
        """;
            parser.parse(msg).ifPresentOrElse(sig -> {
                System.out.println("Parsed: " + sig.symbol + " trigger=" + sig.trigger + " sl=" + sig.stopLoss);
                // Temporarily: We assume the price has reached and test the dummy execution.
                engine.simulateTriggerAndExecute(sig);
                System.out.println("Submitted + OCO (stub).");
            }, () -> System.out.println("Parser failed"));
        };
    }
}

/*
./mvnw spring-boot:run
 */