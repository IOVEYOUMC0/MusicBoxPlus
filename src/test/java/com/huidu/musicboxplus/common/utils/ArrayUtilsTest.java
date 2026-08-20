package com.huidu.musicboxplus.common.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArrayUtilsTest {

    @Test
    void replaceOrRemoveMapReturnsNewListWithSubstitutions() {
        List<String> input = Arrays.asList("hi {name}, you have {count} msgs", "static");
        Map<String, String> map = new HashMap<>();
        map.put("{name}", "Alice");
        map.put("{count}", "5");
        List<String> result = ArrayUtils.replaceOrRemove(input, map);
        assertEquals(Arrays.asList("hi Alice, you have 5 msgs", "static"), result);
        // Source list must not be mutated.
        assertEquals(Arrays.asList("hi {name}, you have {count} msgs", "static"), input);
    }

    @Test
    void replaceOrRemoveMapDropsLinesWhenAnyTokenMapsToNullOrEmpty() {
        List<String> input = Arrays.asList("keep me {a}", "drop me {b}", "drop me too {c}");
        Map<String, String> map = new HashMap<>();
        map.put("{a}", "X");
        map.put("{b}", null);
        map.put("{c}", "");
        List<String> result = ArrayUtils.replaceOrRemove(input, map);
        assertEquals(Collections.singletonList("keep me X"), result);
    }

    @Test
    void replaceOrRemoveMapPreservesNullElementsAsIs() {
        List<String> input = new ArrayList<>();
        input.add("a {k}");
        input.add(null);
        input.add("c");
        Map<String, String> map = new HashMap<>();
        map.put("{k}", "X");
        List<String> result = ArrayUtils.replaceOrRemove(input, map);
        assertEquals(3, result.size());
        assertEquals("a X", result.get(0));
        assertNull(result.get(1));
        assertEquals("c", result.get(2));
    }

    @Test
    void removeFirstStripsHeadAndPreservesTail() {
        String[] in = {"a", "b", "c"};
        String[] out = ArrayUtils.removeFirst(String.class, in);
        assertArrayEquals(new String[]{"b", "c"}, out);
    }

    @Test
    void removeFirstOnEmptyArrayReturnsSameInstance() {
        String[] in = new String[0];
        String[] out = ArrayUtils.removeFirst(String.class, in);
        // For empty input the impl returns the input array as-is.
        assertSame(in, out);
    }

    @Test
    void removeFirstOnSingleElementReturnsEmptyArray() {
        Integer[] in = {7};
        Integer[] out = ArrayUtils.removeFirst(Integer.class, in);
        assertEquals(0, out.length);
    }

    @Test
    void getRandomOnNullOrEmptyListReturnsNull() {
        assertNull(ArrayUtils.getRandom(null));
        assertNull(ArrayUtils.getRandom(Collections.emptyList()));
    }

    @Test
    void getRandomOnSingleElementReturnsThatElement() {
        List<String> list = Collections.singletonList("only");
        assertEquals("only", ArrayUtils.getRandom(list));
    }

    @Test
    void getRandomReturnsAnElementFromTheList() {
        List<String> list = Arrays.asList("a", "b", "c");
        // Probabilistic check: pick many times, every result must be from the list.
        for (int i = 0; i < 50; i++) {
            String picked = ArrayUtils.getRandom(list);
            assertNotNull(picked);
            assertTrue(list.contains(picked));
        }
    }
}
