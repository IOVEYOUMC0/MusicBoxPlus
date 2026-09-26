package com.huidu.musicboxplus.common.utils.classes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PeekListTest {

    @Test
    void boundedListStopsAtEnds() {
        PeekList<String> p = new PeekList<>(List.of("a", "b", "c"), true);
        assertEquals("a", p.current());
        assertTrue(p.hasNext());
        assertFalse(p.hasPrev());

        assertTrue(p.next());
        assertEquals("b", p.current());
        assertTrue(p.next());
        assertEquals("c", p.current());

        assertFalse(p.hasNext());
        assertFalse(p.next(), "next past the end must not advance");
        assertEquals("c", p.current());
    }

    @Test
    void boundedListWalksBackToStart() {
        PeekList<String> p = new PeekList<>(List.of("a", "b", "c"), true);
        p.next();
        p.next(); // at c
        assertTrue(p.prev());
        assertTrue(p.prev());
        assertEquals("a", p.current());
        assertFalse(p.hasPrev());
        assertFalse(p.prev());
        assertEquals("a", p.current());
    }

    @Test
    void unboundedListCyclesForever() {
        PeekList<String> p = new PeekList<>(List.of("a", "b"), false);
        assertEquals("a", p.getAndNext());
        assertEquals("b", p.getAndNext());
        // wraps back to the start
        assertEquals("a", p.current());
        assertTrue(p.hasNext());
        assertTrue(p.hasPrev());
    }

    @Test
    void singleElementUnboundedStaysOnElement() {
        PeekList<String> p = new PeekList<>(List.of("only"), false);
        assertEquals("only", p.current());
        assertTrue(p.next());
        assertEquals("only", p.current());
    }

    @Test
    void peekAheadDoesNotMoveCursor() {
        PeekList<String> p = new PeekList<>(List.of("a", "b", "c"), true);
        assertEquals(List.of("b", "c"), p.getNextElements(2));
        assertEquals("a", p.current(), "peeking must not move the cursor");
        assertEquals(0, p.getCurrent());
    }

    @Test
    void moveToAndResetRepositionCursor() {
        PeekList<String> p = new PeekList<>(List.of("a", "b", "c"), true);
        p.moveTo("c");
        assertEquals("c", p.current());
        assertEquals(2, p.getIndexOf("c"));
        p.reset();
        assertEquals("a", p.current());
    }
}
