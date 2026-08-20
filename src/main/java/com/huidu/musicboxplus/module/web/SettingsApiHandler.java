package com.huidu.musicboxplus.module.web;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// GET /api/settings: editor-wide limits and feature toggles the client needs to render correctly.
final class SettingsApiHandler implements HttpHandler {
    private final WebApiSupport support;

    SettingsApiHandler(WebApiSupport support) {
        this.support = support;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            support.handleOptionsRequest(exchange);
            return;
        }

        if (support.checkRateLimit(exchange)) {
            return;
        }

        Map<String, Object> settings = new HashMap<>();
        settings.put("locale", "zh");
        settings.put("maxTicks", support.config().getMaxTicks());
        MusicBox plugin = support.plugin();
        MusicBoxConfig musicBoxConfig = plugin.getConfigObject();
        MusicBoxConfig.EditorConfig editorConfig = musicBoxConfig != null ? musicBoxConfig.getEditor() : null;
        MusicBoxConfig.ResourcePackInstrumentConfig resourcePackConfig = musicBoxConfig != null ? musicBoxConfig.getResourcePackInstruments() : null;
        settings.put("enable10octave", musicBoxConfig != null && musicBoxConfig.isEnable10octave());
        settings.put("enableTrumpetInstruments", MusicNote.NoteInstrument.isTrumpetEnabled());
        settings.put("nativeTrumpetInstruments", MusicNote.NoteInstrument.isTrumpetRuntimeAvailable());
        settings.put("trumpetResourcePackFallback", MusicNote.NoteInstrument.shouldUseCustomTrumpetFallback());
        settings.put("resourcePackInstrumentOverridesEnabled", resourcePackConfig != null && resourcePackConfig.isEnabled());
        settings.put("defaultMaxPitch", editorConfig != null ? editorConfig.getDefaultMaxPitch() : WebApiSupport.DEFAULT_WEB_MAX_PITCH);
        settings.put("extendedMaxPitch", editorConfig != null ? editorConfig.getExtendedMaxPitch() : WebApiSupport.DEFAULT_WEB_MAX_PITCH);
        settings.put("minBpm", support.getMinBpm());
        settings.put("maxBpm", support.getMaxBpm());
        settings.put("defaultBeatSubdivision", editorConfig != null ? editorConfig.getDefaultBeatSubdivision() : 4);
        settings.put("timeSignatures", support.getSupportedTimeSignatures());

        support.writeJsonResponse(exchange, HttpStatus.OK, settings);
    }
}
