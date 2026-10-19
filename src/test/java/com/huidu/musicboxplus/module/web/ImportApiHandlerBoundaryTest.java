package com.huidu.musicboxplus.module.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

// The multipart boundary, which decides how much work a client can make the body scan do.
//
// extractMultipartBoundary is what stops a client from choosing an arbitrarily long boundary: the
// parser searches the whole body for "--" + boundary at every position, so the length of a
// client-supplied boundary is a multiplier on that scan.
class ImportApiHandlerBoundaryTest {

    @Test
    void readsAPlainBoundary() {
        assertEquals("----WebKitFormBoundaryABC123",
                ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary=----WebKitFormBoundaryABC123"));
    }

    @Test
    void readsAQuotedBoundaryAndIgnoresOtherParameters() {
        assertEquals("mb",
                ImportApiHandler.extractMultipartBoundary("multipart/form-data; charset=utf-8; boundary=\"mb\""));
    }

    @Test
    void isCaseInsensitiveAboutTheParameterName() {
        assertEquals("mb", ImportApiHandler.extractMultipartBoundary("Multipart/Form-Data; Boundary=mb"));
    }

    @Test
    void acceptsABoundaryOfExactlyTheRfcMaximum() {
        String boundary = "b".repeat(ImportApiHandler.MAX_BOUNDARY_LENGTH);

        assertEquals(boundary, ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary=" + boundary));
    }

    @Test
    void rejectsABoundaryLongerThanTheRfcMaximum() {
        String boundary = "b".repeat(ImportApiHandler.MAX_BOUNDARY_LENGTH + 1);

        assertNull(ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary=" + boundary),
                "a client-chosen boundary length must not reach the body scan");
    }

    @Test
    void rejectsTheLongQuotedFormToo() {
        String boundary = "b".repeat(ImportApiHandler.MAX_BOUNDARY_LENGTH + 1);

        assertNull(ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary=\"" + boundary + "\""));
    }

    @Test
    void reportsMissingAndEmptyBoundariesAsAbsent() {
        assertNull(ImportApiHandler.extractMultipartBoundary("multipart/form-data"));
        assertNull(ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary="));
        assertNull(ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary=\"\""));
        assertNull(ImportApiHandler.extractMultipartBoundary(null));
    }

    @Test
    void theMissingBoundaryPathIsAnErrorNotASearch() {
        // What the caller relies on: null means it rejects the request instead of searching for an
        // empty delimiter, which indexOf would match at position 0.
        assertNull(ImportApiHandler.extractMultipartBoundary("multipart/form-data; boundary="));
    }
}
