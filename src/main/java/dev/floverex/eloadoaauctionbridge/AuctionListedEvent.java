package dev.floverex.eloadoaauctionbridge;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired only after the bridge confirms that AuctionHouse created the listing
 * and that it has finished its setup/waiting period.
 */
public final class AuctionListedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String sellerName;
    private final UUID sellerUuid;
    private final UUID auctionId;
    private final ItemStack item;
    private final String itemName;
    private final double price;
    private final boolean bidAuction;

    public AuctionListedEvent(
            @NotNull String sellerName,
            @NotNull UUID sellerUuid,
            @NotNull UUID auctionId,
            @NotNull ItemStack item,
            @NotNull String itemName,
            double price,
            boolean bidAuction
    ) {
        this.sellerName = sellerName;
        this.sellerUuid = sellerUuid;
        this.auctionId = auctionId;
        this.item = item.clone();
        this.itemName = itemName;
        this.price = price;
        this.bidAuction = bidAuction;
    }

    public @NotNull String getSellerName() {
        return sellerName;
    }

    public @NotNull UUID getSellerUuid() {
        return sellerUuid;
    }

    public @NotNull UUID getAuctionId() {
        return auctionId;
    }

    public @NotNull ItemStack getItem() {
        return item.clone();
    }

    public @NotNull String getItemName() {
        return itemName;
    }

    public @NotNull String getMaterial() {
        return item.getType().name();
    }

    public int getAmount() {
        return item.getAmount();
    }

    public double getPrice() {
        return price;
    }

    public boolean isBidAuction() {
        return bidAuction;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
