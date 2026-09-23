"""Checks the independent design diagrams, not Minecraft interaction or rendering."""
import unittest
from unittest.mock import patch
from PIL import Image
import draw_proposal as proposal


class CarrierProposalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs = {}
        def capture(image, path, *args, **kwargs):
            cls.outputs[path.name] = image.copy()
        proposal.TEXT_BOUNDS.clear()
        with patch.object(Image.Image, "save", capture):
            proposal.unselected()
            for stage in range(3):
                proposal.mining(stage)
                proposal.compact(stage)
            proposal.mining(0, True)
            proposal.cargo()
            proposal.combat()

    @classmethod
    def tearDownClass(cls):
        for image in cls.outputs.values():
            image.close()

    def test_ten_distinct_states_are_present(self):
        self.assertEqual(10, len(self.outputs))
        for name in ("00-unselected.png", "01-select-area.png", "02-confirm-area.png",
                     "03-mining.png", "04-move-to-area.png", "05-cargo-supply.png", "06-attack.png"):
            self.assertEqual((1280, 720), self.outputs[name].size)

    def test_small_views_are_drawn_separately_without_whole_screen_downscaling(self):
        for name in ("10-small-select.png", "11-small-confirm.png", "12-small-mining.png"):
            self.assertEqual((960, 580), self.outputs[name].size)

    def test_existing_ninety_inventory_slots_keep_their_identity_and_pitch(self):
        slots = proposal.native_slots()
        self.assertEqual(list(range(90)), [slot[0] for slot in slots])
        for index, x, y in slots:
            if index < 54:
                self.assertEqual((8 + index % 9 * 18, 18 + index // 9 * 18), (x, y))
            elif index < 81:
                self.assertEqual((8 + (index - 54) % 9 * 18, 140 + (index - 54) // 9 * 18), (x, y))
            else:
                self.assertEqual((8 + (index - 81) * 18, 198), (x, y))

    def test_inventory_rectangles_never_overlap(self):
        slots = proposal.native_slots()
        for i, (_, ax, ay) in enumerate(slots):
            for _, bx, by in slots[i+1:]:
                self.assertTrue(ax + 16 <= bx or bx + 16 <= ax or ay + 16 <= by or by + 16 <= ay)

    def test_player_labels_do_not_expose_protocol_fields(self):
        labels = [label for label, _, _ in proposal.TEXT_BOUNDS]
        for label in labels:
            for forbidden in ("nonce", "generation", "TTL", "UUID", "世代", "接続未取得", "予約内部ID"):
                self.assertNotIn(forbidden, label)

    def test_primary_verbs_distinguish_move_confirm_and_destructive_start(self):
        labels = {label for label, _, _ in proposal.TEXT_BOUNDS}
        for required in ("未選択", "母艦をここへ移動", "採掘範囲を確認", "この範囲の採掘を開始",
                         "区画を選び直す", "地形は壊しません", "この範囲の敵を攻撃", "停止"):
            self.assertIn(required, labels)
        self.assertEqual(["採掘", "敵への攻撃", "移動", "貨物・補給", "船内"],
                         [label for label, _ in proposal.TABS])

    def test_text_bounds_are_positive_and_diagram_fonts_remain_legible(self):
        self.assertGreater(len(proposal.TEXT_BOUNDS), 100)
        for label, size, width in proposal.TEXT_BOUNDS:
            self.assertGreater(width, 0, label)
            self.assertGreaterEqual(size, 16, label)

    def test_main_map_and_warning_views_are_not_blank(self):
        for name in ("00-unselected.png", "01-select-area.png", "02-confirm-area.png", "06-attack.png"):
            image = self.outputs[name]
            with image.crop((24, 210, 860, 636)) as area:
                self.assertGreater(len(area.getcolors(area.width * area.height)), 10)
            with image.crop((900, 210, 1256, 645)) as area:
                self.assertGreater(len(area.getcolors(area.width * area.height)), 10)


if __name__ == "__main__":
    unittest.main(verbosity=2)
