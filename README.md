# BZgraph

Client-side Fabric mod for **Minecraft 26.2** that shows an in-game price-history graph for Hypixel SkyBlock **bazaar** items.

> **Data: [SkyCofl (sky.coflnet.com)](https://sky.coflnet.com)** — all price history comes from the SkyCofl API.
> A short clickable "SkyCofl" credit is shown on every graph screen, as required by the data provider's terms; the
> **?** box on each graph lists all data sources.

## Features

- **Hover + key**: hover an item in any container GUI (inventory, bazaar menu, chests) and press **G** to open its graph.
- **Favourites**: click ☆ next to an item's name on its graph (or press `S`) to make it a favourite. **G** with no
  screen open (or with nothing hovered in an inventory/chest), or `/bzgraph favorites`, shows all favourites as a grid
  of mini charts (three per row); hover a chart for the price at that point, click one for its full graph, ✕ on a chart removes it. The grid also has
  **Search items** (`F`), **Last: …** (reopens the graph you looked at last) and the charts' range.
  - **Your own categories**: **+ Category** makes one (e.g. "Mining", "Flips"); drag charts between and within
    categories to sort them (a yellow bar shows where a chart will land). Right-click a chart to open it, move it to a
    category (one "Move to" submenu) or remove it; right-click a category to rename it, move it up/down or delete it (its charts move to
    "Uncategorized"). Click a category's name to fold it.
- **Reopen**: `/bzgraph` reopens the graph you looked at last (same item, history and compared items), or opens an
  item search the first time.
- **Search on the graph**: the search box in the graph's title row (click it or press `F`) finds bazaar items by
  name or ID; Up/Down and Enter or a click switch the graph to that item, Esc clears it.
  With no GUI open, **G** graphs the item in your main hand.
- **Recipe viewers**: search for an item, hover it and press **G** to graph it.
  - **Reliable Recipe Viewer (RRV)**: works in RRV's item list, bookmarks and craftables (BZgraph registers its key
    through RRV's overlay keybind API) and on RRV's recipe screen (ingredients, results and the workstation icon).
  - **Roughly Enough Items (REI)**: works in REI's item list, favorites and recipe screens.
  Both are optional; BZgraph talks to them through reflection and simply skips this when they aren't installed. G is ignored while a text box (REI search, anvil, creative search) is
  focused.
- **Command**: `/bzgraph` reopens the last graph (or the item search); `/bzgraph <ITEM_ID>` opens a graph directly, with tab-completion of all bazaar product IDs (e.g. `/bzgraph ENCHANTED_DIAMOND`).
- **Graph screen** (a centered panel, about three quarters of the screen)
  - Buy and sell price lines, each toggleable (Buy / Sell buttons; filled box = on, hollow = off). **Buy** is the
    insta-buy price (lowest sell offer), **Sell** the insta-sell price (highest buy order); hover the labels to see this.
  - The **?** button (or `H`) explains every line, marker and shortcut; it opens by itself the first time.
  - Time ranges **1D / 1W / 1M / 1Y / ALL** (buttons or keys `1`–`5`; the selected one is outlined). `R` forces a refresh.
  - Each range is drawn at one level of detail: SkyCofl's fine recent samples are merged into the same bucket size as
    the older ones, so the right edge doesn't turn into a scribble.
  - **‹ ›** arrows (or Left/Right arrow keys, or mouse back/forward buttons) move through the items you've opened in
    this session.
  - `Esc` (or `G`) closes BZgraph entirely and returns to the screen you opened it from.
  - Hover crosshair with date, buy, sell, spread, volume, and the mayor at that time.
  - A faint band behind each line shows the lowest–highest price within each sample (a whole day on 1M/1Y/ALL),
    since a daily line can't show moves within the day; your trade markers fall inside it. With both lines shown,
    hovering near one shows only its band; single outlier trades are clipped so they don't stretch it. Toggle with
    the **Band** button or `B`.
  - Y-axis with compact coin formatting (`950`, `1.2k`, `3.4M`, `1.5B`); time axis ticks at round, evenly spaced
    times (every 3 hours, every 4 days, every month...).
  - Header: current buy/sell in large text with their change over the range, then the spread and the range's
    low/high. Hover the title for the internal item ID.
  - "SkyCofl · updated 2 min ago", loading spinner, "Refreshing…" badge, and clear errors: prices can't be loaded
    (press R to retry), rate limited (auto-retry), not a bazaar item, no data.
- **Mayor bar** under the graph: who was mayor (and minister) at each point, coloured by type (neighbouring terms of
  the same type are shaded apart; terms too thin to label can still be hovered). Labels show "Mayor · Minister" when
  there is room; hover for perks. The graph's hover tooltip names the mayor and minister at that time too.
  Toggle with `M`.
- **Mini chart in the bazaar**: on an item's page in Hypixel's bazaar, a small chart (buy/sell lines, band,
  current prices and change over the range; hover it for the price at that point) appears left of the window. Click it for the full graph; right-click to change
  its range. Turn it off with `"miniChart": false`.
- **Your trades on the graph**: ▲ where you bought and ▼ where you sold, at the price you got (the same markers on the
  purse graph; neutral white, so a sale doesn't read as a loss). Hover for amount, price, coins, time and how the
  price has moved since. Nearby trades merge into one marker. **Trades** button. See [Your trades](#your-trades).
- **Compare items**: **+ Compare** (or `C`) on a graph adds up to 3 more bazaar items (search by name or ID), or
  `/bzgraph compare ENCHANTED_DIAMOND DIAMOND ...`. Each item gets its own colour (colour-blind friendly) and one
  line: pick buy or sell price with the Buy / Sell buttons, or turn on **Both lines** (sell lines faded). The
  **Show:** button switches the Y axis between:
  - **% change** (default): every line starts at 0% at the left edge, so a 5-coin and a 5M item can be compared;
  - **Price**: actual prices on one axis;
  - **Ratio** (two items): first ÷ second, e.g. Enchanted Diamond ÷ Diamond for craft flips.
  The header says which price is compared and shows each item's change over the range; click any item's ✕ to remove
  it (removing the first item makes the next one the main item). Bands and trade markers are hidden while comparing.
- **Purse graph**: `/bzgraph purse` or the **Purse** button on a graph. BZgraph reads the `Purse:` line of the
  SkyBlock sidebar once a second, so every change counts (bazaar, auction house, NPCs, mobs, ...); bank coins
  aren't in the sidebar and aren't included. Changes of 1,000+ coins (or 0.1% of the purse) are saved right away,
  smaller ones at most once a minute. Kept per account and SkyBlock profile (from the "You are playing on profile"
  message or the tab list; the profile button switches), in `config/bzgraph/purse.json`; points older than two
  weeks are thinned to each hour's first/lowest/highest/last value. Your bazaar trades of all items are marked on
  it, and the header shows coins in/out from the bazaar over the range. History starts when you install this
  version. Turn off with `"trackPurse": false`.
- Only active on Hypixel (server address contains `hypixel.net`) — configurable.

## Keybind

| Action                 | Default | Where to change                             |
|------------------------|---------|---------------------------------------------|
| Open bazaar price graph| `G`     | Options → Controls → Key Binds → **BZgraph** |

## Config

`config/bzgraph/config.json`

```json
{
  "onlyOnHypixel": true,
  "showBuy": true,
  "showSell": true,
  "defaultRange": "1W",
  "showTrades": true,
  "importLogsOnStartup": true,
  "showBands": true,
  "miniChart": true,
  "miniChartRange": "1W",
  "backgroundOpacity": 65,
  "showMayors": true,
  "trackPurse": true,
  "purseRange": "1W",
  "compareMode": "PERCENT",
  "compareSide": "BUY",
  "compareBoth": false,
  "favoriteGroups": {
    "groups": { "Keys": ["SKELETON_KEY"], "": ["ENCHANTED_DIAMOND"] },
    "collapsed": []
  }
}
```

The last selected range and line toggles are remembered.

## Your trades

Hypixel has no API for your own bazaar history, so BZgraph records it from the bazaar messages in your chat:
`Sold 32x … for … coins!`, `Bought …`, `Your Sell Offer for … was filled!`, `Claimed … coins from selling … at … each!`,
and the matching buy-order messages.

- **Instant sells/buys** are recorded immediately.
- **Offers**: placing one is remembered; the trade is recorded when it's *filled*, at the price of your newest open
  offer for that item. Cancelling an offer (e.g. after being undercut) and relisting records nothing, so the fill uses
  the new price. The claim message then confirms the exact listing price. Coins claimed from an offer that was only
  partly filled are recorded at claim time and marked "partly filled".
- Only messages that *start* with `[Bazaar]` count, so other players can't fake trades by typing them in chat.
  Messages hidden by other mods are still recorded.
- **Old trades**: Minecraft's log files contain your chat history. On startup BZgraph imports trades from this
  instance's `logs/` folder (each file once). Import another launcher's folder with
  `/bzgraph trades import <path to logs folder>`; `/bzgraph trades` shows how many are recorded.
  Logs from sessions BZgraph recorded live are skipped, so nothing is counted twice.
- Trades are kept forever, per Minecraft account, in `config/bzgraph/trades.json`. Nothing is uploaded.
- Chat names ("Fine Citrine Gemstone", "Legion I") are mapped to bazaar IDs using Hypixel's public item list
  (no API key), cached for a week.

## How it works

- History: `GET https://sky.coflnet.com/api/bazaar/{ITEM_ID}/history?start=…&end=…`.
  SkyCofl picks the resolution from the window length: up to ~12 hours comes back at ~3-minute steps reaching about
  10 minutes ago, while longer windows come back as 2-hour (or daily) buckets whose newest bucket can be hours old.
  So **1D** is fetched as two 12-hour windows, and every longer range also fetches the last 12 hours as a fine
  "tail" that replaces the coarse buckets it overlaps. **ALL** additionally makes two requests (the API caps
  responses at 2000 points) and merges/dedupes them.
- Latest price: `GET https://sky.coflnet.com/api/bazaar/{ITEM_ID}/snapshot` (refreshed by SkyCofl every few minutes)
  is added as the final, highlighted point. It's fetched when a graph opens or you press `R`, and reused for 60 s.
- Product list for validation / tab-completion: `GET https://api.hypixel.net/v2/skyblock/bazaar` (no API key), fetched once per session.
- All HTTP goes through a single background queue with **≥1 s spacing** between requests; HTTP 429 honours `Retry-After`.
  Nothing ever blocks the render thread. User-Agent: `bzgraph/<version>`.
- Caching: in-memory LRU for the session plus a disk cache at `config/bzgraph/cache/{ITEM_ID}_{range}.json`.
  Cached data is shown instantly and refreshed in the background once older than its TTL:
  1D = 2 min, 1W = 10 min, 1M = 1 h, 1Y / ALL = 6 h.
- Item IDs come from the stack's `minecraft:custom_data` `id` field. Enchanted books map to
  `ENCHANTMENT_<NAME>_<LEVEL>`; display-name heuristics cover bazaar-menu items without an ID (e.g. `Wither Essence` →
  `ESSENCE_WITHER`). Anything that isn't in the bazaar product list is reported as "not a bazaar item".

## Building

Requires **JDK 25**.

```bash
./gradlew build
```

The mod jar is written to `build/libs/bzgraph-<version>.jar`. Requires Fabric Loader ≥ 0.20.1 and Fabric API.

Unit tests (`./gradlew test`) cover JSON parsing and timestamp handling, merging/deduping the two ALL-range responses,
request-queue spacing, cache TTL, disk/LRU caches, coin formatting, item-ID mapping, comparison maths (% change,
ratios), purse reading/recording, time-axis ticks and resampling.

## Credits

- Price data: **[SkyCofl](https://sky.coflnet.com)** (sky.coflnet.com).
- Bazaar product list: Hypixel public API.

License: CC0-1.0.
