package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.core.song.MusicBoxSong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

// Builds the floating text shown by a text display player. Shared by TextDisplayPlayer and
// the song-less IdleTextDisplay: pass current == null to render the idle placeholder.
final class TextDisplayContent {
    private static final int PROGRESS_BAR_SEGMENTS = 20;

    private TextDisplayContent() {
    }

    static Component build(String name, TextDisplayPlayer.DisplayOptions options, MusicBoxSong current, short currentTick, float speedMultiplier) {
        Component content = Component.empty();
        boolean hasLine = false;
        if (options.isShowName()) {
            content = appendLine(content, Component.text(name, NamedTextColor.GRAY), hasLine);
            hasLine = true;
        }
        if (current == null) {
            if (options.isShowSong()) {
                content = appendLine(content, Component.text("-", NamedTextColor.GRAY), hasLine);
                hasLine = true;
            }
            if (options.isShowProgress()) {
                content = appendLine(content, buildProgressBar(0.0), hasLine);
                hasLine = true;
            }
            if (options.isShowTime()) {
                content = appendLine(content, Component.text("0:00 / 0:00", NamedTextColor.GRAY), hasLine);
                hasLine = true;
            }
            return hasLine ? content : Component.text(name, NamedTextColor.DARK_GRAY);
        }

        short totalTicks = current.getLength();
        double progress = totalTicks <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (double) currentTick / (double) totalTicks));
        float effectiveSpeed = Math.max(0.1f, current.getSpeed() * speedMultiplier);
        String currentTime = formatClock((int) Math.floor(currentTick / effectiveSpeed));
        String totalTime = formatClock((int) Math.floor(totalTicks / effectiveSpeed));

        if (options.isShowSong()) {
            content = appendLine(content, Component.text(current.getName(), NamedTextColor.GREEN), hasLine);
            hasLine = true;
        }
        if (options.isShowProgress()) {
            content = appendLine(content, buildProgressBar(progress), hasLine);
            hasLine = true;
        }
        if (options.isShowTime()) {
            content = appendLine(content, Component.text(currentTime + " / " + totalTime, NamedTextColor.GRAY), hasLine);
            hasLine = true;
        }
        return hasLine ? content : Component.text(current.getName(), NamedTextColor.GREEN);
    }

    private static Component appendLine(Component base, Component line, boolean hasLine) {
        return hasLine ? base.append(Component.newline()).append(line) : base.append(line);
    }

    private static final String FULL_BAR = "|".repeat(PROGRESS_BAR_SEGMENTS);

    private static Component buildProgressBar(double progress) {
        int filled = Math.max(0, Math.min(PROGRESS_BAR_SEGMENTS, (int) Math.round(progress * PROGRESS_BAR_SEGMENTS)));
        if (filled == 0) {
            return Component.text(FULL_BAR, NamedTextColor.DARK_GRAY);
        }
        if (filled == PROGRESS_BAR_SEGMENTS) {
            return Component.text(FULL_BAR, NamedTextColor.GREEN);
        }
        return Component.text(FULL_BAR.substring(0, filled), NamedTextColor.GREEN)
            .append(Component.text(FULL_BAR.substring(filled), NamedTextColor.DARK_GRAY));
    }

    private static String formatClock(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        int minutes = safeSeconds / 60;
        int remainingSeconds = safeSeconds % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, remainingSeconds);
    }
}
