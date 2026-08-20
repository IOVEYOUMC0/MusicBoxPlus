package com.huidu.musicboxplus.common.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.block.SignChangeEvent;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class SignUtils {
    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacySection();
    public static final int MAX_SIGN_RANGE = 256;
    public static final int DEFAULT_SIGN_RANGE = 24;

    public static String getSignLine(Sign sign, int index) {
        Component lineComponent = SignUtils.getFrontSide(sign).line(index);
        if (lineComponent != null) {
            return SERIALIZER.serialize(lineComponent);
        }
        return "";
    }

    public static String getEventLine(SignChangeEvent event, int index) {
        Component lineComponent = event.line(index);
        if (lineComponent != null) {
            return SERIALIZER.serialize(lineComponent);
        }
        return "";
    }

    private static final Pattern COLOR_PATTERN = Pattern.compile("[&\u00a7][0-9a-fA-Fk-oK-OrR]");

    // Legacy colour codes only. Deliberately not StringUtils.stripAllColors, which
    // round-trips through MiniMessage and would also eat tags that belong to the text.
    public static String stripColor(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return COLOR_PATTERN.matcher(text).replaceAll("");
    }

    public static boolean hasEnd(Sign sign) {
        String lineThree = SignUtils.getSignLine(sign, 3);
        return !lineThree.contains("E");
    }

    public static int parseSignRange(Sign sign) {
        int range;
        try {
            String lineTwo = SignUtils.getSignLine(sign, 2);
            range = Integer.parseInt(SignUtils.stripColor(lineTwo));
            // Clamp both ends: a negative range would make the sign player emit nothing
            // (or misbehave downstream), so fall back to the default just like a parse error.
            if (range < 0) {
                range = DEFAULT_SIGN_RANGE;
            } else if (range > MAX_SIGN_RANGE) {
                range = MAX_SIGN_RANGE;
            }
        } catch (Exception exception) {
            range = DEFAULT_SIGN_RANGE;
        }
        return range;
    }

    public static Optional<Sign> findSign(Location startLoc) {
        BukkitUtils.checkPrimary();

        Sign emptySign = null;
        Sign contentSign = null;

        Location location = startLoc.clone();
        for (int i = 0; i < 5; ++i) {
            location.add(0.0, 1.0, 0.0);
            Block block = location.getBlock();
            if (!(block.getState() instanceof Sign sign)) {
                continue;
            }
            if (SignUtils.isEmptySign(sign)) {
                if (emptySign == null) {
                    emptySign = sign;
                }
            } else if (contentSign == null) {
                contentSign = sign;
            }
        }

        location = startLoc.clone();
        for (int i = 0; i < 5; ++i) {
            location.add(0.0, -1.0, 0.0);
            Block block = location.getBlock();
            if (!(block.getState() instanceof Sign sign)) {
                continue;
            }
            if (SignUtils.isEmptySign(sign)) {
                if (emptySign == null) {
                    emptySign = sign;
                }
            } else if (contentSign == null) {
                contentSign = sign;
            }
        }

        if (emptySign != null) {
            return Optional.of(emptySign);
        }
        return Optional.ofNullable(contentSign);
    }

    private static boolean isEmptySign(Sign sign) {
        for (int i = 0; i < 4; ++i) {
            String line = SignUtils.getSignLine(sign, i);
            if (line != null && !line.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static void setSignList(Sign sign, List<String> list) {
        SignSide signSide = SignUtils.getFrontSide(sign);
        for (int i = 0; i < 4; ++i) {
            String str = list.size() > i ? list.get(i) : "";
            signSide.line(i, SERIALIZER.deserialize(str));
        }
        sign.update();
    }

    public static void setSignLine(Sign sign, int index, String text) {
        SignUtils.setSignLine(sign, index, SERIALIZER.deserialize(text != null ? text : ""));
    }

    public static void setSignLine(Sign sign, int index, Component text) {
        SignUtils.getFrontSide(sign).line(index, text != null ? text : Component.empty());
    }

    public static Component getSignLineComponent(Sign sign, int index) {
        Component component = SignUtils.getFrontSide(sign).line(index);
        return component != null ? component : Component.empty();
    }

    public static void clearInfoSign(Location signLocation) {
        BukkitUtils.checkPrimary();
        Block block = signLocation.getBlock();
        if (block.getState() instanceof Sign sign) {
            for (int i = 0; i < 4; ++i) {
                SignUtils.setSignLine(sign, i, Component.empty());
            }
            sign.update();
        }
    }

    private static SignSide getFrontSide(Sign sign) {
        return sign.getSide(Side.FRONT);
    }

    private SignUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
