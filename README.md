# Eloadoa Auction Bridge

A small Paper plugin that confirms a listing was successfully added to ElaineQheart's **AuctionHouse** before exposing it to **DiscordSRV Alerts**.

This prevents Discord announcements for rejected listing attempts such as:

- invalid prices
- an empty hand
- blacklisted items
- a player's maximum listing limit
- disabled auction types

> EloadoaAuctionBridge is a compatibility bridge. It does not replace AuctionHouse or DiscordSRV.

## Features

- Watches configured AuctionHouse listing commands, including `/ah sell` and `/ah bid`
- Compares the seller's AuctionHouse records before and after the command runs
- Waits for AuctionHouse's configured `auction-setup-time`
- Confirms the new record still exists and is active
- Fires `AuctionListedEvent` only after confirmation
- Exposes seller, item, amount, price, auction type, and auction ID to DiscordSRV
- Supports extra command aliases through `config.yml`
- Includes optional debug logging

## Requirements

- Java 21
- Paper 1.21.x
- A compatible ElaineQheart AuctionHouse build
- DiscordSRV

The project currently compiles against Paper `1.21.11-R0.1-SNAPSHOT`.

## Installation

1. Download the latest `EloadoaAuctionBridge-<version>.jar` from the repository's **Releases** page.
2. Stop the Minecraft server.
3. Confirm `AuctionHouse` and `DiscordSRV` are installed.
4. Place the bridge JAR in the server's `plugins/` directory.
5. Start the server once so the bridge can create its configuration.
6. Configure the DiscordSRV channel and alert shown below.
7. Fully restart the server.

The requested plugin load relationship is:

1. AuctionHouse
2. EloadoaAuctionBridge
3. DiscordSRV

`plugin.yml` declares AuctionHouse as a required dependency and asks to load before DiscordSRV so DiscordSRV can resolve the custom event class while loading alerts.

## DiscordSRV setup

### 1. Add the auctions channel

Open:

```text
plugins/DiscordSRV/config.yml
```

Add an `auctions` mapping to the existing `Channels:` map. Do not remove your existing channels.

```yaml
Channels: {"global": "SERVER_CHAT_CHANNEL_ID", "auctions": "AUCTIONS_CHANNEL_ID"}
```

Replace `AUCTIONS_CHANNEL_ID` with the Discord channel ID that should receive auction alerts.

### 2. Add the alert

Open:

```text
plugins/DiscordSRV/alerts.yml
```

Append this entry beneath the existing `Alerts:` section. Use spaces, not tabs.

```yaml
  - Trigger: dev.floverex.eloadoaauctionbridge.AuctionListedEvent
    Channel: auctions
    Embed:
      Enabled: true
      Color: "#F2A900"
      Title:
        Text: "New Auction Listing"
      Description: |-
        **Seller:** `${#event.getSellerName()}`
        **Item:** `${#event.getItemName()}`
        **Amount:** `${#event.getAmount()}`
        **Price:** `$${#event.getPrice()}`
        **Type:** `${#event.isBidAuction() ? "Bid auction" : "Buy now"}`
        Use `/ah` in-game to view the listing.
      Footer:
        Text: "Auction ID: ${#event.getAuctionId()}"
      Timestamp: true
```

Do not create a second `Alerts:` heading when one already exists.

## Bridge configuration

The bridge creates:

```text
plugins/EloadoaAuctionBridge/config.yml
```

Default configuration:

```yaml
# Commands that open your AuctionHouse plugin.
command-labels:
  - ah
  - auctionhouse

# Listing subcommands to announce.
listing-subcommands:
  - sell
  - bid

# Added after AuctionHouse's own auction-setup-time.
extra-confirm-seconds: 1

# Prints listing detection and confirmation details to console.
debug: false
```

Add any custom AuctionHouse command alias under `command-labels`.

## How it works

When a player runs a configured listing command, the bridge:

1. Records the IDs of the player's current AuctionHouse listings.
2. Allows AuctionHouse to process and validate the command.
3. Checks the player's listings again one server tick later.
4. Stops if no new AuctionHouse record was created.
5. Reads AuctionHouse's `auction-setup-time`.
6. Waits for that period plus `extra-confirm-seconds`.
7. Confirms the record exists, is active, is not expired, and is no longer waiting.
8. Fires `AuctionListedEvent`.
9. DiscordSRV receives the event and sends the configured alert.

## Testing

### Successful listing

Hold an allowed item and run:

```text
/ah sell 4000
```

Wait for AuctionHouse's setup time plus approximately one extra second. The embed should appear in the configured Discord channel.

### Rejected listing

Run:

```text
/ah sell invalid
```

No Discord alert should be sent because AuctionHouse never created a listing record.

## Event API

Other plugins can listen for:

```java
dev.floverex.eloadoaauctionbridge.AuctionListedEvent
```

Available values include:

```java
event.getSellerName();
event.getSellerUuid();
event.getAuctionId();
event.getItem();
event.getItemName();
event.getMaterial();
event.getAmount();
event.getPrice();
event.isBidAuction();
```

The returned `ItemStack` is cloned before it is exposed.

## Building locally

Requirements:

- JDK 21
- Maven

Build the plugin with:

```bash
mvn clean package
```

The compiled JAR is placed in:

```text
target/
```

## Creating a GitHub Release

The included workflow builds normal commits and publishes tagged builds to the repository's **Releases** page.

Before publishing a new version, update all three version values:

```text
pom.xml                         <version>
pom.xml                         <finalName>
src/main/resources/plugin.yml   version
```

For example, the Maven version and plugin version should be `1.0.1`, the final JAR name should end in `1.0.1`, and the release tag should be `v1.0.1`.

### Option A: Release from the Actions page

1. Commit and push the version changes to `main`.
2. Open the repository on GitHub.
3. Select **Actions**.
4. Select **Build and Release Plugin**.
5. Select **Run workflow**.
6. Enter a version tag such as `v1.0.1`.
7. Run the workflow.

The workflow creates the Git tag, creates a GitHub Release, generates release notes, and attaches the JAR and its SHA-256 checksum.

Leave the version field blank to perform a build without creating a release.

### Option B: Release by pushing a tag

After committing the version changes:

```bash
git tag v1.0.1
git push origin v1.0.1
```

A pushed `v*` tag starts the workflow and creates the matching GitHub Release automatically.

The release tag must match the Maven project version. For example:

```text
pom.xml version: 1.0.1
release tag:      v1.0.1
```

## Development builds

Pushes to `main`, pull requests, and manually triggered builds without a version still create a temporary Actions artifact. Stable server downloads should come from **Releases**.

## Compatibility warning

This bridge uses reflection against AuctionHouse internals, including:

```text
me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage
me.elaineqheart.auctionHouse.data.ram.ItemNote
```

A future AuctionHouse update may rename or change those classes and methods. Test the bridge after every AuctionHouse update.

The bridge currently detects listings created through player commands. Listings created directly by another plugin, the console, or an AuctionHouse API are not detected by the command listener.

## Troubleshooting

### The bridge disables itself

Look for:

```text
AuctionHouse is missing or disabled
```

or:

```text
Could not connect to ElaineQheart AuctionHouse internals
```

Confirm AuctionHouse loaded successfully and that its version is compatible with the bridge.

### A valid auction does not alert Discord

Check that:

- AuctionHouse created the listing successfully
- DiscordSRV loaded successfully
- `auctions` exists in DiscordSRV's `Channels:` map
- the channel ID is correct
- the alert is beneath the existing `Alerts:` section
- YAML uses spaces rather than tabs
- the server was fully restarted
- the command label and subcommand are listed in the bridge configuration

Set `debug: true` and restart the server to print detection details.

### A rejected auction does not alert Discord

That is expected. The bridge only fires the event after confirming a successful, active listing.

### The release workflow reports a version mismatch

Make the release tag match the Maven version:

```text
pom.xml: 1.0.1
tag:     v1.0.1
```

Also update `src/main/resources/plugin.yml` to the same version before rebuilding.

## License

This project is licensed under the MIT License.
