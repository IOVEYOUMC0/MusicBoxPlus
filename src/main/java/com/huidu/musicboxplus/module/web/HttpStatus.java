package com.huidu.musicboxplus.module.web;

// HTTP status codes used by the web editor server. Named so the intent of each
// response is visible at the call site instead of a bare integer.
public final class HttpStatus {

    public static final int OK = 200;
    public static final int NO_CONTENT = 204;
    public static final int NOT_MODIFIED = 304;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int CONFLICT = 409;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int INTERNAL_SERVER_ERROR = 500;

    private HttpStatus() {
    }
}