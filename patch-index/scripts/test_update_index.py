"""Offline tests for the patch-index tagger. Run: python -m unittest discover patch-index/scripts"""
import unittest

import update_index as ui

PRODUCTS = {"ASHWREATH", "SNOOZLING", "GLASSCORN", "SHARD_SCATHA", "SHARD_POWER_DRAGON", "REFINED_MINERAL",
            "GLOSSY_GEMSTONE", "LOTUS", "ENCHANTMENT_KARMA_1", "ENCHANTMENT_KARMA_2", "ESSENCE_ICE"}
ITEMS = {"ASHWREATH": "Ashwreath", "SNOOZLING": "Snoozling", "GLASSCORN": "Glasscorn",
         "REFINED_MINERAL": "Refined Mineral", "GLOSSY_GEMSTONE": "Glossy Gemstone", "LOTUS": "Lotus",
         "LOTUS_NECKLACE": "Lotus Necklace"}

# Shape of a real XenForo post: a table whose cells used to run together ("Ashwreath0.30.15").
HTML = """<html><body><time datetime="2026-08-20T09:42:25-0400"></time>
<div class="bbWrapper">Adjusted multipliers.<br>
<table><tr><th>Mutation</th><th>Old Multiplier</th><th>New Multiplier</th></tr>
<tr><td>Ashwreath</td><td>0.3</td><td>0.15</td></tr>
<tr><td>Snoozling</td><td>4</td><td>21</td></tr></table>
Scatha shard effects are doubled. Increased Refined Minerals and Glossy Gemstones drop rates.
The Power Dragon Shard can no longer be ID-fused. Wear your Lotus Necklace on the Lotus Atoll.
Karma I is not a word we match without a level, but Karma alone is ignored.
<div class="nested">Glasscorn drops more cactus.</div></div>
<div class="bbWrapper">second post, ignored: Ice Essence</div></body></html>"""


class TaggerTest(unittest.TestCase):
    def setUp(self):
        p = ui.FirstPostParser()
        p.feed(HTML)
        self.text = p.text()
        self.names = ui.build_names(PRODUCTS, ITEMS)
        self.found = set(ui.match_items(self.text, self.names))

    def test_table_cells_are_separated(self):
        self.assertNotIn("Ashwreath0.3", self.text)
        self.assertIn("ASHWREATH", self.found)
        self.assertIn("SNOOZLING", self.found)

    def test_shards_match_both_spellings(self):
        self.assertIn("SHARD_SCATHA", self.found)
        self.assertIn("SHARD_POWER_DRAGON", self.found)

    def test_plurals(self):
        self.assertIn("REFINED_MINERAL", self.found)
        self.assertIn("GLOSSY_GEMSTONE", self.found)

    def test_longer_non_bazaar_names_mask_shorter_ones(self):
        # "Lotus Necklace" masks "Lotus" there; "Lotus Atoll" still matches (curators exclude it via itemNotes).
        self.assertEqual(self.text.count("Lotus Necklace"), 1)
        self.assertIn("LOTUS", self.found)

    def test_enchant_needs_level(self):
        self.assertIn("ENCHANTMENT_KARMA_1", self.found)
        self.assertNotIn("ENCHANTMENT_KARMA_2", self.found)

    def test_nested_divs_and_first_post_only(self):
        self.assertIn("GLASSCORN", self.found)
        self.assertNotIn("ESSENCE_ICE", self.found)


if __name__ == "__main__":
    unittest.main()
