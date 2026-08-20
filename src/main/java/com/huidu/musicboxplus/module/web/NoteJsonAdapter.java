package com.huidu.musicboxplus.module.web;

import com.google.gson.*;
import com.huidu.musicboxplus.module.edit.MusicNote;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

final class NoteJsonAdapter implements JsonSerializer<MusicNote>, JsonDeserializer<MusicNote> {

    @Override
    public JsonElement serialize(MusicNote note, Type type, JsonSerializationContext ctx) {
        JsonObject obj = new JsonObject();
        obj.addProperty("pitch", note.getPitch());
        obj.addProperty("tick", note.getTick());

        JsonArray instruments = new JsonArray();
        for (MusicNote.NoteInstrument inst : note.getInstruments()) {
            instruments.add(inst.name());
        }
        obj.add("instruments", instruments);

        return obj;
    }

    @Override
    public MusicNote deserialize(JsonElement json, Type type, JsonDeserializationContext ctx)
            throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();
        int pitch = obj.get("pitch").getAsInt();
        int tick = obj.get("tick").getAsInt();

        List<MusicNote.NoteInstrument> instruments = new ArrayList<>();
        if (obj.has("instruments")) {
            for (JsonElement elem : obj.getAsJsonArray("instruments")) {
                String name = elem.isJsonObject()
                        ? elem.getAsJsonObject().get("id").getAsString()
                        : elem.getAsString();
                MusicNote.NoteInstrument instrument = MusicNote.NoteInstrument.parseExternal(name);
                if (instrument != null) {
                    instruments.add(instrument);
                }
            }
        }

        return new MusicNote(pitch, tick, instruments);
    }
}
