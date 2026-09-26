package com.huidu.musicboxplus.core.nbs;

import java.io.IOException;
import java.io.Serial;

// Parse failure. The message carries the field name and byte offset, which tells a corrupt file
// apart from a parser reading the wrong thing.
public class NbsFormatException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NbsFormatException(String field, long offset, String detail) {
        super("读取 NBS 字段 \"" + field + "\" 失败（偏移 " + offset + "）：" + detail);
    }

    public NbsFormatException(String message) {
        super(message);
    }
}
