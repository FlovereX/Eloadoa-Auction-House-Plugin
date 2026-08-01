# EloadoaAuctionBridge

This Paper plugin watches ElaineQheart's `AuctionHouse` plugin and fires a
custom Bukkit event only after a listing was actually created and finished its
configured setup/waiting period.

It does not announce rejected commands, including:
- invalid prices
- empty hand
- blacklisted items
- maximum listing limit
- disabled auction type

## Build with GitHub

1. Create a new GitHub repository.
2. Upload every file and folder from this project.
3. Open the repository's **Actions** tab.
4. Run **Build plugin**, or push a commit to `main`.
5. Open the completed workflow and download the `EloadoaAuctionBridge` artifact.
6. Extract `EloadoaAuctionBridge-1.0.0.jar`.

## Install

1. Stop the Minecraft server.
2. Put `EloadoaAuctionBridge-1.0.0.jar` in `plugins/`.
3. Keep `AuctionHouse` and `DiscordSRV` installed.
4. Add the `auctions` channel mapping to DiscordSRV's existing `Channels:` line.
5. Add the supplied alert to the existing `Alerts:` section.
6. Fully restart the server.

Expected load order:
1. AuctionHouse
2. EloadoaAuctionBridge
3. DiscordSRV

The supplied `plugin.yml` requests this order so DiscordSRV can find the custom
event class while loading alerts.

## Test

Hold an item and run:

    /ah sell 4000

Wait for `auction-setup-time` plus about one second. The embed should appear in
the mapped Discord auctions channel.

Rejected test:

    /ah sell invalid

No Discord alert should be sent.

## Compatibility note

This bridge uses reflection against these internal AuctionHouse classes:

- `me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage`
- `me.elaineqheart.auctionHouse.data.ram.ItemNote`

It is designed for the ElaineQheart AuctionHouse build whose command creates
an `ItemNote` through `ItemNoteStorage.createNote(...)`. A future AuctionHouse
update could rename those internal classes or methods. Test the bridge whenever
you update AuctionHouse.
