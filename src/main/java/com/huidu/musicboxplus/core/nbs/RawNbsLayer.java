package com.huidu.musicboxplus.core.nbs;

// One layer of the file. volume and panning are both unsigned bytes; panning 100 means centred.
// locked only exists from format v4 on and panning from v2 on; the reader fills in defaults below that.
public record RawNbsLayer(
        int index,
        String name,
        boolean locked,
        int volume,
        int panning) {
}
