package com.huidu.musicboxplus.module.edit.publish;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.EconomyUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PublishedMusicManager {

    private static volatile PublishedMusicManager instance;
    private static final Object LOCK = new Object();
    private File publishedFolder;
    private final File revenueFile;
    private final Map<UUID, PublishedMusic> publishedMusicCache = new ConcurrentHashMap<>();
    private final Map<UUID, List<PublishedMusic>> authorMusicCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pendingRevenue = new ConcurrentHashMap<>();

    public static PublishedMusicManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new PublishedMusicManager();
                }
            }
        }
        return instance;
    }

    public static PublishedMusicManager getExistingInstance() {
        return instance;
    }

    private PublishedMusicManager() {
        this.publishedFolder = resolvePublishedFolder();
        ensurePublishedFolder();
        this.revenueFile = new File(MusicBox.getInstance().getDataFolder(), "pending_revenue.yml");
        loadAllPublishedMusic();
        loadPendingRevenue();
    }

    private File resolvePublishedFolder() {
        MusicBoxConfig config = MusicBox.getInstance().getConfigObject();
        String folderName = config != null && config.getStorage() != null
                ? config.getStorage().getPublishedMusicFolder()
                : "PublishedMusic";
        if (folderName == null || folderName.trim().isEmpty()) {
            folderName = "PublishedMusic";
        }
        File folder = new File(folderName.trim());
        if (folder.isAbsolute()) {
            return folder;
        }
        return new File(MusicBox.getInstance().getDataFolder(), folderName.trim());
    }

    private void ensurePublishedFolder() {
        if (!publishedFolder.exists() && !publishedFolder.mkdirs()) {
            MusicBox.getInstance().getLogger().warning("无法创建发布音乐目录: " + publishedFolder.getAbsolutePath());
        }
    }

    private void loadPendingRevenue() {
        if (!revenueFile.exists()) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(revenueFile);
            for (String key : config.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    double amount = config.getDouble(key, 0);
                    if (amount > 0) {
                        pendingRevenue.put(uuid, amount);
                    }
                } catch (IllegalArgumentException e) {
                    MusicBox.getInstance().getLogger().warning("跳过无效的收入UUID: " + key);
                }
            }
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING, "加载待领取收入数据失败", e);
        }
    }

    private void savePendingRevenue() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, Double> entry : pendingRevenue.entrySet()) {
                if (entry.getValue() > 0) {
                    config.set(entry.getKey().toString(), entry.getValue());
                }
            }
            config.save(revenueFile);
        } catch (IOException e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING, "保存待领取收入数据失败", e);
        }
    }

    public void loadAllPublishedMusic() {
        this.publishedFolder = resolvePublishedFolder();
        ensurePublishedFolder();
        publishedMusicCache.clear();
        authorMusicCache.clear();

        File[] authorFolders = publishedFolder.listFiles(File::isDirectory);
        if (authorFolders == null) {
            return;
        }

        for (File authorFolder : authorFolders) {
            try {
                UUID authorUUID = UUID.fromString(authorFolder.getName());
                loadAuthorPublishedMusic(authorUUID);
            } catch (IllegalArgumentException e) {
                MusicBox.getInstance().getLogger().warning("跳过无效的作者文件夹: " + authorFolder.getName());
            }
        }
    }

    public void loadAuthorPublishedMusic(UUID authorUUID) {
        File authorFolder = new File(publishedFolder, authorUUID.toString());
        if (!authorFolder.exists() || !authorFolder.isDirectory()) {
            return;
        }

        List<PublishedMusic> musicList = Collections.synchronizedList(new ArrayList<>());
        File[] musicFiles = authorFolder.listFiles((dir, name) -> name.endsWith(".yml"));

        if (musicFiles != null) {
            for (File musicFile : musicFiles) {
                try {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(musicFile);
                    PublishedMusic published = PublishedMusic.fromConfig(config);
                    if (published != null) {
                        musicList.add(published);
                        publishedMusicCache.put(published.getUniqueId(), published);
                    }
                } catch (Exception e) {
                    MusicBox.getInstance().getLogger().log(Level.WARNING,
                            "加载发布音乐失败: " + musicFile.getName(), e);
                }
            }
        }

        authorMusicCache.put(authorUUID, musicList);
    }

    public PublishResult publishMusic(PlayerMusic music, double price, Player publisher) {
        return publishMusic(music, price, publisher, music == null ? "" : music.getDescription());
    }

    public PublishResult publishMusic(PlayerMusic music, double price, Player publisher, String description) {
        if (music == null) {
            return PublishResult.FAILED;
        }

        if (publisher == null || !music.getAuthorUUID().equals(publisher.getUniqueId())) {
            return PublishResult.FAILED;
        }

        if (price < 0) {
            return PublishResult.INVALID_PRICE;
        }

        double minPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice();
        double maxPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice();
        
        if (price < minPrice || price > maxPrice) {
            return PublishResult.PRICE_OUT_OF_RANGE;
        }

        PublishedMusic published = findByOriginalMusicId(music.getAuthorUUID(), music.getUniqueId());
        boolean isNewListing = false;
        if (published == null) {
            published = new PublishedMusic(music, price);
            isNewListing = true;
        } else {
            published.updateFromMusic(music);
            published.setPrice(price);
            published.setAvailable(true);
        }
        published.setDescription(description == null ? "" : description);

        // Review flow: with requireApproval on, a fresh listing and a rejected one being
        // resubmitted both land in PENDING; a listing already in review stays PENDING. With
        // approval off, everything is APPROVED immediately (legacy behavior).
        boolean requireApproval = MusicBox.getInstance().getConfigObject().getPublishConfig().isRequireApproval();
        if (requireApproval && (isNewListing || published.getStatus() == PublishedMusic.PublishedMusicStatus.REJECTED)) {
            published.setStatus(PublishedMusic.PublishedMusicStatus.PENDING);
        } else if (!requireApproval) {
            published.setStatus(PublishedMusic.PublishedMusicStatus.APPROVED);
        }

        publishedMusicCache.put(published.getUniqueId(), published);
        List<PublishedMusic> authorList = authorMusicCache.computeIfAbsent(
                music.getAuthorUUID(), k -> Collections.synchronizedList(new ArrayList<>()));
        if (isNewListing) {
            authorList.add(published);
        } else {
            UUID publishedId = published.getUniqueId();
            authorList.removeIf(p -> p.getUniqueId().equals(publishedId));
            authorList.add(published);
        }
        savePublishedMusic(published);
        
        return PublishResult.SUCCESS;
    }

    private PublishedMusic findByOriginalMusicId(UUID authorUUID, UUID originalMusicId) {
        if (authorUUID == null || originalMusicId == null) {
            return null;
        }

        return getPublishedByAuthor(authorUUID).stream()
                .filter(p -> originalMusicId.equals(p.getOriginalMusicId()))
                .findFirst()
                .orElse(null);
    }

    public boolean unpublishMusic(UUID publishedId, UUID requesterUUID) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }

        if (!published.getAuthorUUID().equals(requesterUUID)) {
            return false;
        }

        published.setAvailable(false);
        savePublishedMusic(published);
        
        return true;
    }

    // Called when a player deletes a source PlayerMusic: the listing that pointed at it must go
    // too, otherwise the dangling originalMusicId makes the author-claim flow silently rebuild a
    // fresh copy and leave the stale listing forever.
    public void onSourceMusicDeleted(UUID authorUUID, UUID originalMusicId) {
        PublishedMusic published = findByOriginalMusicId(authorUUID, originalMusicId);
        if (published != null) {
            deletePublishedMusic(published.getUniqueId(), authorUUID);
        }
    }

    public boolean republishMusic(UUID publishedId, UUID requesterUUID) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }

        if (!published.getAuthorUUID().equals(requesterUUID)) {
            return false;
        }

        // With approval on, a rejected listing going back on sale must pass review again;
        // an already-approved listing simply goes back on sale as before.
        boolean requireApproval = MusicBox.getInstance().getConfigObject().getPublishConfig().isRequireApproval();
        if (requireApproval && !published.isApproved()) {
            published.setStatus(PublishedMusic.PublishedMusicStatus.PENDING);
        }

        published.setAvailable(true);
        savePublishedMusic(published);
        
        return true;
    }

    public boolean updatePublishedMusic(UUID publishedId, PlayerMusic music, UUID requesterUUID) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }

        if (!published.getAuthorUUID().equals(requesterUUID)) {
            return false;
        }

        published.updateFromMusic(music);
        savePublishedMusic(published);
        
        return true;
    }

    public boolean updatePrice(UUID publishedId, double newPrice, UUID requesterUUID) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }

        if (!published.getAuthorUUID().equals(requesterUUID)) {
            return false;
        }

        double minPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice();
        double maxPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice();
        
        if (newPrice < minPrice || newPrice > maxPrice) {
            return false;
        }

        published.setPrice(newPrice);
        savePublishedMusic(published);
        
        return true;
    }

    // price is the amount captured when the buyer confirmed the purchase. Using it here and in
    // recordSuccessfulPurchase means a price change by the author mid-purchase can never split
    // the withdrawal and the revenue accounting onto two different amounts.
    public PurchaseResult purchaseMusic(UUID publishedId, Player buyer, double price) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return PurchaseResult.NOT_FOUND;
        }

        if (!published.isAvailable()) {
            return PurchaseResult.NOT_AVAILABLE;
        }

        if (published.getAuthorUUID().equals(buyer.getUniqueId())) {
            return canClaimOwnMusic(published, buyer) ? PurchaseResult.AUTHOR_CLAIM : PurchaseResult.OWN_MUSIC;
        }

        if (EconomyUtils.cannotBuy(buyer, price)) {
            return PurchaseResult.NO_MONEY;
        }

        if (!EconomyUtils.buyNoMessage(buyer, price)) {
            return PurchaseResult.PAYMENT_FAILED;
        }

        return PurchaseResult.SUCCESS;
    }

    public boolean recordAuthorClaim(UUID publishedId, Player buyer) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null || buyer == null) {
            return false;
        }
        if (!published.isAvailable() || !buyer.getUniqueId().equals(published.getAuthorUUID())) {
            return false;
        }
        if (!canClaimOwnMusic(published, buyer)) {
            return false;
        }
        published.addAuthorClaim(buyer.getUniqueId());
        savePublishedMusicSync(published);
        return true;
    }

    public void rollbackAuthorClaim(UUID publishedId, Player buyer) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null || buyer == null) {
            return;
        }
        published.removeAuthorClaim(buyer.getUniqueId());
        savePublishedMusicSync(published);
    }

    public boolean canClaimOwnMusic(PublishedMusic published, Player buyer) {
        if (published == null || buyer == null || !buyer.getUniqueId().equals(published.getAuthorUUID())) {
            return false;
        }
        MusicBoxConfig.PublishConfig publishConfig = MusicBox.getInstance().getConfigObject().getPublishConfig();
        if (!publishConfig.isAllowAuthorClaimOwnMusic()) {
            return false;
        }
        return !publishConfig.isAuthorClaimOwnMusicOnce() || !published.hasAuthorClaim(buyer.getUniqueId());
    }

    // paidPrice matches what purchaseMusic withdrew; accounting must use the same amount so a
    // concurrent price change by the author cannot skew the author's revenue vs the sale.
    public boolean recordSuccessfulPurchase(UUID publishedId, Player buyer, double paidPrice) {
        try {
            PublishedMusic published = publishedMusicCache.get(publishedId);
            if (published == null || buyer == null) {
                return false;
            }

            if (!published.isAvailable()) {
                return false;
            }

            if (published.getAuthorUUID().equals(buyer.getUniqueId())) {
                return false;
            }

            double taxRate = MusicBox.getInstance().getConfigObject().getPublishConfig().getTaxRate();
            double authorRevenue = paidPrice * (1 - taxRate);
            published.addSale();
            published.addRevenue(authorRevenue);
            // In-memory state is updated synchronously so the caller's rollback checks see it, but
            // the files go out async: this runs on the buyer's region thread and one of the two
            // writes serialises every note of the song.
            addPendingRevenue(published.getAuthorUUID(), authorRevenue);
            savePublishedMusic(published);
            return true;
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().warning("记录发布音乐购买失败: " + e.getMessage());
            rollbackRecordedPurchase(publishedId, paidPrice);
            return false;
        }
    }

    public void rollbackRecordedPurchase(UUID publishedId, double paidPrice) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return;
        }

        double taxRate = MusicBox.getInstance().getConfigObject().getPublishConfig().getTaxRate();
        double authorRevenue = paidPrice * (1 - taxRate);
        published.rollbackSale(authorRevenue);
        pendingRevenue.computeIfPresent(published.getAuthorUUID(), (uuid, amount) -> Math.max(0, amount - authorRevenue));
        savePublishedMusicSync(published);
        savePendingRevenue();
    }
    
    private void savePublishedMusicSync(PublishedMusic published) {
        invalidateAvailableCache();
        try {
            synchronized (this) {
                if (!publishedMusicCache.containsKey(published.getUniqueId())) {
                    return;
                }
                File authorFolder = getAuthorFolder(published.getAuthorUUID());
                if (!authorFolder.exists()) {
                    authorFolder.mkdirs();
                }
                File musicFile = getPublishedMusicFile(published);
                YamlConfiguration config = published.toConfig();
                config.save(musicFile);
            }
        } catch (IOException e) {
            MusicBox.getInstance().getLogger().log(Level.SEVERE,
                    "保存发布音乐失败: " + published.getName(), e);
        }
    }
    
    // Merges in memory now, writes the file off-thread: callers run on a region thread.
    private void addPendingRevenue(UUID authorUUID, double amount) {
        pendingRevenue.merge(authorUUID, amount, Double::sum);
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(this::savePendingRevenue);
    }

    public double claimRevenue(Player player) {
        UUID playerUUID = player.getUniqueId();
        // Atomically take the pending amount first: a rapid second claim then reads
        // null and pays nothing (no double-pay), and we never clobber revenue that a
        // concurrent sale merged in after a stale read.
        Double amount = pendingRevenue.remove(playerUUID);

        if (amount == null || amount <= 0) {
            if (amount != null) {
                savePendingRevenue();
            }
            return 0;
        }

        if (EconomyUtils.depositPlayer(player, amount)) {
            savePendingRevenue();
            return amount;
        }

        // Deposit failed — put the revenue back (merge in case a sale arrived meanwhile).
        pendingRevenue.merge(playerUUID, amount, Double::sum);
        return 0;
    }

    public double getPendingRevenue(UUID playerUUID) {
        return pendingRevenue.getOrDefault(playerUUID, 0.0);
    }

    public PublishedMusic getPublishedMusic(UUID id) {
        return publishedMusicCache.get(id);
    }

    public List<PublishedMusic> getPublishedByAuthor(UUID authorUUID) {
        return new ArrayList<>(authorMusicCache.getOrDefault(authorUUID, new ArrayList<>()));
    }

    // Available listings sorted by sales (desc), cached unmodifiable and rebuilt only when
    // listings change (publish/unpublish/delete/purchase), so the shop GUI does not re-filter
    // and re-sort the whole catalog on the main thread on every open / page refresh.
    private volatile List<PublishedMusic> sortedAvailableCache;

    public List<PublishedMusic> getAvailableSortedBySales() {
        List<PublishedMusic> cached = sortedAvailableCache;
        if (cached != null) {
            return cached;
        }
        cached = Collections.unmodifiableList(publishedMusicCache.values().stream()
                .filter(p -> p.isAvailable() && p.isApproved())
                .sorted(Comparator.comparingInt(PublishedMusic::getSalesCount).reversed())
                .collect(Collectors.toList()));
        sortedAvailableCache = cached;
        return cached;
    }

    private void invalidateAvailableCache() {
        sortedAvailableCache = null;
    }

    public List<PublishedMusic> getAvailablePublished() {
        return new ArrayList<>(getAvailableSortedBySales());
    }

    public List<PublishedMusic> searchPublished(String query) {
        String lowerQuery = query.toLowerCase();
        return getAvailableSortedBySales().stream()
                .filter(p -> p.getName().toLowerCase().contains(lowerQuery) ||
                        p.getAuthor().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    // Everything still waiting for a decision, oldest first, for the review command/GUI.
    public List<PublishedMusic> getPendingPublished() {
        return publishedMusicCache.values().stream()
                .filter(PublishedMusic::isPending)
                .sorted(Comparator.comparingLong(PublishedMusic::getPublishedAt))
                .collect(Collectors.toList());
    }

    public boolean approveMusic(UUID publishedId) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }
        published.setStatus(PublishedMusic.PublishedMusicStatus.APPROVED);
        // Availability stays the author's call: publishMusic already set it, and forcing it here
        // re-lists a submission the author withdrew while it sat in the review queue.
        savePublishedMusic(published);
        notifyAuthor(published, Lang.PUBLISH_APPROVED_NOTICE);
        return true;
    }

    public boolean rejectMusic(UUID publishedId) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }
        published.setStatus(PublishedMusic.PublishedMusicStatus.REJECTED);
        published.setAvailable(false);
        savePublishedMusic(published);
        notifyAuthor(published, Lang.PUBLISH_REJECTED_NOTICE);
        return true;
    }

    // Notifies the author when online; offline authors see the result next time they manage
    // the listing. Approval/rejection never blocks on player presence.
    private void notifyAuthor(PublishedMusic published, Lang message) {
        if (published == null) {
            return;
        }
        Player author = Bukkit.getPlayer(published.getAuthorUUID());
        if (author != null && author.isOnline()) {
            MessageUtils.send(author, message, "{name}", published.getName());
        }
    }

    private void savePublishedMusic(PublishedMusic published) {
        invalidateAvailableCache();
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                synchronized (this) {
                    if (!publishedMusicCache.containsKey(published.getUniqueId())) {
                        return;
                    }
                    File authorFolder = getAuthorFolder(published.getAuthorUUID());
                    if (!authorFolder.exists()) {
                        authorFolder.mkdirs();
                    }
                    File musicFile = getPublishedMusicFile(published);
                    YamlConfiguration config = published.toConfig();
                    config.save(musicFile);
                }
            } catch (IOException e) {
                MusicBox.getInstance().getLogger().log(Level.SEVERE,
                        "保存发布音乐失败: " + published.getName(), e);
            }
        });
    }

    public boolean deletePublishedMusic(UUID publishedId, UUID requesterUUID) {
        PublishedMusic published = publishedMusicCache.get(publishedId);
        if (published == null) {
            return false;
        }

        if (!published.getAuthorUUID().equals(requesterUUID)) {
            return false;
        }

        try {
            synchronized (this) {
                File musicFile = getPublishedMusicFile(published);
                Files.deleteIfExists(musicFile.toPath());

                publishedMusicCache.remove(publishedId);
                invalidateAvailableCache();

                List<PublishedMusic> authorList = authorMusicCache.get(published.getAuthorUUID());
                if (authorList != null) {
                    authorList.removeIf(p -> p.getUniqueId().equals(publishedId));
                    if (authorList.isEmpty()) {
                        authorMusicCache.remove(published.getAuthorUUID());
                    }
                }

                File authorFolder = getAuthorFolder(published.getAuthorUUID());
                File[] remainingFiles = authorFolder.listFiles();
                if (remainingFiles != null && remainingFiles.length == 0) {
                    Files.deleteIfExists(authorFolder.toPath());
                }
            }
            return true;
        } catch (IOException | SecurityException e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING,
                    "Failed to delete published music: " + published.getName(), e);
            return false;
        }
    }

    private File getAuthorFolder(UUID authorUUID) {
        return new File(publishedFolder, authorUUID.toString());
    }

    private File getPublishedMusicFile(PublishedMusic published) {
        return new File(getAuthorFolder(published.getAuthorUUID()), published.getUniqueId().toString() + ".yml");
    }

    public enum PublishResult {
        SUCCESS,
        FAILED,
        INVALID_PRICE,
        PRICE_OUT_OF_RANGE
    }

    public enum PurchaseResult {
        SUCCESS,
        AUTHOR_CLAIM,
        NOT_FOUND,
        NOT_AVAILABLE,
        OWN_MUSIC,
        NO_MONEY,
        PAYMENT_FAILED
    }
    
    public void shutdown() {
        savePendingRevenue();
        publishedMusicCache.clear();
        authorMusicCache.clear();
        pendingRevenue.clear();
        synchronized (LOCK) {
            if (instance == this) {
                instance = null;
            }
        }
    }
}
