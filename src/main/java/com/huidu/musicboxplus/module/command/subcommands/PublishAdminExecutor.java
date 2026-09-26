package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.module.command.SubCommand;
import com.huidu.musicboxplus.module.edit.gui.PublishReviewGUI;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Admin review for the publish approval flow: /musicboxplus publish review opens the review
// GUI, list prints the pending queue, approve/reject decide a listing by its id. Requires
// musicboxplus.publish.review (or admin). Authors are notified by the manager when online.
public class PublishAdminExecutor implements SubCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "review" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtils.send(sender, Lang.ONLY_PLAYERS);
                    return;
                }
                if (PublishedMusicManager.getInstance().getPendingPublished().isEmpty()) {
                    MessageUtils.send(player, Lang.PUBLISH_REVIEW_EMPTY);
                    return;
                }
                new PublishReviewGUI(player).open();
            }
            case "list" -> listPending(sender);
            case "approve" -> decide(sender, args, true);
            case "reject" -> decide(sender, args, false);
            default -> MessageUtils.send(sender, Lang.PUBLISH_REVIEW_USAGE);
        }
    }

    private void listPending(CommandSender sender) {
        List<PublishedMusic> pending = PublishedMusicManager.getInstance().getPendingPublished();
        if (pending.isEmpty()) {
            MessageUtils.send(sender, Lang.PUBLISH_REVIEW_EMPTY);
            return;
        }
        MessageUtils.send(sender, Lang.PUBLISH_REVIEW_LIST_HEADER);
        for (PublishedMusic published : pending) {
            MessageUtils.send(sender, Lang.PUBLISH_REVIEW_LIST_ENTRY,
                    "{id}", published.getUniqueId().toString(),
                    "{name}", published.getName(),
                    "{author}", published.getAuthor(),
                    "{price}", String.format("%.0f", published.getPrice()));
        }
    }

    private void decide(CommandSender sender, String[] args, boolean approve) {
        if (args.length < 2) {
            MessageUtils.send(sender, Lang.PUBLISH_REVIEW_USAGE);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            MessageUtils.send(sender, Lang.PUBLISH_REVIEW_USAGE);
            return;
        }
        boolean success = approve
                ? PublishedMusicManager.getInstance().approveMusic(id)
                : PublishedMusicManager.getInstance().rejectMusic(id);
        if (!success) {
            MessageUtils.send(sender, Lang.PUBLISH_REVIEW_NOT_FOUND);
            return;
        }
        PublishedMusic published = PublishedMusicManager.getInstance().getPublishedMusic(id);
        String name = published != null ? published.getName() : "";
        MessageUtils.send(sender, approve ? Lang.PUBLISH_REVIEW_APPROVED : Lang.PUBLISH_REVIEW_REJECTED,
                "{name}", name);
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.PUBLISH_REVIEW);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("review", "list", "approve", "reject")
                    .filter(option -> option.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("approve") || args[0].equalsIgnoreCase("reject"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            for (PublishedMusic published : PublishedMusicManager.getInstance().getPendingPublished()) {
                String id = published.getUniqueId().toString();
                if (id.startsWith(prefix)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        return Collections.emptyList();
    }
}
