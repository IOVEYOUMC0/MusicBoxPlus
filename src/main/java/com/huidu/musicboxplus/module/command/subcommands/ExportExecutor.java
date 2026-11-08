package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.command.SubCommand;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.io.MusicExportService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /musicboxplus export <player|uuid>}: writes every song a player owns as an .nbs file.
 *
 * <p>Player-made music only exists inside the database. Without an export path, a player who wants
 * their work back -- or a server that has to migrate to another machine -- depends on hand-copying
 * SQLite files. The conversion already existed for the editor's export button; this only exposes it
 * to operators in bulk.
 */
public class ExportExecutor implements SubCommand {

    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    public void execute(CommandSender sender, String[] args) {
        MusicBox plugin = MusicBox.getInstance();
        if (args.length == 0) {
            MessageUtils.send(sender, LogLocale.text(plugin,
                    "Usage: /musicboxplus export <player|uuid>",
                    "用法: /musicboxplus export <玩家名|UUID>"));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            MessageUtils.send(sender, LogLocale.text(plugin,
                    "An export is already running.", "已有导出正在进行中。"));
            return;
        }
        String target = args[0];
        // Resolving a name can block (it may query Mojang for an unknown player), so it happens off
        // the tick thread together with the export itself.
        AsyncTaskManager.runAsync(() -> {
            try {
                UUID owner = resolveOwner(target);
                if (owner == null) {
                    finish(sender, LogLocale.text(plugin,
                            "Unknown player: " + target, "未知玩家: " + target));
                    return;
                }
                List<PlayerMusic> songs = PlayerMusicManager.getInstance().getMusicByPlayer(owner);
                if (songs.isEmpty()) {
                    finish(sender, LogLocale.text(plugin,
                            "That player has no songs.", "该玩家没有作品。"));
                    return;
                }
                File targetDir = new File(plugin.getDataFolder(),
                        "exports/" + safeFolderName(target) + "-" + System.currentTimeMillis());
                MusicExportService.ExportResult result = MusicExportService.export(songs, targetDir);
                finish(sender, buildReport(result));
            } catch (Exception failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Music export failed", failure);
                finish(sender, LogLocale.text(plugin,
                        "Export failed: " + failure.getMessage(),
                        "导出失败: " + failure.getMessage()));
            } finally {
                running.set(false);
            }
        }, () -> {
            running.set(false);
            Scheduler.global(() -> MessageUtils.send(sender, LogLocale.text(plugin,
                    "Export was rejected because the async queue is full.", "异步队列已满，导出未执行。")));
        });
    }

    private String buildReport(MusicExportService.ExportResult result) {
        MusicBox plugin = MusicBox.getInstance();
        StringBuilder message = new StringBuilder(LogLocale.text(plugin,
                "Exported " + result.files().size() + " song(s) to " + result.directory().getPath(),
                "已导出 " + result.files().size() + " 首作品到 " + result.directory().getPath()));
        if (!result.failures().isEmpty()) {
            message.append('\n').append(LogLocale.text(plugin,
                    "Failed: " + String.join(", ", result.failures()),
                    "失败: " + String.join(", ", result.failures())));
        }
        if (!result.warnings().isEmpty()) {
            message.append('\n').append(LogLocale.text(plugin,
                    "Warnings: " + String.join(", ", result.warnings()),
                    "警告: " + String.join(", ", result.warnings())));
        }
        return message.toString();
    }

    /** Never call after the async hop; logs the outcome from the global thread. */
    private void finish(CommandSender sender, String message) {
        Scheduler.global(() -> MessageUtils.send(sender, message));
    }

    private static UUID resolveOwner(String target) {
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException notAUuid) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
            return offline == null ? null : offline.getUniqueId();
        }
    }

    private static String safeFolderName(String name) {
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? "player" : sanitized;
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.RELOAD);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
