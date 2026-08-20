package com.huidu.musicboxplus.module.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            support.handleOptionsRequest(exchange);
            return;
        }

        if (support.checkRateLimit(exchange)) {
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

        try {
            // synchronized: two browser tabs can PUT the same song concurrently, and the
            // apply+save mutates the shared object — serialize them so one tab cannot clobber
            // the other's partial write mid-save.
            synchronized (music) {
                JsonObject json = support.json().fromJson(body, JsonObject.class);
                if (json == null) {
                    support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid JSON");
                    return;
                }
                PlayerMusic beforeSnapshot = music.snapshot();
                // The editor's autosave always sends the full note array, and the notes branch below
                // clears them again before repopulating -- so copying them into this snapshot is work
                // thrown away on every save.
                boolean replacingNotes = json.has("notes") && json.get("notes").isJsonArray();
                PlayerMusic updatedSnapshot = replacingNotes
                        ? beforeSnapshot.snapshotWithoutNotes()
                        : beforeSnapshot.snapshot();
                int maxPitch = support.getMaxPitchForMusic(beforeSnapshot);
                int maxTick = support.getMaxTickForMusic(beforeSnapshot);

                if (json.has("title") && json.get("title").isJsonPrimitive()) {
                    String title = json.get("title").getAsString();
                    String normalizedTitle = normalizeTitle(title);
                    if (normalizedTitle != null && !normalizedTitle.trim().isEmpty()) {
                        updatedSnapshot.setName(normalizedTitle);
                    }
                }
                if (json.has("tempo") && json.get("tempo").isJsonPrimitive()) {
                    int tempo = support.getJsonInt(json, "tempo", "Tempo must be a number");
                    if (tempo < support.getMinBpm() || tempo > support.getMaxBpm()) {
                        support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Tempo out of range");
                        return;
                    }
                    updatedSnapshot.setBpm(tempo);
                }
                if (json.has("beatSubdivision") && json.get("beatSubdivision").isJsonPrimitive()) {
                    int beatSubdivision = support.getJsonInt(json, "beatSubdivision", "Beat subdivision must be a number");
                    if (beatSubdivision < 1 || beatSubdivision > 16) {
                        support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Beat subdivision out of range");
                        return;
                    }
                    updatedSnapshot.setBeatSubdivision(beatSubdivision);
                }
                if (json.has("timeSignature") && json.get("timeSignature").isJsonPrimitive()) {
                    String timeSignature = json.get("timeSignature").getAsString();
                    if (!support.getSupportedTimeSignatures().contains(timeSignature)) {
                        support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Unsupported time signature");
                        return;
                    }
                    updatedSnapshot.setTimeSignature(PlayerMusic.TimeSignature.fromString(timeSignature));
                }
                if (json.has("description") && json.get("description").isJsonPrimitive()) {
                    updatedSnapshot.setDescription(normalizeDescription(json.get("description").getAsString()));
                }
                if (json.has("notes") && json.get("notes").isJsonArray()) {
                    var notesArray = json.getAsJsonArray("notes");
                    if (notesArray.size() > MusicFileImporter.MAX_IMPORT_NOTES) {
                        support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Too many notes (max "
                                + MusicFileImporter.MAX_IMPORT_NOTES + ")");
                        return;
                    }
                    for (JsonElement elem : notesArray) {
                        MusicNote note = support.parseMusicNoteForWeb(elem, maxPitch, maxTick);
                        if (!updatedSnapshot.addNote(note)) {
                            throw new JsonParseException("Duplicate note at pitch " + note.getPitch() + ", tick " + note.getTick());
                        }
                    }
                }

                music.applySnapshot(updatedSnapshot);
                boolean saved = PlayerMusicManager.getInstance().saveMusicSync(music);
                if (!saved) {
                    music.applySnapshot(beforeSnapshot);
                    support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Save failed");
                    return;
                }
            }
            MusicEditListener.notifyMusicUpdated(music.getUniqueId());
            support.writeTextResponse(exchange, HttpStatus.OK, "Saved");
        } catch (JsonParseException e) {
            support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        } catch (CompletionException e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Save failed: " + message);
        } catch (Exception e) {
            support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Save failed: " + e.getMessage());
        }
    }

    private String normalizeTitle(String title) {
        // Route through the SAME sanitizer as every in-game rename/create path so the web editor
        // can't persist MiniMessage/legacy-color/control chars (< > & " ' U+00A7) into item names
        // that other players see via the publish/purchase flow; stripping control chars alone
        // lets stored injection through. validateMusicName trims, caps at 100, and returns null
        // for blank input (the caller already null/blank-checks the result).
        return PlayerMusicManager.getInstance().validateMusicName(title);
    }

    private String normalizeDescription(String description) {
        if (description == null) return "";
        if (description.length() > 1000) {
            description = description.substring(0, 1000);
        }
        // Strip the MiniMessage/legacy-color injection vectors (< > & U+00A7) in addition to
        // control chars, so a description can't inject formatting/hover-spoof into the disc lore
        // rendered for other players. Newlines/tabs are preserved for multi-line lore.
        return description.replaceAll("[<>&\\u00a7\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]", "");
    }
}
