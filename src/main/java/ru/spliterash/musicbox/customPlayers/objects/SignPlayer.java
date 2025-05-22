package ru.spliterash.musicbox.customPlayers.objects;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.jetbrains.annotations.NotNull;
import ru.spliterash.musicbox.MusicBox;
import ru.spliterash.musicbox.customPlayers.abstracts.AbstractBlockPlayer;
import ru.spliterash.musicbox.customPlayers.interfaces.IPlayList;
import ru.spliterash.musicbox.db.DatabaseLoader;
import ru.spliterash.musicbox.utils.VersionUtils;
import ru.spliterash.musicbox.utils.BukkitUtils;
import ru.spliterash.musicbox.utils.FaceUtils;
import ru.spliterash.musicbox.utils.SignUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class SignPlayer extends AbstractBlockPlayer {
    public static final String SIGN_SECOND_LINE = String.format("%s[%sMUSIC%s]", ChatColor.GRAY, ChatColor.GREEN, ChatColor.GRAY);
    private final Sign sign;
    private final boolean preventDestroy;
    private Location infoSign;

    private static final VersionUtils versionUtils = new VersionUtils();

    private SignPlayer(IPlayList list, int range, Sign sign) {
        super(list, sign.getLocation(), range);
        this.sign = sign;
        preventDestroy = sign.getLine(3).contains("P");
        if (preventDestroy)
            getRangePlayerModel().setAutoDestroyMillis(0);
        setupInfoSign();
    }

    public static Set<SignPlayer> getPreventedPlayers() {
        return getAll()
                .stream()
                .filter(p -> p instanceof SignPlayer)
                .map(p -> (SignPlayer) p)
                .filter(SignPlayer::isPreventDestroy)
                .collect(Collectors.toSet());
    }

    public static void redstoneSign(Sign sign, int pin, int newCurrent) {
        SignPlayer player = AbstractBlockPlayer.findByLocation(sign.getLocation());
        if (pin == 0) {
            if (newCurrent > 0) {
                createSign(sign);
            } else if (player != null) {
                player.destroy();
            }
        } else if (player != null && newCurrent > 0) {
            IPlayList list = player.getPlayList();
            switch (pin) {
                case 1:
                    list.back(1);
                    break;
                case 2:
                    list.next();
                    break;
                default:
                    return;
            }
            player.destroy();
            new SignPlayer(list, player.getRange(), sign);
        }
    }

    private static void createSign(Sign sign) {
        int range = SignUtils.parseSignRange(sign);
        SignUtils
                .parseSignPlaylist(sign)
                .ifPresent(l -> new SignPlayer(l, range, sign));
    }

    public static void restorePreventedPlayers() {
        List<Location> locations = DatabaseLoader.getBase().getPreventedSigns();
        for (Location location : locations) {
            BukkitUtils.runSyncTask(() -> {
                @NotNull BlockState b = location.getBlock().getState();
                if (b instanceof Sign) {
                    Sign sign = (Sign) b;
                    if (isPlayerSign(sign)) {
                        createSign(sign);
                    }
                }
            });
        }
    }

    public static boolean isPlayerSign(Sign s) {
        return s.getLine(1).startsWith(SignPlayer.SIGN_SECOND_LINE);
    }

    private void pingLever() {
        BukkitUtils.runSyncTask(() -> {
            @NotNull Block block = Objects.requireNonNull(getTargetLocation()).getBlock();
            BlockFace face = versionUtils.getRotation(block);
            face = FaceUtils.invertFace(face);
            @NotNull Block leverBlock = block.getRelative(face, 2);

            if (Material.matchMaterial(String.valueOf(leverBlock.getType())) == Material.LEVER) {
                versionUtils.setLever(leverBlock, true);
                Bukkit.getScheduler().runTaskLater(MusicBox.getInstance(), () ->
                        versionUtils.setLever(leverBlock, false), 10);
            }
        });
    }

    private void setupInfoSign() {
        if (sign.getLine(3).contains("I"))
            SignUtils
                    .findSign(sign.getLocation())
                    .ifPresent(s -> {
                        infoSign = s.getLocation();
                        SignUtils.setPlayListInfo(infoSign, super.getPlayList());
                    });
    }

    @Override
    protected void every100MillisAsync() {
        BukkitUtils.runSyncTask(() -> {
            Block b = Objects.requireNonNull(getTargetLocation()).getBlock();
            if (!(b.getState() instanceof Sign) || !b.isBlockIndirectlyPowered())
                destroy();
        });
    }

    @Override
    protected SignPlayer runNextSong(IPlayList list) {
        return new SignPlayer(list, getRange(), sign);
    }

    @Override
    protected void songEnd() {
        pingLever();
    }
}