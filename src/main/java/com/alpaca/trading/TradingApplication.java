package com.alpaca.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TradingApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradingApplication.class, args);
        System.out.println("================");
	}

}
/*

export $(cat .env | xargs)
./mvnw spring-boot:run

 */