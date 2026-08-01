package dev.floverex.eloadoaauctionbridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class EloadoaAuctionBridge extends JavaPlugin implements Listener {

    private Method getMySortedDateCreated;
    private Method getNote;
    private Method getNoteId;
    private Method getItem;
    private Method getItemName;
    private Method getPrice;
    private Method isBidAuction;
    private Method isTheoreticallyOnAuction;
    private Method isExpired;
    private Method isOnWaitingList;

    private Plugin auctionHouse;
    private Set<String> commandLabels;
    private Set<String> listingSubcommands;
    private int extraConfirmSeconds;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        commandLabels = getConfig().getStringList("command-labels").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        listingSubcommands = getConfig().getStringList("listing-subcommands").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        extraConfirmSeconds = Math.max(0, getConfig().getInt("extra-confirm-seconds", 1));
        debug = getConfig().getBoolean("debug", false);

        auctionHouse = Bukkit.getPluginManager().getPlugin("AuctionHouse");
        if (auctionHouse == null || !auctionHouse.isEnabled()) {
            getLogger().severe("AuctionHouse is missing or disabled. Disabling bridge.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            loadAuctionHouseReflection();
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.SEVERE,
                    "Could not connect to ElaineQheart AuctionHouse internals. "
                            + "The AuctionHouse version may have changed.", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Enabled. Successful AuctionHouse listings will fire AuctionListedEvent.");
    }

    private void loadAuctionHouseReflection() throws ReflectiveOperationException {
        ClassLoader loader = auctionHouse.getClass().getClassLoader();

        Class<?> storageClass = Class.forName(
                "me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage",
                true,
                loader
        );
        Class<?> noteClass = Class.forName(
                "me.elaineqheart.auctionHouse.data.ram.ItemNote",
                true,
                loader
        );

        getMySortedDateCreated = storageClass.getMethod("getMySortedDateCreated", UUID.class);
        getNote = storageClass.getMethod("getNote", UUID.class);

        getNoteId = noteClass.getMethod("getNoteID");
        getItem = noteClass.getMethod("getItem");
        getItemName = noteClass.getMethod("getItemName");
        getPrice = noteClass.getMethod("getPrice");
        isBidAuction = noteClass.getMethod("isBIDAuction");
        isTheoreticallyOnAuction = noteClass.getMethod("isTheoreticallyOnAuction");
        isExpired = noteClass.getMethod("isExpired");
        isOnWaitingList = noteClass.getMethod("isOnWaitingList");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2) {
            return;
        }

        String[] parts = raw.substring(1).trim().split("\\s+");
        if (parts.length < 3) {
            return;
        }

        String label = stripNamespace(parts[0]).toLowerCase(Locale.ROOT);
        String subcommand = parts[1].toLowerCase(Locale.ROOT);

        if (!commandLabels.contains(label) || !listingSubcommands.contains(subcommand)) {
            return;
        }

        Player player = event.getPlayer();
        Set<UUID> before = getListingIds(player.getUniqueId());

        // PlayerCommandPreprocessEvent runs before the command executor. Checking
        // one tick later lets AuctionHouse finish all validation and createNote().
        Bukkit.getScheduler().runTask(this, () -> findCreatedListing(player, before));
    }

    private void findCreatedListing(Player player, Set<UUID> before) {
        try {
            List<Object> notes = getListings(player.getUniqueId());
            Object newNote = null;

            for (Object note : notes) {
                UUID noteId = (UUID) getNoteId.invoke(note);
                if (!before.contains(noteId)) {
                    newNote = note;
                    break;
                }
            }

            // Invalid price, blacklisted item, no item in hand, max listings, etc.
            // create no ItemNote, so nothing is announced.
            if (newNote == null) {
                debug("No new listing was created for " + player.getName());
                return;
            }

            UUID noteId = (UUID) getNoteId.invoke(newNote);
            int setupSeconds = Math.max(0,
                    auctionHouse.getConfig().getInt("auction-setup-time", 0));
            long delayTicks = Math.max(1L, (setupSeconds + extraConfirmSeconds) * 20L);

            debug("Detected listing " + noteId + "; confirming in " + delayTicks + " ticks.");

            String sellerName = player.getName();
            UUID sellerUuid = player.getUniqueId();

            Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> confirmAndFire(sellerName, sellerUuid, noteId),
                    delayTicks
            );
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.WARNING, "Failed to inspect a new AuctionHouse listing.", exception);
        }
    }

    private void confirmAndFire(String sellerName, UUID sellerUuid, UUID noteId) {
        try {
            Object note = getNote.invoke(null, noteId);
            if (note == null) {
                debug("Listing " + noteId + " was removed before confirmation.");
                return;
            }

            boolean active = (boolean) isTheoreticallyOnAuction.invoke(note);
            boolean expired = (boolean) isExpired.invoke(note);
            boolean waiting = (boolean) isOnWaitingList.invoke(note);

            if (!active || expired || waiting) {
                debug("Listing " + noteId + " is not active after confirmation.");
                return;
            }

            ItemStack item = ((ItemStack) getItem.invoke(note)).clone();
            String itemName = cleanItemName((String) getItemName.invoke(note), item);
            double price = ((Number) getPrice.invoke(note)).doubleValue();
            boolean bidAuction = (boolean) isBidAuction.invoke(note);

            Bukkit.getPluginManager().callEvent(new AuctionListedEvent(
                    sellerName,
                    sellerUuid,
                    noteId,
                    item,
                    itemName,
                    price,
                    bidAuction
            ));

            debug("Fired AuctionListedEvent for " + noteId);
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.WARNING, "Failed to confirm AuctionHouse listing " + noteId, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> getListings(UUID playerUuid) {
        try {
            Object result = getMySortedDateCreated.invoke(null, playerUuid);
            if (result instanceof List<?> list) {
                return new ArrayList<>((List<Object>) list);
            }
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.WARNING, "Failed to read AuctionHouse listings.", exception);
        }
        return List.of();
    }

    private Set<UUID> getListingIds(UUID playerUuid) {
        Set<UUID> ids = new HashSet<>();
        for (Object note : getListings(playerUuid)) {
            try {
                ids.add((UUID) getNoteId.invoke(note));
            } catch (ReflectiveOperationException exception) {
                getLogger().log(Level.WARNING, "Failed to read an AuctionHouse listing ID.", exception);
            }
        }
        return ids;
    }

    private String cleanItemName(String auctionName, ItemStack item) {
        String clean = ChatColor.stripColor(auctionName == null ? "" : auctionName);
        if (clean != null && !clean.isBlank()) {
            return clean;
        }

        String material = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder title = new StringBuilder();
        for (String word : material.split(" ")) {
            if (!word.isEmpty()) {
                if (!title.isEmpty()) {
                    title.append(' ');
                }
                title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return title.toString();
    }

    private String stripNamespace(String label) {
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    private void debug(String message) {
        if (debug) {
            getLogger().info("[Debug] " + message);
        }
    }
}
