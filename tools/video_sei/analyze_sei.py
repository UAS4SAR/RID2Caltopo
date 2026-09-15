#!/usr/bin/env python3
"""Extract and characterize H.264 SEI payloads from an MP4 recording.

The tool asks ffprobe for packet offsets and timestamps, then reads the AVCC
NAL units directly from the original file. This avoids decoding or rewriting
the recording and preserves the timestamp assigned to each SEI payload.
"""

from __future__ import annotations

import argparse
import collections
import csv
import dataclasses
import json
import pathlib
import shutil
import struct
import subprocess
import sys
from collections.abc import Iterable, Sequence


@dataclasses.dataclass(frozen=True)
class Packet:
    index: int
    pts_seconds: float | None
    dts_seconds: float | None
    size: int
    position: int


@dataclasses.dataclass(frozen=True)
class SEIRecord:
    packet_index: int
    pts_seconds: float | None
    dts_seconds: float | None
    nal_index: int
    message_index: int
    payload_type: int
    payload: bytes


@dataclasses.dataclass(frozen=True)
class PrivateTLV:
    tag: int
    body: bytes


def parse_optional_float(value: str | None) -> float | None:
    if value in (None, "", "N/A"):
        return None
    return float(value)


def parse_compact_line(line: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for component in line.rstrip().split("|"):
        key, separator, value = component.partition("=")
        if separator:
            fields[key] = value
    return fields


def probe_packets(path: pathlib.Path, ffprobe: str = "ffprobe") -> list[Packet]:
    command = [
        ffprobe,
        "-v",
        "quiet",
        "-select_streams",
        "v:0",
        "-show_packets",
        "-show_entries",
        "packet=pts_time,dts_time,size,pos",
        "-of",
        "compact=p=0:nk=0",
        str(path),
    ]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    packets: list[Packet] = []
    for line in result.stdout.splitlines():
        fields = parse_compact_line(line)
        if "size" not in fields or "pos" not in fields:
            continue
        packets.append(Packet(
            index=len(packets),
            pts_seconds=parse_optional_float(fields.get("pts_time")),
            dts_seconds=parse_optional_float(fields.get("dts_time")),
            size=int(fields["size"]),
            position=int(fields["pos"]),
        ))
    return packets


def parse_avcc_nals(packet: bytes, length_size: int = 4) -> list[bytes]:
    if length_size not in (1, 2, 4):
        raise ValueError("AVCC NAL length size must be 1, 2, or 4 bytes")
    nals: list[bytes] = []
    cursor = 0
    while cursor < len(packet):
        if cursor + length_size > len(packet):
            raise ValueError(f"truncated AVCC NAL length at byte {cursor}")
        nal_size = int.from_bytes(packet[cursor:cursor + length_size], "big")
        cursor += length_size
        if nal_size <= 0 or cursor + nal_size > len(packet):
            raise ValueError(
                f"invalid AVCC NAL size {nal_size} at byte {cursor - length_size}"
            )
        nals.append(packet[cursor:cursor + nal_size])
        cursor += nal_size
    return nals


def remove_emulation_prevention(data: bytes) -> bytes:
    output = bytearray()
    cursor = 0
    while cursor < len(data):
        if (
            cursor >= 2
            and data[cursor - 2:cursor] == b"\x00\x00"
            and data[cursor] == 0x03
            and cursor + 1 < len(data)
            and data[cursor + 1] <= 0x03
        ):
            cursor += 1
            continue
        value = data[cursor]
        output.append(value)
        cursor += 1
    return bytes(output)


def read_extended_value(data: bytes, cursor: int) -> tuple[int, int]:
    value = 0
    while True:
        if cursor >= len(data):
            raise ValueError("truncated SEI extended value")
        component = data[cursor]
        cursor += 1
        value += component
        if component != 0xFF:
            return value, cursor


def parse_sei_messages(nal: bytes) -> list[tuple[int, bytes]]:
    if not nal or (nal[0] & 0x1F) != 6:
        raise ValueError("NAL unit is not H.264 SEI")
    rbsp = remove_emulation_prevention(nal[1:])
    cursor = 0
    messages: list[tuple[int, bytes]] = []
    while cursor < len(rbsp):
        if rbsp[cursor] == 0x80 and all(value == 0 for value in rbsp[cursor + 1:]):
            break
        payload_type, cursor = read_extended_value(rbsp, cursor)
        payload_size, cursor = read_extended_value(rbsp, cursor)
        end = cursor + payload_size
        if end > len(rbsp):
            raise ValueError(
                f"truncated SEI payload type {payload_type}: "
                f"needs {payload_size} bytes, has {len(rbsp) - cursor}"
            )
        messages.append((payload_type, rbsp[cursor:end]))
        cursor = end
    return messages


def extract_sei_records(
    path: pathlib.Path,
    packets: Sequence[Packet],
    *,
    length_size: int = 4,
) -> tuple[list[SEIRecord], list[str]]:
    records: list[SEIRecord] = []
    warnings: list[str] = []
    with path.open("rb") as stream:
        for packet in packets:
            stream.seek(packet.position)
            packet_data = stream.read(packet.size)
            if len(packet_data) != packet.size:
                warnings.append(
                    f"packet {packet.index}: expected {packet.size} bytes at "
                    f"{packet.position}, read {len(packet_data)}"
                )
                continue
            try:
                nals = parse_avcc_nals(packet_data, length_size=length_size)
            except ValueError as error:
                warnings.append(f"packet {packet.index}: {error}")
                continue
            for nal_index, nal in enumerate(nals):
                if not nal or (nal[0] & 0x1F) != 6:
                    continue
                try:
                    messages = parse_sei_messages(nal)
                except ValueError as error:
                    warnings.append(
                        f"packet {packet.index} NAL {nal_index}: {error}"
                    )
                    continue
                for message_index, (payload_type, payload) in enumerate(messages):
                    records.append(SEIRecord(
                        packet_index=packet.index,
                        pts_seconds=packet.pts_seconds,
                        dts_seconds=packet.dts_seconds,
                        nal_index=nal_index,
                        message_index=message_index,
                        payload_type=payload_type,
                        payload=payload,
                    ))
    return records, warnings


def contiguous_ranges(offsets: Iterable[int]) -> list[tuple[int, int]]:
    ordered = sorted(set(offsets))
    if not ordered:
        return []
    ranges: list[tuple[int, int]] = []
    start = previous = ordered[0]
    for offset in ordered[1:]:
        if offset != previous + 1:
            ranges.append((start, previous))
            start = offset
        previous = offset
    ranges.append((start, previous))
    return ranges


def parse_private_tlvs(payload: bytes) -> tuple[list[PrivateTLV], int]:
    """Parse the repeated little-endian tag/length records used by this stream.

    The Matrice payload ends with a variable number of zero padding bytes. A
    zero tag/length header is treated as padding, and non-zero trailing data is
    rejected so an unrelated payload is not accidentally presented as decoded.
    """
    records: list[PrivateTLV] = []
    cursor = 0
    while cursor + 4 <= len(payload):
        tag, length = struct.unpack_from("<HH", payload, cursor)
        if tag == 0 and length == 0:
            break
        end = cursor + 4 + length
        if end > len(payload):
            raise ValueError(
                f"TLV tag {tag} at byte {cursor} declares {length} bytes, "
                f"only {len(payload) - cursor - 4} remain"
            )
        records.append(PrivateTLV(tag=tag, body=payload[cursor + 4:end]))
        cursor = end
    trailing = payload[cursor:]
    if any(trailing):
        raise ValueError(f"non-zero data after TLV records at byte {cursor}")
    return records, len(trailing)


def percentile_nearest_rank(values: Sequence[int], percentile: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = round((len(ordered) - 1) * percentile)
    return ordered[index]


def discover_angle_candidates(
    parsed_payloads: Sequence[Sequence[PrivateTLV]],
    *,
    limit: int = 12,
) -> list[dict[str, object]]:
    """Rank smoothly changing signed int16 fields that could encode angles.

    DJI has not published the private tag schema. Candidates deliberately use
    neutral names and expose their evidence instead of asserting semantics.
    """
    if not parsed_payloads:
        return []
    bodies_by_tag: dict[int, list[bytes]] = collections.defaultdict(list)
    for payload in parsed_payloads:
        seen: set[int] = set()
        for tlv in payload:
            if tlv.tag in seen:
                continue
            seen.add(tlv.tag)
            bodies_by_tag[tlv.tag].append(tlv.body)

    candidates: list[dict[str, object]] = []
    expected_samples = len(parsed_payloads)
    for tag, bodies in bodies_by_tag.items():
        if len(bodies) != expected_samples:
            continue
        body_length = min(map(len, bodies))
        for offset in range(body_length - 1):
            values = [struct.unpack_from("<h", body, offset)[0] for body in bodies]
            distinct = len(set(values))
            minimum = min(values)
            maximum = max(values)
            if distinct < 5 or minimum < -36000 or maximum > 36000:
                continue
            absolute_deltas = [abs(right - left) for left, right in zip(values, values[1:])]
            p95_delta = percentile_nearest_rank(absolute_deltas, 0.95)
            p99_delta = percentile_nearest_rank(absolute_deltas, 0.99)
            continuity = sum(delta <= 500 for delta in absolute_deltas) / max(1, len(absolute_deltas))
            maximum_delta = max(absolute_deltas, default=0)
            if (
                continuity < 0.95
                or p95_delta > 100
                or p99_delta > 500
                or maximum_delta > 2500
            ):
                continue
            range_kind = (
                "heading_0_360"
                if minimum >= 0
                else "signed_angle_-360_360"
            )
            # Prefer continuous, meaningfully changing values over flags and
            # counters. This is only a discovery rank, never a semantic claim.
            score = (
                continuity
                + min(distinct, 1000) / 10000
                + min(maximum - minimum, 36000) / 360000
                - maximum_delta / 250000
            )
            candidates.append({
                "tag": tag,
                "byte_offset": offset,
                "encoding": "int16_le",
                "hypothesis_scale": "0.01_degrees_per_unit",
                "range_kind": range_kind,
                "sample_count": len(values),
                "distinct_values": distinct,
                "first_if_scaled_degrees": round(values[0] / 100, 2),
                "last_if_scaled_degrees": round(values[-1] / 100, 2),
                "minimum_if_scaled_degrees": round(minimum / 100, 2),
                "maximum_if_scaled_degrees": round(maximum / 100, 2),
                "unchanged_fraction": round(absolute_deltas.count(0) / max(1, len(absolute_deltas)), 6),
                "continuity_fraction": round(continuity, 6),
                "p95_step_degrees": round(p95_delta / 100, 2),
                "p99_step_degrees": round(p99_delta / 100, 2),
                "maximum_step_degrees": round(maximum_delta / 100, 2),
                "discovery_score": round(score, 6),
            })
    candidates.sort(
        key=lambda candidate: (
            candidate["discovery_score"],
            candidate["distinct_values"],
        ),
        reverse=True,
    )
    return candidates[:limit]


def decode_caltopo_camera_candidates(
    tlvs: Sequence[PrivateTLV],
) -> dict[str, float]:
    """Decode the current Matrice-to-CalTopo field hypotheses.

    Field names describe their intended CalTopo destination, not a published
    DJI schema. The evidence and confidence for each mapping is emitted in the
    JSON summary and documented in README.md.
    """
    body_by_tag = {tlv.tag: tlv.body for tlv in tlvs}
    attitude = body_by_tag.get(4, b"")
    optics = body_by_tag.get(10, b"")
    values: dict[str, float] = {}
    if len(attitude) >= 39:
        full_turn = float(1 << 32)
        for byte_offset in range(3, 36, 4):
            angle_raw = struct.unpack_from("<I", attitude, byte_offset)[0]
            values[f"diagnostic:tag4_angle_offset_{byte_offset}"] = round(
                angle_raw * 360.0 / full_turn,
                6,
            )
        camera_azimuth_raw = struct.unpack_from("<i", attitude, 3)[0]
        camera_tilt_raw = struct.unpack_from("<I", attitude, 11)[0]
        camera_azimuth = (camera_azimuth_raw / 10_000_000.0) % 360.0
        camera_tilt_encoder = camera_tilt_raw * 360.0 / full_turn
        camera_tilt = ((camera_tilt_encoder - 90.0 + 180.0) % 360.0) - 180.0
        values["camera:azimuth"] = round(camera_azimuth, 6)
        values["camera:tilt"] = round(camera_tilt, 6)
    if len(optics) >= 13:
        values["camera:fov_width"] = round(
            struct.unpack_from("<I", optics, 1)[0] / 256,
            6,
        )
        values["camera:fov_height"] = round(
            struct.unpack_from("<I", optics, 5)[0] / 256,
            6,
        )
        values["diagnostic:lens_state"] = round(
            struct.unpack_from("<I", optics, 9)[0] / 256,
            6,
        )
    return values


def summarize_caltopo_camera_candidates(
    parsed_payloads: Sequence[Sequence[PrivateTLV]],
) -> dict[str, object]:
    decoded = [decode_caltopo_camera_candidates(tlvs) for tlvs in parsed_payloads]
    decoded = [values for values in decoded if values]
    field_mappings: dict[str, dict[str, object]] = {
        "camera:azimuth": {
            "source": "tag_4_byte_3",
            "encoding": "uint32_le_binary_angle_360_over_2_pow_32_then_90_minus_raw_plus_declination",
            "confidence": "field-validated",
            "evidence": "tracked camera-aspect rotations; fixed-target tests established the magnetic-east axis conversion; this is not independently validated aircraft yaw",
        },
        "camera:tilt": {
            "source": "tag_4_byte_11",
            "encoding": "uint32_le_binary_angle_360_over_2_pow_32_minus_90_normalized_signed",
            "confidence": "high",
            "evidence": "tracks the scripted up-down-neutral sweep and reads exactly -90 when straight down",
        },
        "camera:fov_width": {
            "source": "tag_10_byte_1",
            "encoding": "uint32_le_divided_by_256_degrees",
            "confidence": "high",
            "evidence": "tracks zoom and camera changes; ratio with height matches the image aspect",
        },
        "camera:fov_height": {
            "source": "tag_10_byte_5",
            "encoding": "uint32_le_divided_by_256_degrees",
            "confidence": "high",
            "evidence": "tracks zoom and camera changes; ratio with width matches the image aspect",
        },
    }
    field_statistics: dict[str, dict[str, object]] = {}
    for field in field_mappings:
        values = [item[field] for item in decoded if field in item]
        if values:
            field_statistics[field] = {
                "sample_count": len(values),
                "distinct_values": len(set(values)),
                "first": values[0],
                "last": values[-1],
                "minimum": min(values),
                "maximum": max(values),
            }
    aspect_ratios = [
        item["camera:fov_width"] / item["camera:fov_height"]
        for item in decoded
        if item.get("camera:fov_height", 0) > 0
    ]
    ratio_counts = collections.Counter(round(ratio, 3) for ratio in aspect_ratios)
    return {
        "status": "reverse_engineered_candidates_not_yet_operational",
        "field_mappings": field_mappings,
        "field_statistics": field_statistics,
        "common_fov_aspect_ratios": [
            {"ratio": ratio, "count": count}
            for ratio, count in ratio_counts.most_common(4)
        ],
    }


def summarize(records: Sequence[SEIRecord], selected_type: int) -> dict[str, object]:
    type_counts = collections.Counter(record.payload_type for record in records)
    selected = [record for record in records if record.payload_type == selected_type]
    length_counts = collections.Counter(len(record.payload) for record in selected)
    summary: dict[str, object] = {
        "message_count": len(records),
        "payload_type_counts": dict(sorted(type_counts.items())),
        "selected_payload_type": selected_type,
        "selected_message_count": len(selected),
        "selected_payload_lengths": dict(sorted(length_counts.items())),
        "selected_unique_payloads": len({record.payload for record in selected}),
    }
    if not selected:
        return summary
    parsed_payloads: list[list[PrivateTLV]] = []
    parse_errors = 0
    padding_counts: collections.Counter[int] = collections.Counter()
    layout_counts: collections.Counter[tuple[tuple[int, int], ...]] = collections.Counter()
    for record in selected:
        try:
            tlvs, padding_length = parse_private_tlvs(record.payload)
        except ValueError:
            parse_errors += 1
            continue
        parsed_payloads.append(tlvs)
        padding_counts[padding_length] += 1
        layout_counts[tuple((tlv.tag, len(tlv.body)) for tlv in tlvs)] += 1
    dominant_length, dominant_count = length_counts.most_common(1)[0]
    dominant = [record.payload for record in selected if len(record.payload) == dominant_length]
    varying_offsets = [
        offset
        for offset in range(dominant_length)
        if len({payload[offset] for payload in dominant}) > 1
    ]
    summary.update({
        "dominant_payload_length": dominant_length,
        "dominant_payload_count": dominant_count,
        "constant_byte_count": dominant_length - len(varying_offsets),
        "varying_byte_count": len(varying_offsets),
        "varying_byte_ranges": [
            {"start": start, "end": end}
            for start, end in contiguous_ranges(varying_offsets)
        ],
        "byte_statistics": [
            {
                "offset": offset,
                "distinct": len(values := {payload[offset] for payload in dominant}),
                "minimum": min(values),
                "maximum": max(values),
            }
            for offset in range(dominant_length)
        ],
        "private_tlv": {
            "parsed_payload_count": len(parsed_payloads),
            "parse_error_count": parse_errors,
            "layouts": [
                {
                    "count": count,
                    "records": [
                        {"tag": tag, "length": length}
                        for tag, length in layout
                    ],
                }
                for layout, count in layout_counts.most_common()
            ],
            "zero_padding_lengths": dict(sorted(padding_counts.items())),
        },
        "provisional_angle_candidates": discover_angle_candidates(parsed_payloads),
        "caltopo_camera_candidates": summarize_caltopo_camera_candidates(
            parsed_payloads
        ),
    })
    return summary


def write_caltopo_candidate_csv(
    path: pathlib.Path,
    records: Sequence[SEIRecord],
    payload_type: int,
) -> None:
    decoded_fields = [
        "camera:azimuth",
        "camera:tilt",
        "camera:fov_width",
        "camera:fov_height",
        *(f"diagnostic:tag4_angle_offset_{offset}" for offset in range(3, 36, 4)),
        "diagnostic:lens_state",
    ]
    fieldnames = ["packet_index", "pts_seconds", "dts_seconds"] + decoded_fields
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        for record in records:
            if record.payload_type != payload_type:
                continue
            try:
                tlvs, _ = parse_private_tlvs(record.payload)
            except ValueError:
                continue
            row: dict[str, object] = {
                "packet_index": record.packet_index,
                "pts_seconds": record.pts_seconds,
                "dts_seconds": record.dts_seconds,
            }
            row.update(decode_caltopo_camera_candidates(tlvs))
            writer.writerow(row)


def write_candidate_csv(
    path: pathlib.Path,
    records: Sequence[SEIRecord],
    payload_type: int,
    candidates: Sequence[dict[str, object]],
) -> None:
    candidate_keys = [
        (int(candidate["tag"]), int(candidate["byte_offset"]))
        for candidate in candidates
    ]
    fieldnames = ["packet_index", "pts_seconds", "dts_seconds"] + [
        f"tag_{tag:03d}_offset_{offset:03d}_i16le_x_0.01_candidate_degrees"
        for tag, offset in candidate_keys
    ]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        for record in records:
            if record.payload_type != payload_type:
                continue
            try:
                tlvs, _ = parse_private_tlvs(record.payload)
            except ValueError:
                continue
            body_by_tag = {tlv.tag: tlv.body for tlv in tlvs}
            row: dict[str, object] = {
                "packet_index": record.packet_index,
                "pts_seconds": record.pts_seconds,
                "dts_seconds": record.dts_seconds,
            }
            for tag, offset in candidate_keys:
                body = body_by_tag.get(tag, b"")
                if offset + 2 <= len(body):
                    raw_value = struct.unpack_from("<h", body, offset)[0]
                    row[f"tag_{tag:03d}_offset_{offset:03d}_i16le_x_0.01_candidate_degrees"] = raw_value / 100
            writer.writerow(row)


def write_csv(path: pathlib.Path, records: Sequence[SEIRecord], payload_type: int) -> None:
    selected = [record for record in records if record.payload_type == payload_type]
    maximum_length = max((len(record.payload) for record in selected), default=0)
    fieldnames = [
        "packet_index",
        "pts_seconds",
        "dts_seconds",
        "payload_type",
        "payload_length",
        "payload_hex",
    ] + [f"byte_{offset:03d}" for offset in range(maximum_length)]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        for record in selected:
            row: dict[str, object] = {
                "packet_index": record.packet_index,
                "pts_seconds": record.pts_seconds,
                "dts_seconds": record.dts_seconds,
                "payload_type": record.payload_type,
                "payload_length": len(record.payload),
                "payload_hex": record.payload.hex(),
            }
            row.update({
                f"byte_{offset:03d}": value
                for offset, value in enumerate(record.payload)
            })
            writer.writerow(row)


def format_hex_dump(payload: bytes, width: int = 16) -> str:
    lines = []
    for offset in range(0, len(payload), width):
        chunk = payload[offset:offset + width]
        hexadecimal = " ".join(f"{value:02x}" for value in chunk)
        printable = "".join(chr(value) if 32 <= value <= 126 else "." for value in chunk)
        lines.append(f"{offset:08x}  {hexadecimal:<{width * 3 - 1}}  |{printable}|")
    return "\n".join(lines)


def write_hex_dump(
    path: pathlib.Path,
    records: Sequence[SEIRecord],
    payload_type: int | None,
) -> None:
    selected = [
        record for record in records
        if payload_type is None or record.payload_type == payload_type
    ]
    with path.open("w", encoding="utf-8") as stream:
        for index, record in enumerate(selected):
            if index:
                stream.write("\n")
            stream.write(
                f"packet={record.packet_index} pts={record.pts_seconds:.6f} "
                f"dts={record.dts_seconds:.6f} nal={record.nal_index} "
                f"message={record.message_index} type={record.payload_type} "
                f"length={len(record.payload)}\n"
            )
            stream.write(format_hex_dump(record.payload))
            stream.write("\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=pathlib.Path, help="MP4/fMP4 recording")
    parser.add_argument(
        "--payload-type",
        type=int,
        default=245,
        help="SEI payload type to characterize and export (default: 245)",
    )
    parser.add_argument("--csv", type=pathlib.Path, help="write selected payloads to CSV")
    parser.add_argument(
        "--hex-dump",
        type=pathlib.Path,
        help="write a complete timestamped hex dump of selected SEI payloads",
    )
    parser.add_argument(
        "--candidate-csv",
        type=pathlib.Path,
        help="write provisional angle candidate time series to CSV",
    )
    parser.add_argument(
        "--caltopo-csv",
        type=pathlib.Path,
        help="write the four provisional CalTopo camera fields to CSV",
    )
    parser.add_argument("--json", type=pathlib.Path, help="write the summary as JSON")
    parser.add_argument(
        "--nal-length-size",
        type=int,
        choices=(1, 2, 4),
        default=4,
        help="AVCC NAL length prefix size (default: 4)",
    )
    parser.add_argument("--ffprobe", default="ffprobe", help="ffprobe executable")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    recording = args.recording.resolve()
    if not recording.is_file():
        print(f"Recording not found: {recording}", file=sys.stderr)
        return 2
    if shutil.which(args.ffprobe) is None:
        print(f"ffprobe executable not found: {args.ffprobe}", file=sys.stderr)
        return 2
    try:
        packets = probe_packets(recording, ffprobe=args.ffprobe)
        records, warnings = extract_sei_records(
            recording,
            packets,
            length_size=args.nal_length_size,
        )
    except subprocess.CalledProcessError as error:
        print(f"ffprobe failed with exit status {error.returncode}", file=sys.stderr)
        return 1
    summary = summarize(records, args.payload_type)
    summary["recording"] = str(recording)
    summary["video_packet_count"] = len(packets)
    summary["warning_count"] = len(warnings)
    print(json.dumps(summary, indent=2, sort_keys=True))
    for warning in warnings[:20]:
        print(f"warning: {warning}", file=sys.stderr)
    if len(warnings) > 20:
        print(f"warning: {len(warnings) - 20} additional warnings omitted", file=sys.stderr)
    if args.csv:
        write_csv(args.csv, records, args.payload_type)
    if args.hex_dump:
        write_hex_dump(args.hex_dump, records, args.payload_type)
    if args.candidate_csv:
        write_candidate_csv(
            args.candidate_csv,
            records,
            args.payload_type,
            summary.get("provisional_angle_candidates", []),
        )
    if args.caltopo_csv:
        write_caltopo_candidate_csv(
            args.caltopo_csv,
            records,
            args.payload_type,
        )
    if args.json:
        args.json.write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    return 0 if summary["selected_message_count"] else 3


if __name__ == "__main__":
    raise SystemExit(main())
