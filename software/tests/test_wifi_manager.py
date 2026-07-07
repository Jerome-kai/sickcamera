from __future__ import annotations

import unittest

from imagegencam.wifi_manager import _split_nmcli_line


class WifiManagerTests(unittest.TestCase):
    def test_split_nmcli_line_handles_escaped_colons(self) -> None:
        self.assertEqual(
            _split_nmcli_line(r"yes:Studio\:WiFi:88:WPA2"),
            ["yes", "Studio:WiFi", "88", "WPA2"],
        )

    def test_split_nmcli_line_preserves_empty_fields(self) -> None:
        self.assertEqual(
            _split_nmcli_line("no:Open Network:42:"),
            ["no", "Open Network", "42", ""],
        )


if __name__ == "__main__":
    unittest.main()
