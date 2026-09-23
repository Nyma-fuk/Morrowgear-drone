"""Independent diagram geometry checks, not Minecraft or browser UI tests."""
import importlib.util
from itertools import combinations
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("carrier_layout", HERE / "carrier-layout.py")
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)


class CarrierDiagramTest(unittest.TestCase):
    def test_all_ninety_slot_ids_are_unique_and_ordered(self):
        self.assertEqual([slot[0] for slot in layout.native_slots()], list(range(90)))

    def test_cargo_has_six_native_rows(self):
        expected = [(row * 9 + column, 8 + 18 * column, 18 + 18 * row)
                    for row in range(6) for column in range(9)]
        self.assertEqual(layout.native_slots()[:54], expected)

    def test_player_has_three_native_rows(self):
        expected = [(54 + row * 9 + column, 8 + 18 * column, 140 + 18 * row)
                    for row in range(3) for column in range(9)]
        self.assertEqual(layout.native_slots()[54:81], expected)

    def test_hotbar_is_separate_native_row(self):
        self.assertEqual(layout.native_slots()[81:], [(81 + i, 8 + i * 18, 198) for i in range(9)])

    def test_slot_borders_fit_native_inventory(self):
        width, height = layout.NATIVE_SIZE
        self.assertEqual((width, height), (176, 222))
        for _, x, y in layout.native_slots():
            self.assertGreaterEqual(x - 1, 0)
            self.assertGreaterEqual(y - 1, 0)
            self.assertLess(x + 16, width)
            self.assertLess(y + 16, height)

    def test_slot_borders_never_overlap(self):
        for a, b in combinations(layout.native_slots(), 2):
            _, ax, ay = a
            _, bx, by = b
            self.assertTrue(ax + 16 < bx - 1 or bx + 16 < ax - 1
                            or ay + 16 < by - 1 or by + 16 < ay - 1, (a, b))

    def test_compact_stop_is_in_view_and_outside_inventory(self):
        width, height = layout.COMPACT_SIZE
        left, top, right, bottom = layout.COMPACT_STOP
        self.assertEqual((width, height), (320, 240))
        self.assertGreaterEqual(left, layout.NATIVE_SIZE[0])
        self.assertGreaterEqual(top, 0)
        self.assertLessEqual(right, width)
        self.assertLessEqual(bottom, height)
        self.assertGreaterEqual(right - left, 120)
        self.assertGreaterEqual(bottom - top, 20)

    def test_180_pixel_height_is_not_claimed_to_fit(self):
        self.assertGreater(layout.NATIVE_SIZE[1], 180)
        self.assertTrue(any(y + 16 > 180 for _, _, y in layout.native_slots()))

    def test_preview_cancel_is_separate_from_emergency_stop(self):
        left, top, right, bottom = layout.COMPACT_CANCEL
        self.assertGreaterEqual(left, layout.NATIVE_SIZE[0])
        self.assertLess(top, bottom)
        self.assertLessEqual(bottom, 161)
        self.assertLess(bottom, layout.COMPACT_STOP[1])
        self.assertLessEqual(right, layout.COMPACT_SIZE[0])
        self.assertGreaterEqual(right - left, 30)

    def test_service_reservations_are_bounded_to_four(self):
        self.assertEqual(layout.BAY_CAPACITY, 4)

    def test_compact_font_is_not_shrunk_with_diagram_scale(self):
        self.assertGreaterEqual(layout.COMPACT_FONT_SIZE, 9)
        self.assertEqual(layout.art.FONTS[layout.COMPACT_FONT_SIZE * 3].size, 27)


if __name__ == "__main__":
    unittest.main(verbosity=2)
