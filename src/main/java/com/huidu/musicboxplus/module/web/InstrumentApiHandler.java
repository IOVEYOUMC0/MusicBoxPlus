package com.huidu.musicboxplus.module.web;

import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// GET /api/instruments: list every instrument the editor can assign to a note.
final class InstrumentApiHandler implements HttpHandler {
    private final WebApiSupport support;

    InstrumentApiHandler(WebApiSupport support) {
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

        if (!"GET".equals(exchange.getRequestMethod())) {
            support.writeTextResponse(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
            return;
        }

        List<Map<String, Object>> instruments = new ArrayList<>();
        for (MusicNote.NoteInstrument inst : MusicNote.NoteInstrument.getAvailableValues()) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", inst.name());
            data.put("label", inst.getDisplayName());
            data.put("material", inst.getMaterial().name().toLowerCase(Locale.ROOT));
            data.put("sound", WebInstrumentJsonSupport.getEffectiveSoundIdentifier(inst));
            data.put("nativeSupported", inst.isRuntimeAvailable());
            data.put("customSound", ResourcePackInstrumentUtils.resolveSoundKey(inst));
            data.put("effectiveMode", WebInstrumentJsonSupport.getEffectiveInstrumentMode(inst));
            instruments.add(data);
        }

        support.writeJsonResponse(exchange, HttpStatus.OK, instruments);
    }
}
