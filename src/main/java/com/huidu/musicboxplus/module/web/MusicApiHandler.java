package com.huidu.musicboxplus.module.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.io.MusicFileImporter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletionException;

// GET/PUT /api/music: read and edit the session's player music.
final class MusicApiHandler implements HttpHandler {
    private final WebApiSupport support;

    MusicApiHandler(WebApiSupport support) {
        this.support = support;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Rate limit first. Answering OPTIONS above it made preflight a free, unmetered route:
        // the same work per request, none of it counted.
        if (support.checkRateLimit(exchange)) {
            return;
        }

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            support.handleOptionsRequest(exchange);
            return;
        }

        java.util.Optional<String> sessionIdOpt = support.extractSessionId(exchange);
        if (sessionIdOpt.isEmpty()) {
            support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Missing or invalid session");
            return;
        }
        String sessionId = sessionIdOpt.get();

        WebEditorSession session = support.sessionManager().connect(sessionId);
        if (session == null) {
            support.writeTextResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid session");
            return;
        }

        try {
            String csrfToken = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
            boolean validCsrf = csrfToken != null && session.validateCsrfToken(csrfToken);

            switch (exchange.getRequestMethod()) {
                case "GET":
                    if (!validCsrf) {
                        support.writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Invalid CSRF token");
                        return;
                    }
                    fetchMusic(exchange, session);
                    break;
                case "PUT":
                    if (!validCsrf) {
                        support.writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Invalid CSRF token");
                        return;
                    }
                    updateMusic(exchange, session);
                    break;
                default:
                    support.writeTextResponse(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
            }
        } finally {
            support.sessionManager().disconnect(sessionId);
        }
    }

    private void fetchMusic(HttpExchange exchange, WebEditorSession session) throws IOException {
        PlayerMusic music = PlayerMusicManager.getInstance()
            .getMusicById(UUID.fromString(session.getMusicId()));

        if (music == null) {
            support.writeTextResponse(exchange, HttpStatus.NOT_FOUND, "Music not found");
            return;
        }
        if (support.canAccessMusic(session, music, exchange)) {
            return;
        }

        PlayerMusic snapshot = music.snapshot();
        support.writeJsonResponse(exchange, HttpStatus.OK, support.buildMusicPayload(snapshot));
    }

    private void updateMusic(HttpExchange exchange, WebEditorSession session) throws IOException {
        String body = support.readRequestBody(exchange, support.getMaxRequestSize());
        PlayerMusic music = PlayerMusicManager.getInstance()
            .getMusicById(UUID.fromString(session.getMusicId()));

        if (music == null) {
            support.writeTextResponse(exchange, HttpStatus.NOT_FOUND, "Music not found");
            return;
        }
        if (support.canAccessMusic(session, music, exchange)) {
            return;
        }

        // The in-game editor mutates the same PlayerMusic instance on the entity thread; a web
        // write racing it would corrupt the object and its autosave, so refuse while an editor
        // session for this player is open.
        try {
            UUID ownerId = UUID.fromString(session.getPlayerId());
            if (MusicEditListener.isInEditMode(ownerId)) {
                support.writeTextResponse(exchange, HttpStatus.CONFLICT, "This song is being edited in game");
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Session player ids are always UUIDs in practice; fall through if not.
        }

        UUID musicId = music.getUniqueId();
        try {
            JsonObject json = support.json().fromJson(body, JsonObject.class);
            if (json == null) {
                support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid JSON");
                return;
            }
            // One lock order for every write path: PlayerMusicManager takes its own monitor and then
            // the song's. This used to be `synchronized (music)` around saveMusicSync -- the song
            // monitor outside the manager's -- which is the reverse of what rename/delete do, i.e. an
            // ABBA deadlock as soon as a browser PUT and a rename overlap.
            boolean saved = PlayerMusicManager.getInstance().updateMusicSync(musicId, working -> {
                applyJsonUpdate(json, working);
                return working;
            });
            if (!saved) {
                support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Save failed");
                return;
            }
            support.writeTextResponse(exchange, HttpStatus.OK, "Saved");
            // The in-game editor notification is a side channel: the save is already committed, so a
            // failure to schedule it must not turn a successful request into an HTTP 500 (it did).
            try {
                Scheduler.global(() -> MusicEditListener.notifyMusicUpdated(musicId));
            } catch (RuntimeException failure) {
                support.logServerError("Notifying in-game editors failed for music " + musicId, failure);
            }
        } catch (RejectedRequest e) {
            support.writeTextResponse(exchange, e.status, e.getMessage());
        } catch (JsonParseException e) {
            support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        } catch (CompletionException e) {
            // Logged in full, answered generically: see WebApiSupport.logServerError.
            support.logServerError("Saving music failed", e);
            support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Save failed");
        } catch (Exception e) {
            support.logServerError("Saving music failed", e);
            support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Save failed");
        }
    }

    // A field the request got wrong. Thrown from inside the locked mutation and answered here, so a
    // request that fails validation never reaches the live song.
    private static final class RejectedRequest extends RuntimeException {
        private final int status;

        private RejectedRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Applies the request's fields to the working copy the manager handed over.
     *
     * Notes are only replaced when the request carries them, which is what keeps a title/BPM-only
     * save from re-sending (and re-inserting) the whole note table.
     */
    private void applyJsonUpdate(JsonObject json, PlayerMusic working) {
        // A field of the wrong JSON type used to be skipped in silence and the request still answered
        // "Saved", so the editor believed a change had landed while the song kept its old value.
        boolean replacingNotes = requireType(json, "notes", JsonElement::isJsonArray, "notes must be an array");
        int maxPitch = support.getMaxPitchForMusic(working);
        int maxTick = support.getMaxTickForMusic(working);

        if (requireType(json, "title", JsonElement::isJsonPrimitive, "title must be a string")) {
            String title = json.get("title").getAsString();
            String normalizedTitle = normalizeTitle(title);
            if (normalizedTitle != null && !normalizedTitle.trim().isEmpty()) {
                working.setName(normalizedTitle);
            }
        }
        if (requireType(json, "tempo", JsonElement::isJsonPrimitive, "Tempo must be a number")) {
            int tempo = support.getJsonInt(json, "tempo", "Tempo must be a number");
            if (tempo < support.getMinBpm() || tempo > support.getMaxBpm()) {
                throw new RejectedRequest(HttpStatus.BAD_REQUEST, "Tempo out of range");
            }
            working.setBpm(tempo);
        }
        if (requireType(json, "beatSubdivision", JsonElement::isJsonPrimitive, "Beat subdivision must be a number")) {
            int beatSubdivision = support.getJsonInt(json, "beatSubdivision", "Beat subdivision must be a number");
            if (beatSubdivision < 1 || beatSubdivision > 16) {
                throw new RejectedRequest(HttpStatus.BAD_REQUEST, "Beat subdivision out of range");
            }
            working.setBeatSubdivision(beatSubdivision);
        }
        if (requireType(json, "timeSignature", JsonElement::isJsonPrimitive, "time signature must be a string")) {
            String timeSignature = json.get("timeSignature").getAsString();
            if (!support.getSupportedTimeSignatures().contains(timeSignature)) {
                throw new RejectedRequest(HttpStatus.BAD_REQUEST, "Unsupported time signature");
            }
            working.setTimeSignature(PlayerMusic.TimeSignature.fromString(timeSignature));
        }
        if (requireType(json, "description", JsonElement::isJsonPrimitive, "description must be a string")) {
            working.setDescription(normalizeDescription(json.get("description").getAsString()));
        }
        if (replacingNotes) {
            var notesArray = json.getAsJsonArray("notes");
            if (notesArray.size() > MusicFileImporter.MAX_IMPORT_NOTES) {
                throw new RejectedRequest(HttpStatus.BAD_REQUEST, "Too many notes (max "
                        + MusicFileImporter.MAX_IMPORT_NOTES + ")");
            }
            working.clearNotes();
            for (JsonElement elem : notesArray) {
                MusicNote note = support.parseMusicNoteForWeb(elem, maxPitch, maxTick);
                if (!working.addNote(note)) {
                    throw new JsonParseException("Duplicate note at pitch " + note.getPitch() + ", tick " + note.getTick());
                }
            }
        }
    }

    /**
     * True when the field is present and of the expected type; throws a 400 when it is present with
     * the wrong type, and returns false (nothing to do) when it is absent.
     */
    private static boolean requireType(JsonObject json, String field,
                                       java.util.function.Predicate<JsonElement> expected,
                                       String message) {
        if (!json.has(field)) {
            return false;
        }
        JsonElement value = json.get(field);
        if (!expected.test(value)) {
            throw new RejectedRequest(HttpStatus.BAD_REQUEST, message);
        }
        return true;
    }

    private String normalizeTitle(String title) {
        // Route through the SAME sanitizer as every in-game rename/create path so the web editor
        // can't persist MiniMessage/legacy-color/control chars (< > & " ' U+00A7) into item names
        // that other players see via the publish/purchase flow; stripping control chars alone
        // lets stored injection through. validateMusicName trims, caps at 100, and returns null
        // for blank input (the caller already null/blank-checks the result).
        return PlayerMusicManager.getInstance().validateMusicName(title);
    }

    // Single implementation, shared with the file-import path.
    private String normalizeDescription(String description) {
        return com.huidu.musicboxplus.module.edit.io.NBSImporter.sanitizeDescription(description);
    }
}
