import pathlib
import tempfile
import unittest

from analyze_sei import (
    Packet,
    PrivateTLV,
    SEIRecord,
    contiguous_ranges,
    decode_caltopo_camera_candidates,
    discover_angle_candidates,
    extract_sei_records,
    format_hex_dump,
    parse_avcc_nals,
    parse_private_tlvs,
    parse_sei_messages,
    remove_emulation_prevention,
    summarize,
    write_hex_dump,
)


def avcc(*nals: bytes) -> bytes:
    return b"".join(len(nal).to_bytes(4, "big") + nal for nal in nals)


class AnalyzeSEITests(unittest.TestCase):
    def test_hex_dump_preserves_every_payload_byte_and_timestamp(self):
        record = SEIRecord(7, 1.25, 1.2, 2, 3, 245, b"\x00A\xff")
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "payloads.hex.txt"
            write_hex_dump(output, [record], 245)
            rendered = output.read_text(encoding="utf-8")

        self.assertIn(
            "packet=7 pts=1.250000 dts=1.200000 nal=2 message=3 type=245 length=3",
            rendered,
        )
        self.assertIn("00000000  00 41 ff", rendered)
        self.assertIn("|.A.|", rendered)
        self.assertEqual(format_hex_dump(bytes(range(17))).count("\n"), 1)

    def test_parse_avcc_nals(self):
        self.assertEqual(parse_avcc_nals(avcc(b"\x67abc", b"\x06def")), [b"\x67abc", b"\x06def"])

    def test_remove_emulation_prevention(self):
        self.assertEqual(
            remove_emulation_prevention(b"\x01\x00\x00\x03\x00\x02"),
            b"\x01\x00\x00\x00\x02",
        )
        self.assertEqual(
            remove_emulation_prevention(b"\x01\x00\x00\x03\x04\x02"),
            b"\x01\x00\x00\x03\x04\x02",
        )

    def test_parse_private_sei_message(self):
        payload = b"\x09\x00\x11\x00"
        nal = bytes([0x06, 245, len(payload)]) + payload + b"\x80"
        self.assertEqual(parse_sei_messages(nal), [(245, payload)])

    def test_parse_extended_sei_type_and_size(self):
        payload = bytes(range(256)) + b"abc"
        nal = bytes([0x06, 0xFF, 0x05, 0xFF, 0x04]) + payload + b"\x80"
        self.assertEqual(parse_sei_messages(nal), [(260, payload)])

    def test_extract_uses_packet_offsets_and_timestamps(self):
        payload = b"\x01\x02\x03"
        packet_data = avcc(bytes([0x06, 245, len(payload)]) + payload + b"\x80")
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "sample.mp4"
            path.write_bytes(b"prefix" + packet_data + b"suffix")
            records, warnings = extract_sei_records(
                path,
                [Packet(index=7, pts_seconds=1.25, dts_seconds=1.2, size=len(packet_data), position=6)],
            )
        self.assertEqual(warnings, [])
        self.assertEqual(records, [SEIRecord(
            packet_index=7,
            pts_seconds=1.25,
            dts_seconds=1.2,
            nal_index=0,
            message_index=0,
            payload_type=245,
            payload=payload,
        )])

    def test_summary_reports_varying_byte_ranges(self):
        records = [
            SEIRecord(index, float(index), float(index), 0, 0, 245, payload)
            for index, payload in enumerate((b"\x01\x02\x03\x04", b"\x01\x09\x08\x04"))
        ]
        summary = summarize(records, 245)
        self.assertEqual(summary["selected_message_count"], 2)
        self.assertEqual(summary["dominant_payload_length"], 4)
        self.assertEqual(summary["varying_byte_ranges"], [{"start": 1, "end": 2}])

    def test_contiguous_ranges(self):
        self.assertEqual(contiguous_ranges([1, 2, 4, 7, 8]), [(1, 2), (4, 4), (7, 8)])

    def test_parse_private_tlvs_and_zero_padding(self):
        payload = b"\x09\x00\x03\x00abc\x04\x00\x02\x00de\x00\x00\x00"
        records, padding = parse_private_tlvs(payload)
        self.assertEqual(records, [PrivateTLV(9, b"abc"), PrivateTLV(4, b"de")])
        self.assertEqual(padding, 3)

    def test_parse_private_tlvs_rejects_nonzero_trailing_data(self):
        with self.assertRaisesRegex(ValueError, "non-zero data"):
            parse_private_tlvs(b"\x09\x00\x01\x00a\x01")

    def test_discovers_smooth_little_endian_angle_candidate(self):
        payloads = []
        for value in (21613, 21614, 21614, 21616, 21618, 21620):
            body = b"prefix" + value.to_bytes(2, "little", signed=True) + b"suffix"
            payloads.append([PrivateTLV(4, body)])
        candidates = discover_angle_candidates(payloads)
        candidate = next(
            item for item in candidates
            if item["tag"] == 4 and item["byte_offset"] == 6
        )
        self.assertEqual(candidate["first_if_scaled_degrees"], 216.13)
        self.assertEqual(candidate["last_if_scaled_degrees"], 216.2)

    def test_recorded_signed_heading_boundary(self):
        headings = []
        for raw in (1799354051, -1793949732):
            attitude = bytearray(39)
            attitude[3:7] = raw.to_bytes(4, "little", signed=True)
            decoded = decode_caltopo_camera_candidates([PrivateTLV(4, bytes(attitude))])
            headings.append(decoded["camera:azimuth"])
        self.assertAlmostEqual(headings[1] - headings[0], 0.6696217, places=5)

    def test_decodes_expected_caltopo_camera_field_candidates(self):
        attitude = bytearray(39)
        azimuth_raw = round(111.46 * 10_000_000)
        tilt_raw = round((90.0 - 37.0) / 360.0 * (1 << 32))
        attitude[3:7] = azimuth_raw.to_bytes(4, "little")
        attitude[11:15] = tilt_raw.to_bytes(4, "little")
        optics = bytearray(13)
        optics[1:5] = (9652).to_bytes(4, "little")
        optics[5:9] = (5429).to_bytes(4, "little")
        optics[9:13] = (6720).to_bytes(4, "little")
        decoded = decode_caltopo_camera_candidates([
            PrivateTLV(4, bytes(attitude)),
            PrivateTLV(10, bytes(optics)),
        ])
        self.assertAlmostEqual(decoded["camera:azimuth"], 111.46, places=5)
        self.assertAlmostEqual(decoded["camera:tilt"], -37.0, places=5)
        self.assertAlmostEqual(decoded["diagnostic:tag4_angle_offset_3"], azimuth_raw * 360 / (1 << 32), places=5)
        self.assertEqual(decoded["camera:fov_width"], 37.703125)
        self.assertEqual(decoded["camera:fov_height"], 21.207031)


if __name__ == "__main__":
    unittest.main()
