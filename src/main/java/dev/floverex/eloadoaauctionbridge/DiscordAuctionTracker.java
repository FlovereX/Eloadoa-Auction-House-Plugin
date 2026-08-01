package dev.floverex.eloadoaauctionbridge;

import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Sends AuctionHouse listings straight to a Discord channel through DiscordSRV's JDA
 * instance, then edits the embed as the auction's state changes (sold, expired, or
 * cancelled/removed) and optionally deletes it afterward. This is an alternative to
 * DiscordSRV's alerts.yml and is only active while "discord.enabled" is true in
 * config.yml. Tracked auctions are persisted to tracked-auctions.yml so tracking
 * survives a server restart.
 */
final class DiscordAuctionTracker {

    private enum Status {
        ACTIVE,
        SOLD,
        EXPIRED,
        REMOVED
    }

    private static final class TrackedAuction {
        String sellerName;
        String itemName;
        int amount;
        double price;
        boolean bidAuction;
        long messageId;
        long endEpochSeconds;
        Status status = Status.ACTIVE;
        long finalizedAtEpochMillis;
    }

    private final EloadoaAuctionBridge plugin;
    private final Map<UUID, TrackedAuction> tracked = new HashMap<>();
    private final File storageFile;
    private String channelId;
    private long deleteAfterMillis;

    DiscordAuctionTracker(EloadoaAuctionBridge plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "tracked-auctions.yml");
    }

    void start() {
        channelId = plugin.getConfig().getString("discord.channel-id", "0");
        deleteAfterMillis = Math.max(0, plugin.getConfig().getInt("discord.delete-after-seconds", 300)) * 1000L;
        int intervalSeconds = Math.max(1, plugin.getConfig().getInt("discord.check-interval-seconds", 10));

        load();

        long intervalTicks = intervalSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::pollAll, intervalTicks, intervalTicks);
    }

    /**
     * Posts a new embed for a confirmed listing and starts tracking it.
     */
    void trackNewListing(
            UUID auctionId,
            String sellerName,
            String itemName,
            int amount,
            double price,
            boolean bidAuction,
            long timeLeftSeconds
    ) {
        TextChannel channel = resolveChannel();
        if (channel == null) {
            return;
        }

        TrackedAuction auction = new TrackedAuction();
        auction.sellerName = sellerName;
        auction.itemName = itemName;
        auction.amount = amount;
        auction.price = price;
        auction.bidAuction = bidAuction;
        auction.endEpochSeconds = Instant.now().getEpochSecond() + Math.max(0, timeLeftSeconds);

        MessageEmbed embed = buildActiveEmbed(auction);

        channel.sendMessageEmbeds(embed).queue(
                message -> {
                    auction.messageId = message.getIdLong();
                    synchronized (tracked) {
                        tracked.put(auctionId, auction);
                    }
                    save();
                },
                throwable -> plugin.getLogger().log(
                        Level.WARNING, "Failed to send Discord embed for auction " + auctionId, throwable)
        );
    }

    private void pollAll() {
        Map<UUID, TrackedAuction> snapshot;
        synchronized (tracked) {
            snapshot = new HashMap<>(tracked);
        }

        for (Map.Entry<UUID, TrackedAuction> entry : snapshot.entrySet()) {
            pollOne(entry.getKey(), entry.getValue());
        }
    }

    private void pollOne(UUID auctionId, TrackedAuction auction) {
        if (auction.status != Status.ACTIVE) {
            if (deleteAfterMillis > 0
                    && System.currentTimeMillis() - auction.finalizedAtEpochMillis >= deleteAfterMillis) {
                deleteMessage(auction);
                synchronized (tracked) {
                    tracked.remove(auctionId);
                }
                save();
            }
            return;
        }

        try {
            Object note = plugin.getNoteById(auctionId);

            if (note == null) {
                finalizeAuction(auctionId, auction, Status.REMOVED, null);
                return;
            }

            boolean sold = plugin.isNoteSold(note);
            boolean stillOnAuction = plugin.isNoteTheoreticallyOnAuction(note);
            boolean expired = plugin.isNoteExpired(note);

            if (sold && !stillOnAuction) {
                auction.price = plugin.getNotePrice(note);
                finalizeAuction(auctionId, auction, Status.SOLD, plugin.getNoteBuyerName(note));
                return;
            }

            if (!stillOnAuction || expired) {
                finalizeAuction(auctionId, auction, Status.EXPIRED, null);
                return;
            }

            long newEnd = Instant.now().getEpochSecond() + Math.max(0, plugin.getNoteTimeLeft(note));
            if (Math.abs(newEnd - auction.endEpochSeconds) >= 30) {
                // A late bid extended (or otherwise changed) the auction's end time.
                auction.endEpochSeconds = newEnd;
                editMessage(auction, buildActiveEmbed(auction));
                save();
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to poll tracked auction " + auctionId, exception);
        }
    }

    private void finalizeAuction(UUID auctionId, TrackedAuction auction, Status status, String buyerName) {
        auction.status = status;
        auction.finalizedAtEpochMillis = System.currentTimeMillis();
        editMessage(auction, buildFinalEmbed(auction, status, buyerName));
        save();
    }

    private MessageEmbed buildActiveEmbed(TrackedAuction auction) {
        return new EmbedBuilder()
                .setTitle("New Auction Listing")
                .setColor(0xF2A900)
                .addField("Seller", auction.sellerName, true)
                .addField("Item", auction.itemName, true)
                .addField("Amount", String.valueOf(auction.amount), true)
                .addField("Price", formatPrice(auction.price), true)
                .addField("Type", auction.bidAuction ? "Bid auction" : "Buy now", true)
                .addField("Ends", "<t:" + auction.endEpochSeconds + ":R>", true)
                .setFooter("Use /ah in-game to view the listing")
                .build();
    }

    private MessageEmbed buildFinalEmbed(TrackedAuction auction, Status status, String buyerName) {
        EmbedBuilder builder = new EmbedBuilder()
                .addField("Seller", auction.sellerName, true)
                .addField("Item", auction.itemName, true)
                .addField("Amount", String.valueOf(auction.amount), true)
                .addField("Price", formatPrice(auction.price), true)
                .addField("Type", auction.bidAuction ? "Bid auction" : "Buy now", true);

        switch (status) {
            case SOLD -> {
                builder.setTitle("Sold").setColor(0x43B581);
                if (buyerName != null && !buyerName.isBlank()) {
                    builder.addField("Buyer", buyerName, true);
                }
            }
            case EXPIRED -> builder.setTitle("Expired").setColor(0x99AAB5);
            case REMOVED -> builder.setTitle("Removed").setColor(0xED4245);
            default -> builder.setTitle("Auction Listing");
        }

        return builder.build();
    }

    private String formatPrice(double price) {
        return String.format(Locale.US, "$%,.2f", price);
    }

    private void editMessage(TrackedAuction auction, MessageEmbed embed) {
        TextChannel channel = resolveChannel();
        if (channel == null || auction.messageId == 0) {
            return;
        }
        channel.editMessageEmbedsById(auction.messageId, embed).queue(
                unused -> { },
                throwable -> plugin.getLogger().log(
                        Level.WARNING, "Failed to edit Discord message " + auction.messageId, throwable)
        );
    }

    private void deleteMessage(TrackedAuction auction) {
        TextChannel channel = resolveChannel();
        if (channel == null || auction.messageId == 0) {
            return;
        }
        channel.deleteMessageById(auction.messageId).queue(
                unused -> { },
                throwable -> plugin.getLogger().log(
                        Level.WARNING, "Failed to delete Discord message " + auction.messageId, throwable)
        );
    }

    private TextChannel resolveChannel() {
        TextChannel channel = DiscordUtil.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning(
                    "Could not resolve Discord channel " + channelId
                            + ". Is DiscordSRV loaded and is discord.channel-id correct?");
        }
        return channel;
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        Map<UUID, TrackedAuction> snapshot;
        synchronized (tracked) {
            snapshot = new HashMap<>(tracked);
        }

        for (Map.Entry<UUID, TrackedAuction> entry : snapshot.entrySet()) {
            String path = "auctions." + entry.getKey();
            TrackedAuction auction = entry.getValue();
            yaml.set(path + ".seller-name", auction.sellerName);
            yaml.set(path + ".item-name", auction.itemName);
            yaml.set(path + ".amount", auction.amount);
            yaml.set(path + ".price", auction.price);
            yaml.set(path + ".bid-auction", auction.bidAuction);
            yaml.set(path + ".message-id", auction.messageId);
            yaml.set(path + ".end-epoch-seconds", auction.endEpochSeconds);
            yaml.set(path + ".status", auction.status.name());
            yaml.set(path + ".finalized-at", auction.finalizedAtEpochMillis);
        }

        try {
            yaml.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to save tracked-auctions.yml", exception);
        }
    }

    private void load() {
        if (!storageFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = yaml.getConfigurationSection("auctions");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID auctionId = UUID.fromString(key);
                String path = "auctions." + key;

                TrackedAuction auction = new TrackedAuction();
                auction.sellerName = yaml.getString(path + ".seller-name", "Unknown");
                auction.itemName = yaml.getString(path + ".item-name", "Item");
                auction.amount = yaml.getInt(path + ".amount", 1);
                auction.price = yaml.getDouble(path + ".price", 0);
                auction.bidAuction = yaml.getBoolean(path + ".bid-auction", false);
                auction.messageId = yaml.getLong(path + ".message-id");
                auction.endEpochSeconds = yaml.getLong(path + ".end-epoch-seconds");
                auction.status = Status.valueOf(yaml.getString(path + ".status", "ACTIVE"));
                auction.finalizedAtEpochMillis = yaml.getLong(path + ".finalized-at");

                tracked.put(auctionId, auction);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid tracked auction entry: " + key);
            }
        }
    }

    void shutdown() {
        save();
    }
}
