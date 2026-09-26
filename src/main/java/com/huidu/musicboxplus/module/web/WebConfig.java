package com.huidu.musicboxplus.module.web;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;

import java.util.ArrayList;
import java.util.List;

// Runtime snapshot of the web editor's config section, resolved once at startup so handlers
// never re-read config.yml per request.
public final class WebConfig {
    private final int port;
    private final String host;
    private final boolean showPort;
    private final boolean enabled;
    private final int sessionTimeout;
    private final int maxTicks;
    private final boolean bindAllInterfaces;
    private final String bindAddress;
    private final int maxRequestSize;
    private final int rateLimit;
    private final String allowedOrigin;
    private final List<String> trustedProxies;

    WebConfig(MusicBox plugin) {
        MusicBoxConfig.WebConfig webConfig = plugin.getConfigObject() != null ? plugin.getConfigObject().getWeb() : null;
        this.port = webConfig != null ? webConfig.getPort() : 8080;
        this.host = webConfig != null ? webConfig.getHost() : "localhost";
        this.showPort = webConfig == null || webConfig.isShowPortInChat();
        this.enabled = webConfig != null && webConfig.isEnabled();
        this.sessionTimeout = webConfig != null ? webConfig.getLinkExpireTime() : 10;
        this.maxTicks = webConfig != null ? webConfig.getMaxMusicLength() : 10000;
        this.bindAllInterfaces = webConfig != null && webConfig.isBindAllInterfaces();
        this.bindAddress = webConfig != null ? webConfig.getBindAddress() : "";
        this.maxRequestSize = webConfig != null ? webConfig.getMaxRequestSize() : 10;
        this.rateLimit = webConfig != null ? Math.max(1, webConfig.getRateLimit()) : 100;
        this.allowedOrigin = normalizeAllowedOrigin(webConfig != null ? webConfig.getAllowedOrigin() : "");
        this.trustedProxies = webConfig != null ? new ArrayList<>(webConfig.getTrustedProxies()) : new ArrayList<>();
    }

    private static String normalizeAllowedOrigin(String configuredOrigin) {
        if (configuredOrigin == null || configuredOrigin.isBlank()) {
            return "";
        }
        return configuredOrigin.trim();
    }

    public int getPort() { return port; }
    public String getHost() { return host; }
    public String getBindAddress() {
        if (bindAddress != null && !bindAddress.isEmpty()) {
            return bindAddress;
        }
        return bindAllInterfaces ? "0.0.0.0" : "localhost";
    }
    public boolean showPort() { return showPort; }
    public boolean isEnabled() { return enabled; }
    public int getSessionTimeout() { return sessionTimeout; }
    public int getMaxTicks() { return maxTicks; }
    public int getMaxRequestSize() { return maxRequestSize; }
    public int getRateLimit() { return rateLimit; }
    public String getAllowedOrigin() { return allowedOrigin; }
    public boolean isTrustedProxy(String ip) {
        return trustedProxies.contains(ip) || trustedProxies.contains("*");
    }
}
