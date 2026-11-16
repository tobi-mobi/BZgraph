#!/usr/bin/env python3
"""
Maintains patch-index/index.json for BZgraph from the Hypixel SkyBlock Patch Notes forum.

Standard library only. Modes:
  rss       (default) check the forum RSS feed for new threads
  backfill  walk the forum's thread list pages (use once to seed history)
  validate  only validate the index file (exit 1 on problems)

For every new thread it fetches the first post, works out the release date and version, and fills
`autoItems` with bazaar product IDs whose names appear in the post. It never touches the
hand-maintained fields of existing entries (`summary`, `items`, `excludeItems`, `title`, `date`).
New entries get an empty summary and "needsReview": true so a human writes the summary in the PR.
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

FORUM = "https://hypixel.net/forums/skyblock-patch-notes.158/"
RSS = FORUM + "index.rss"
BAZAAR = "https://api.hypixel.net/v2/skyblock/bazaar"
ITEMS = "https://api.hypixel.net/v2/resources/skyblock/items"
USER_AGENT = "bzgraph-patch-index/1.0 (+GitHub Actions)"
# Price history starts 2020-03-12; older patches can't be shown on a graph.
OLDEST = dt.datetime(2020, 3, 1, tzinfo=dt.timezone.utc)
INDEX_VERSION = 1
# Bazaar product names that are too generic to match reliably in prose.
GENERIC_NAMES = {
    "Ice", "Sand", "Egg", "String", "Stone", "Bone", "Snow", "Clay", "Glass", "Feather", "Leather", "Wheat",
    "Coal", "Seeds", "Cactus", "Potato", "Carrot", "Pumpkin", "Melon", "Mushroom", "Gravel", "Flint",
    "Sponge", "Slimeball", "Gold", "Diamond", "Emerald", "Redstone", "Obsidian", "Netherrack", "Quartz",
    "Mithril", "Titanium", "Gemstone", "Sugar Cane", "Rabbit", "Mutton", "Pork", "Raw Fish", "Lily Pad",
}
MONTHS = {m: i for i, m in enumerate(
    ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
     "november", "december"], start=1)}

_last_request = 0.0


def http_get(url: str, delay: float) -> str:
    """GET with a polite delay between requests and a few retries."""
    global _last_request
    for attempt in range(4):
        wait = _last_request + delay - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        _last_request = time.monotonic()
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Language": "en-US"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                retry = int(e.headers.get("Retry-After", "10") or 10)
                print(f"  HTTP {e.code} for {url}, retrying in {retry}s", file=sys.stderr)
                time.sleep(min(retry, 120))
                continue
            raise
        except urllib.error.URLError as e:
            print(f"  {e} for {url}, retrying", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"giving up on {url}")


# ---------------------------------------------------------------- forum parsing

THREAD_ID = re.compile(r"/threads/(?:[^/]*\.)?(\d+)/?")


def thread_id(url: str) -> str | None:
    m = THREAD_ID.search(url)
    return m.group(1) if m else None


def canonical_url(url: str) -> str:
    m = re.search(r"(https://hypixel\.net)?(/threads/[^/?#]+/)", url)
    return "https://hypixel.net" + m.group(2) if m else url


def rss_threads(delay: float) -> list[dict]:
    root = ET.fromstring(http_get(RSS, delay))
    out = []
    for it in root.findall("./channel/item"):
        link = it.findtext("link") or ""
        out.append({"title": (it.findtext("title") or "").strip(), "url": canonical_url(link)})
    return out


def forum_page_threads(page: int, delay: float) -> list[dict]:
    url = FORUM if page == 1 else f"{FORUM}page-{page}"
    try:
        body = http_get(url, delay)
    except urllib.error.HTTPError as e:
        if e.code in (303, 404):
            return []
        raise
    out = []
    for block in body.split("structItem--thread")[1:]:
        a = re.search(r'<a href="(/threads/[^"]+?)"[^>]*>([^<]{4,})</a>', block)
        if a:
            t = re.search(r'datetime="([^"]+)"', block)
            started = parse_xf_datetime(t.group(1)) if t else None
            out.append({"title": html.unescape(a.group(2)).strip(), "url": canonical_url(a.group(1)), "started": started})
    return out


class FirstPostParser(HTMLParser):
    """Collects the text of the first div.bbWrapper and the first <time datetime=...>."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.done = False
        self.parts: list[str] = []
        self.published: str | None = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "time" and self.published is None and a.get("datetime"):
            self.published = a["datetime"]
        if self.done:
            return
        if self.depth == 0:
            if tag == "div" and "bbWrapper" in (a.get("class") or "").split():
                self.depth = 1
        elif tag == "div":
            self.depth += 1
        if self.depth and tag in ("br", "li", "p", "div"):
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if self.depth and not self.done and tag == "div":
            self.depth -= 1
            if self.depth == 0:
                self.done = True

    def handle_data(self, data):
        if self.depth and not self.done:
            self.parts.append(data)

    def text(self) -> str:
        t = "".join(self.parts)
        t = re.sub(r"\{[^{}]*\"lightbox_[^{}]*\}", " ", t)  # lightbox JSON blobs
        return re.sub(r"[ \t​]+", " ", re.sub(r"\n\s*\n+", "\n", t)).strip()


def parse_xf_datetime(s: str) -> dt.datetime:
    # XenForo: 2026-10-27T10:35:04-0400
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%S%z").astimezone(dt.timezone.utc)


def release_date(title: str, posted: dt.datetime) -> dt.datetime:
    """Prefer the "[October 27]" date in the title (release day); fall back to the post time."""
    m = re.match(r"\s*\[([A-Za-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?\]", title)
    if not m or m.group(1).lower() not in MONTHS:
        return posted
    month, day = MONTHS[m.group(1).lower()], int(m.group(2))
    year = posted.year
    if month > posted.month + 1:  # "[December 30]" posted in January
        year -= 1
    try:
        d = dt.date(year, month, day)
    except ValueError:
        return posted
    if d == posted.date():
        return posted
    # midnight US Eastern (standard time) – day precision is what matters
    return dt.datetime(d.year, d.month, d.day, 5, 0, tzinfo=dt.timezone.utc)


def version_of(title: str, text: str) -> str | None:
    m = re.search(r"\b[vV]?(0\.\d{1,2}(?:\.\d{1,2})?)\b", title) or re.search(r"\bSkyBlock v?(0\.\d{1,2}(?:\.\d{1,2})?)\b", text[:400])
    return m.group(1) if m else None


# ---------------------------------------------------------------- item matching

def titlecase(s: str) -> str:
    return " ".join(w.capitalize() for w in s.lower().split("_"))


ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]


def load_product_names(delay: float) -> dict[str, list[str]]:
    """Returns display name -> bazaar product IDs. Non-bazaar item names map to [] so they mask
    shorter bazaar names inside them (e.g. "Lotus Necklace" must not count as "Lotus")."""
    products = set(json.loads(http_get(BAZAAR, delay))["products"].keys())
    items = {i["id"]: i.get("name") for i in json.loads(http_get(ITEMS, delay))["items"]}
    names: dict[str, list[str]] = {}

    def clean(name):
        return re.sub(r"§.", "", name or "").strip()

    def add(name, pid):
        name = clean(name)
        if len(name) >= 3 and name not in GENERIC_NAMES:
            names.setdefault(name, []).append(pid)

    for pid, name in items.items():
        if pid not in products and clean(name):
            names.setdefault(clean(name), [])

    for pid in sorted(products):
        if pid in items and items[pid]:
            add(items[pid], pid)
        elif pid.startswith("ESSENCE_"):
            add(titlecase(pid[len("ESSENCE_"):]) + " Essence", pid)
        elif pid.startswith("ENCHANTMENT_"):
            m = re.match(r"ENCHANTMENT_(.+)_(\d+)$", pid)
            if m:
                base, lvl = titlecase(m.group(1)), int(m.group(2))
                # Bare enchant names ("Power", "Growth") are common words: require a level or "Enchantment".
                if 1 <= lvl <= len(ROMAN):
                    add(f"{base} {ROMAN[lvl - 1]}", pid)
                add(f"{base} Enchantment", pid)
                add(f"{base} enchantment", pid)
        else:
            add(titlecase(pid), pid)
    return names


def match_items(text: str, names: dict[str, list[str]]) -> list[str]:
    """Case-sensitive, whole-word, longest-name-first matching; matched spans are masked."""
    found: set[str] = set()
    masked = text
    for name in sorted(names, key=len, reverse=True):
        if name not in masked:  # fast path
            continue
        pattern = re.compile(r"(?<![A-Za-z0-9])" + re.escape(name) + r"(?![A-Za-z0-9])")
        if pattern.search(masked):
            found.update(names[name])
            masked = pattern.sub(lambda m: "#" * len(m.group(0)), masked)
    return sorted(found)


# ---------------------------------------------------------------- index file

def load_index(path: Path) -> dict:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"version": INDEX_VERSION, "updated": None, "patches": []}


def save_index(path: Path, index: dict) -> None:
    index["patches"].sort(key=lambda p: (p["date"], p["id"]), reverse=True)
    index["version"] = INDEX_VERSION
    path.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def validate(index: dict) -> list[str]:
    errors = []
    if index.get("version") != INDEX_VERSION:
        errors.append(f"version must be {INDEX_VERSION}")
    seen = set()
    for i, p in enumerate(index.get("patches", [])):
        where = f"patches[{i}] ({p.get('id')})"
        for key in ("id", "date", "title", "url"):
            if not isinstance(p.get(key), str) or not p[key].strip():
                errors.append(f"{where}: missing '{key}'")
        if p.get("id") in seen:
            errors.append(f"{where}: duplicate id")
        seen.add(p.get("id"))
        try:
            dt.datetime.strptime(p.get("date", ""), "%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            errors.append(f"{where}: date must look like 2026-10-27T14:35:04Z")
        if not str(p.get("url", "")).startswith("https://"):
            errors.append(f"{where}: url must be https")
        for key in ("autoItems", "items", "excludeItems"):
            v = p.get(key, [])
            if not isinstance(v, list) or not all(isinstance(x, str) for x in v):
                errors.append(f"{where}: '{key}' must be a list of strings")
        if not isinstance(p.get("summary", ""), str):
            errors.append(f"{where}: summary must be a string")
        # Optional, hand-written: {"PRODUCT_ID": "what changed for it"}; keys may end in * (prefix match).
        # Once present (even {}), the mod ignores autoItems for this entry.
        notes = p.get("itemNotes")
        if notes is not None:
            if not isinstance(notes, dict) or not all(
                    isinstance(k, str) and isinstance(v, str) and k.strip() for k, v in notes.items()):
                errors.append(f"{where}: 'itemNotes' must map item IDs to strings")
            elif any(k != k.upper() for k in notes):
                errors.append(f"{where}: 'itemNotes' keys must be upper-case product IDs")
    return errors


def make_entry(thread: dict, names: dict[str, list[str]], delay: float) -> dict | None:
    page = http_get(thread["url"], delay)
    parser = FirstPostParser()
    parser.feed(page)
    if not parser.published:
        print(f"  no post date found for {thread['url']}, skipping", file=sys.stderr)
        return None
    posted = parse_xf_datetime(parser.published)
    if posted < OLDEST:
        return None
    text = parser.text()
    date = release_date(thread["title"], posted)
    return {
        "id": "t" + thread_id(thread["url"]),
        "date": date.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "version": version_of(thread["title"], text),
        "title": thread["title"],
        "summary": "",
        "url": thread["url"],
        "autoItems": match_items(thread["title"] + "\n" + text, names),
        "items": [],
        "excludeItems": [],
        "needsReview": True,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--index", type=Path, default=Path(__file__).resolve().parent.parent / "index.json")
    ap.add_argument("--mode", choices=["rss", "backfill", "validate"], default="rss")
    ap.add_argument("--max-pages", type=int, default=60, help="backfill: forum pages to walk")
    ap.add_argument("--max-new", type=int, default=400, help="stop after this many new entries")
    ap.add_argument("--delay", type=float, default=1.5, help="seconds between HTTP requests")
    args = ap.parse_args()

    index = load_index(args.index)
    if args.mode == "validate":
        errors = validate(index)
        for e in errors:
            print("ERROR:", e)
        print(f"{len(index.get('patches', []))} patches, {len(errors)} problems")
        return 1 if errors else 0

    known = {p["id"] for p in index["patches"]}
    if args.mode == "rss":
        threads = rss_threads(args.delay)
    else:
        threads, seen = [], set()
        for page in range(1, args.max_pages + 1):
            got = [t for t in forum_page_threads(page, args.delay) if t["url"] not in seen]
            if not got:
                break
            seen.update(t["url"] for t in got)
            recent = [t for t in got if t["started"] is None or t["started"] >= OLDEST]
            threads.extend(recent)
            print(f"page {page}: {len(got)} threads, {len(recent)} since {OLDEST.date()}", file=sys.stderr)
            if not recent:
                break  # everything older than the price data from here on

    new = [t for t in threads if thread_id(t["url"]) and "t" + thread_id(t["url"]) not in known]
    print(f"{len(threads)} threads, {len(new)} new", file=sys.stderr)
    if not new:
        return 0

    names = load_product_names(args.delay)
    added = 0
    for t in new[: args.max_new]:
        print(f"+ {t['title']}", file=sys.stderr)
        try:
            entry = make_entry(t, names, args.delay)
        except Exception as e:  # keep going; one bad thread shouldn't kill the run
            print(f"  failed: {e}", file=sys.stderr)
            continue
        if entry:
            index["patches"].append(entry)
            known.add(entry["id"])
            added += 1
    if added:
        index["updated"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        save_index(args.index, index)
    errors = validate(index)
    for e in errors:
        print("ERROR:", e, file=sys.stderr)
    print(f"added {added} entries", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
