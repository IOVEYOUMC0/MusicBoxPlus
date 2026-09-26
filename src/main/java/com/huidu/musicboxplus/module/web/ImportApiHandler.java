package com.huidu.musicboxplus.module.web;

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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

// POST /api/import: import an NBS/MIDI file into the session's music, either by server path or
// as a multipart file upload.
final class ImportApiHandler implements HttpHandler {
    private final WebApiSupport support;
    private final Semaphore importPermits = new Semaphore(2);

    ImportApiHandler(WebApiSupport support) {
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

        if (!"POST".equals(exchange.getRequestMethod())) {
            support.writeTextResponse(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
            return;
        }

        Optional<String> sessionIdOpt = support.extractSessionId(exchange);
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

        boolean importPermit = false;
        try {
            if (!importPermits.tryAcquire()) {
                support.writeTextResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many imports in progress");
                return;
            }
            importPermit = true;
            String csrfToken = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
            boolean validCsrf = csrfToken != null && session.validateCsrfToken(csrfToken);
            if (!validCsrf) {
                support.writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Invalid CSRF token");
                return;
            }

            PlayerMusic targetMusic = PlayerMusicManager.getInstance()
                    .getMusicById(UUID.fromString(session.getMusicId()));
            if (targetMusic == null) {
                support.writeTextResponse(exchange, HttpStatus.NOT_FOUND, "Music not found");
                return;
            }
            if (support.canAccessMusic(session, targetMusic, exchange)) {
                return;
            }

            PlayerMusic beforeSnapshot = targetMusic.snapshot();
            MusicFileImporter.ImportResult importResult = readImportRequest(exchange, targetMusic);

            PlayerMusic importedMusic = importResult.music();
            PlayerMusic importedSnapshot = importedMusic.snapshot();
            boolean appliedImport = false;
            try {
                PlayerMusic mergedSnapshot = beforeSnapshot.snapshot();
                mergedSnapshot.setName(importedSnapshot.getName());
                mergedSnapshot.setBpm(importedSnapshot.getBpm());
                mergedSnapshot.setBeatSubdivision(importedSnapshot.getBeatSubdivision());
                mergedSnapshot.setTimeSignature(importedSnapshot.getTimeSignature());
                mergedSnapshot.setDescription(importedSnapshot.getDescription());
                mergedSnapshot.clearNotes();
                for (MusicNote note : importedSnapshot.getNotes()) {
                    mergedSnapshot.addNote(new MusicNote(note.getPitch(), note.getTick(), new ArrayList<>(note.getInstruments())));
                }
                targetMusic.applySnapshot(mergedSnapshot);
                appliedImport = true;

                boolean saved = PlayerMusicManager.getInstance().saveMusicSync(targetMusic);
                if (!saved) {
                    targetMusic.applySnapshot(beforeSnapshot);
                    appliedImport = false;
                    support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Import save failed");
                    return;
                }
                MusicEditListener.notifyMusicUpdated(targetMusic.getUniqueId());
            } catch (RuntimeException e) {
                if (appliedImport) {
                    targetMusic.applySnapshot(beforeSnapshot);
                }
                throw e;
            } finally {
                PlayerMusicManager.getInstance().deleteMusic(importedMusic.getUniqueId());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("format", importResult.format().getDisplayName());
            response.put("warnings", importResult.warnings());
            response.put("music", support.buildMusicPayload(targetMusic.snapshot()));
            support.writeJsonResponse(exchange, HttpStatus.OK, response);
        } catch (JsonParseException e) {
            support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            support.writeTextResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Import failed: " + e.getMessage());
        } finally {
            if (importPermit) {
                importPermits.release();
            }
            support.sessionManager().disconnect(sessionId);
        }
    }

    private MusicFileImporter.ImportResult readImportRequest(HttpExchange exchange, PlayerMusic targetMusic) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            UploadedImportFile upload = parseMultipartUpload(exchange, contentType);
            return importUploadedFile(upload, targetMusic);
        }

        String body = support.readRequestBody(exchange, support.getMaxRequestSize());
        JsonObject json = support.json().fromJson(body, JsonObject.class);
        if (json == null || !json.has("path") || !json.get("path").isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing import path");
        }

        String requestedPath = json.get("path").getAsString();
        return MusicFileImporter.getInstance()
                .importByName(requestedPath, targetMusic.getAuthor(), targetMusic.getAuthorUUID());
    }

    private UploadedImportFile parseMultipartUpload(HttpExchange exchange, String contentType) throws IOException {
        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("Missing multipart boundary");
        }

        int multipartLimit = (int) Math.min(
                support.getMaxRequestSize(), MusicFileImporter.MAX_IMPORT_FILE_BYTES + 64L * 1024L);
        byte[] body = support.readRequestBodyBytes(exchange, multipartLimit);
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] partDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerSeparator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int cursor = indexOf(body, delimiter, 0);

        while (cursor >= 0) {
            int partStart = cursor + delimiter.length;
            if (startsWith(body, "--".getBytes(StandardCharsets.ISO_8859_1), partStart)) {
                break;
            }
            if (startsWith(body, "\r\n".getBytes(StandardCharsets.ISO_8859_1), partStart)) {
                partStart += 2;
            }

            int headerEnd = indexOf(body, headerSeparator, partStart);
            if (headerEnd < 0) {
                break;
            }

            String headers = new String(body, partStart, headerEnd - partStart, StandardCharsets.ISO_8859_1);
            int dataStart = headerEnd + 4;
            int nextDelimiter = indexOf(body, partDelimiter, dataStart);
            if (nextDelimiter < 0) {
                break;
            }

            if (headers.toLowerCase(Locale.ROOT).contains("name=\"file\"")) {
                String fileName = extractMultipartFileName(decodeMultipartHeaders(headers));
                if (fileName == null) {
                    fileName = extractMultipartFileName(headers);
                }
                byte[] fileBytes = java.util.Arrays.copyOfRange(body, dataStart, nextDelimiter);
                if (fileName == null || fileName.isBlank()) {
                    throw new IllegalArgumentException("Missing upload file name");
                }
                if (fileBytes.length == 0) {
                    throw new IllegalArgumentException("Uploaded file is empty");
                }
                String safeFileName = validateImportFileName(fileName);
                return new UploadedImportFile(safeFileName, fileBytes);
            }

            cursor = nextDelimiter + 2;
        }

        throw new IllegalArgumentException("Missing upload file");
    }

    static int indexOf(byte[] data, byte[] target, int from) {
        int limit = data.length - target.length;
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (startsWith(data, target, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, byte[] target, int offset) {
        if (offset < 0 || offset + target.length > data.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (data[offset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    private String decodeMultipartHeaders(String headers) {
        return new String(headers.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private String extractMultipartBoundary(String contentType) {
        for (String segment : contentType.split(";")) {
            String trimmed = segment.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length());
                if (boundary.length() >= 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    private String extractMultipartFileName(String headers) {
        for (String header : headers.split("\r\n")) {
            if (!header.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) {
                continue;
            }
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("filename*=")) {
                    String fileName = decodeExtendedMultipartFileName(trimmed.substring("filename*=".length()));
                    if (fileName != null && !fileName.isBlank()) {
                        return fileName;
                    }
                }
                if (lower.startsWith("filename=")) {
                    String fileName = trimmed.substring("filename=".length());
                    if (fileName.length() >= 2 && fileName.startsWith("\"") && fileName.endsWith("\"")) {
                        fileName = fileName.substring(1, fileName.length() - 1);
                    }
                    return fileName.replace("\\\"", "\"");
                }
            }
        }
        return null;
    }

    private String decodeExtendedMultipartFileName(String value) {
        String encoded = value.trim();
        if (encoded.length() >= 2 && encoded.startsWith("\"") && encoded.endsWith("\"")) {
            encoded = encoded.substring(1, encoded.length() - 1);
        }

        int firstQuote = encoded.indexOf('\'');
        int secondQuote = firstQuote >= 0 ? encoded.indexOf('\'', firstQuote + 1) : -1;
        try {
            if (firstQuote > 0 && secondQuote > firstQuote) {
                Charset charset = Charset.forName(encoded.substring(0, firstQuote));
                return URLDecoder.decode(encoded.substring(secondQuote + 1), charset);
            }
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String validateImportFileName(String fileName) throws IOException {
        String candidateName = fileName.trim().replace('\\', '/');
        int slashIndex = candidateName.lastIndexOf('/');
        if (slashIndex >= 0) {
            candidateName = candidateName.substring(slashIndex + 1);
        }

        String normalizedName;
        try {
            normalizedName = Path.of(candidateName).getFileName().toString();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid upload file name");
        }
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Invalid upload file name");
        }
        MusicFileImporter.getInstance().detectFormat(normalizedName);
        return normalizedName;
    }

    private MusicFileImporter.ImportResult importUploadedFile(UploadedImportFile upload, PlayerMusic targetMusic) throws IOException {
        String safeName = upload.fileName();
        MusicFileImporter.ImportResult result = MusicFileImporter.getInstance()
                .importFromBytes(safeName, upload.bytes(), targetMusic.getAuthor(), targetMusic.getAuthorUUID());
        // Through the same sanitizer as every other name write. The title comes from the uploaded
        // file, and names are substituted into language strings that are then MiniMessage-parsed,
        // so an unfiltered one becomes live markup in the admin's review chat.
        String importedName = PlayerMusicManager.getInstance().validateMusicName(result.music().getName());
        result.music().setName(importedName == null || importedName.isBlank()
                ? stripFileExtension(safeName)
                : importedName);
        return result;
    }

    private String stripFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private record UploadedImportFile(String fileName, byte[] bytes) {
    }
}
