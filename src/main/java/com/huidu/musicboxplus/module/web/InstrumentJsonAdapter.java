package com.huidu.musicboxplus.module.web;

import com.google.gson.*;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;

import java.lang.reflect.Type;

final class InstrumentJsonAdapter
        implements JsonSerializer<MusicNote.NoteInstrument>, JsonDeserializer<MusicNote.NoteInstrument> {

    @Override
    public JsonElement serialize(MusicNote.NoteInstrument src, Type type, JsonSerializationContext ctx) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", src.name());
        obj.addProperty("label", src.getDisplayName());
        obj.addProperty("sound", WebInstrumentJsonSupport.getEffectiveSoundIdentifier(src));
        obj.addProperty("nativeSupported", src.isRuntimeAvailable());
        String customSound = ResourcePackInstrumentUtils.resolveSoundKey(src);
        if (customSound != null) {
            obj.addProperty("customSound", customSound);
        }
        obj.addProperty("effectiveMode", WebInstrumentJsonSupport.getEffectiveInstrumentMode(src));
        return obj;
    }

    @Override
    public MusicNote.NoteInstrument deserialize(JsonElement json, Type type, JsonDeserializationContext ctx)
            throws JsonParseException {
        String name = json.isJsonObject()
                ? json.getAsJsonObject().get("id").getAsString()
                : json.getAsString();
        return MusicNote.NoteInstrument.parseExternal(name);
    }
}
