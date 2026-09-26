package com.huidu.musicboxplus.module.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportApiHandlerTest {

    @Test
    void findsMultipartBoundaryWithoutConvertingBinaryBodyToString() {
        byte[] body = new byte[]{0, (byte) 0xFF, 12, '\r', '\n', '-', '-', 'm', 'b', 0};
        byte[] boundary = "\r\n--mb".getBytes(StandardCharsets.ISO_8859_1);

        assertEquals(3, ImportApiHandler.indexOf(body, boundary, 0));
        assertEquals(-1, ImportApiHandler.indexOf(body, boundary, 4));
    }
}
