# BZgraph

Client-side Fabric mod for **Minecraft 26.2** that shows an in-game price-history graph for Hypixel SkyBlock **bazaar** items.

> **Data: [SkyCofl (sky.coflnet.com)](https://sky.coflnet.com)** — all price history comes from the SkyCofl API.
> A short clickable "SkyCofl" credit is shown on every graph screen, as required by the data provider's terms; the
> **?** box on each graph lists all data sources.

## Features

- **Hover + key**: hover an item in any container GUI (inventory, bazaar menu, chests) and press **G** to open its graph.
- **Item search**: press **G** with no screen open (or with nothing hovered in an inventory/chest), or type `/bzgraph`,
  to search bazaar items by name or ID; Enter or a click opens the graph.
  With no GUI open, **G** graphs the item in your main hand.
- **Recipe viewers**: search for an item, hover it and press **G** to graph it.
  - **Reliable Recipe Viewer (RRV)**: works in RRV's item list, bookmarks and craftables (BZgraph registers its key
    through RRV's overlay keybind API) and on RRV's recipe screen (ingredients, results and the workstation icon).
  - **Roughly Enough Items (REI)**: works in REI's item list, favorites and recipe screens.
  Both are optional; BZgraph talks to them through reflection and simply skips this when they aren't installed. G is ignored while a text box (REI search, anvil, creative search) is
  focused.
- **Command**: `/bzgraph` opens the item search; `/bzgraph <ITEM_ID>` opens a graph directly, with tab-completion of all bazaar product IDs (e.g. `/bzgraph ENCHANTED_DIAMOND`).
- **Graph screen** (a centered panel, about three quarters of the screen)
  - Buy and sell price lines, each toggleable (Buy / Sell buttons; filled box = on, hollow = off). **Buy** is the
    insta-buy price (lowest sell offer), **Sell** the insta-sell price (highest buy order); hover the labels to see this.
  - The **?** button (or `H`) explains every line, marker and shortcut; it opens by itself the first time.
  - Time ranges **1D / 1W / 1M / 1Y / ALL** (buttons or keys `1`–`5`; the selected one is outlined). `R` forces a refresh.
  - Each range is drawn at one level of detail: SkyCofl's fine recent samples are merged into the same bucket size as
    the older ones, so the right edge doesn't turn into a scribble.
  - **‹ ›** arrows (or Left/Right arrow keys, or mouse back/forward buttons) move through the items you've opened in
    this session, e.g. after clicking an item in a patch note.
  - `Esc` (or `G`) closes BZgraph entirely and returns to the screen you opened it from; the patch screens' **Back**
    button goes back one step.
  - Hover crosshair with date, buy, sell, spread, volume, the mayor at that time and the patch note on that point.
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
  the same type are shaded apart; terms too thin to label can still be hovered); hover for perks.
  Toggle with `M`.
- **Mini chart in the bazaar**: on an item's page in Hypixel's bazaar, a small chart (buy/sell lines, band, current
  prices and change over the range) appears left of the window. Click it for the full graph; right-click to change
  its range. Turn it off with `"miniChart": false`.
- **Patch notes on the graph**: markers at SkyBlock patch-note release dates. Hover for title, date, what the patch
  changed *for this item* (when the index has a note for it), the general summary, and the median price before vs.
  after the patch; click for details. "Patches: Item / All / Off" button (only patches that
  mention the item, all patches, or none). Markers close together merge into one; clicking it lists them.
- **Patch note browser**: `/bzgraph patches` or `P` on a graph — searchable list with title, date, a short summary,
  mentioned bazaar items (click to graph) and an "Open full post" link to the Hypixel forums.
- **Your trades on the graph**: ▲ where you bought and ▼ where you sold, at the price you got (the same markers on the
  purse graph; neutral white, so a sale doesn't read as a loss). Hover for amount, price, coins, time and how the
  price has moved since. Nearby trades merge into one marker. **Trades** button.
- **Patch notes** are a thin line through the graph with a small flag on top (two flags when several are merged).
  See [Your trades](#your-trades).
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
  "patchMarkers": "RELEVANT",
  "patchIndexUrl": "",
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
  "compareBoth": false
}
```

The last selected range and line toggles are remembered. `patchIndexUrl` overrides the built-in patch index URL
(e.g. a Raspberry Pi); it must be `https://`, or `http://` to a LAN host such as `192.168.x.x` or `raspberrypi.local`.

## Patch index

Patch notes come from a JSON index you host, not from the forum directly:

- `patch-index/index.json` — the index. Each entry has `id`, `date` (UTC), `version`, `title`, `summary`, `url`,
  `autoItems` (found by the script), `items` (manual additions), `excludeItems` (manual removals) and `itemNotes`.
- `itemNotes` maps a product ID to what the patch changed for that item, e.g.
  `{"GLOSSY_GEMSTONE": "Drop rate doubled at every Breaking Power."}`. Keys may end in `*` to cover every level
  (`ENCHANTMENT_KARMA_*`). Once an entry has `itemNotes` (even `{}`), it counts as curated: only the items it names
  (plus `items`) are relevant, and `autoItems` — just names spotted in the post — is ignored.
  Without `itemNotes`, the mod uses `autoItems + items − excludeItems`.
- `patch-index/scripts/update_index.py` — Python 3 (standard library only). Reads the
  [SkyBlock Patch Notes forum](https://hypixel.net/forums/skyblock-patch-notes.158/) RSS feed, fetches each new thread,
  takes the release date from the `[Month Day]` title prefix, and tags bazaar items whose names appear in the post.
  It never overwrites the hand-written fields of existing entries. `--mode backfill` walks the whole forum (seeding),
  `--mode validate` checks the file.
- `.github/workflows/patch-index.yml` — runs the script every 6 hours and opens/updates a pull request on branch
  `patch-index/auto`. Before merging: write each new `summary` **in your own words**, add `itemNotes` for the items
  the patch really affects, and remove `"needsReview": true`. PRs and pushes touching `patch-index/` are validated.
  Enable *Settings → Actions → General → Allow GitHub Actions to create and approve pull requests*.
- **Keep `updated` current.** The mod uses whichever copy (its 24 h download cache or the one bundled in the jar)
  has the newer `updated` stamp. The workflow bumps it automatically when `index.json` changes on `main` without
  it; locally, run `python patch-index/scripts/update_index.py --mode touch` after editing.

The mod downloads the index once per session (cached 24 h in `config/bzgraph/cache/patch_index.json`, via the same
1-request-per-second queue) and also bundles the copy from build time, so it works offline. Set the default URL in
`gradle.properties` (`patch_index_url=https://raw.githubusercontent.com/<owner>/<repo>/main/patch-index/index.json`).
To self-host later, serve the same file from any web server and change that URL (or players' `patchIndexUrl`).

Patch markers show *timing*, not cause — prices also move with events, mayors and the market.

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
- Patch notes: titles, dates and links to the official Hypixel SkyBlock patch notes; summaries are written by the
  index maintainer.

License: CC0-1.0.
