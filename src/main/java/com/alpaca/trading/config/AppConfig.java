package com.alpaca.trading.config;
import io.github.cdimascio.dotenv.Dotenv;

public class AppConfig {
    private final Dotenv env = Dotenv.configure().ignoreIfMissing().load();

    public String alpacaKey()     { return env.get("ALPACA_KEY"); }
    public String alpacaSecret()  { return env.get("ALPACA_SECRET"); }
    public String alpacaBaseUrl() { return env.get("ALPACA_BASE_URL", "https://paper-api.alpaca.markets"); }
    public String dataWsUrl()     { return env.get("ALPACA_DATA_WS"); }

    public boolean extendedHours(){ return Boolean.parseBoolean(env.get("ALLOW_EXTENDED_HOURS","true")); }
    public double spreadMaxPct()  { return Double.parseDouble(env.get("SPREAD_MAX_PCT","0.5")); }
    public double slippageMaxPct(){ return Double.parseDouble(env.get("SLIPPAGE_MAX_PCT","0.5")); }
    public double positionUsd()   { return Double.parseDouble(env.get("POSITION_NOTIONAL_USD","200")); }
    public double takeProfitPct() { return Double.parseDouble(env.get("TAKE_PROFIT_PCT","5")); }

    public String tdAppId()       { return env.get("TD_APP_ID"); }
    public String tdAppHash()     { return env.get("TD_APP_HASH"); }
    public String tdPhone()       { return env.get("TD_PHONE"); }
    public String tdSessionDir()  { return env.get("TD_SESSION_DIR","./tdlight-session"); }
}
