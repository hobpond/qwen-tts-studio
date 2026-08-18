#!/usr/bin/env python3
"""Render a manifest-backed audiobook as a dense Chinese sweep video.

The batch manifest remains the source of truth.  The renderer does not run TTS
again and does not infer timings from a second copy of the source text.  It
maps each validated chunk's exact WAV frame interval across the non-whitespace
characters in that chunk, writes ASS ``\\kf`` karaoke events, burns them over a
solid background, and muxes the existing combined WAV as the only audio.

There are no true character alignments in the batch manifest, so the timing
source is deliberately reported as ``uniform-character-within-chunk``.  This
is a learning aid and a visible reading playhead, not a claim that the model's
phoneme timing was measured.  The active line is centered in a multi-line
reading window; surrounding lines remain visible as sung context or a blue
read-ahead instead of leaving the screen mostly empty like the original
two-row KTV layout.

The final file is written only after structural ffprobe checks and a pixel-level
probe show that the highlighted fill actually advances during a character
sweep.  The probe is adapted from the KTV renderer in ``D:/work/ktv-pitcch``.

``--streamable`` adds a resumable delivery path.  It encodes the source WAV to
one cached AAC/M4A artifact, then assembles a fragmented MP4 and an HLS
fMP4/VOD package by copying the encoded audio and rendered video.  A failed
assembly therefore does not force another multi-hour WAV-to-AAC encode.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import math
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence


SCRIPT_VERSION = "1.2"
TIMING_SOURCE = "uniform-character-within-chunk"
DEFAULT_WIDTH = 1280
DEFAULT_HEIGHT = 720
DEFAULT_FPS = 24
DEFAULT_FONT_SIZE = 36
DEFAULT_CHARS_PER_LINE = 20
DEFAULT_LINES_ON_SCREEN = 7
DEFAULT_SEGMENT_DURATION = 600.0
DEFAULT_SEGMENT_WORKERS = 2
DEFAULT_BACKGROUND = "0x101820"
DEFAULT_FONT = "Microsoft YaHei"
DEFAULT_CRF = 27
DEFAULT_AUDIO_BITRATE = "96k"

# ASS uses &HAABBGGRR, whereas the probe reads RGB24.  These are the same
# colours used by the KTV renderer: white is the sung/fill state and blue is
# the unsung/remainder state.
SUNG_COLOUR_ASS = "&H00FFFFFF"
UNSUNG_COLOUR_ASS = "&H00E08010"
SUNG_RGB = (255, 255, 255)
UNSUNG_RGB = (16, 128, 224)


class RenderError(RuntimeError):
    """A named, fail-closed renderer or validation failure."""


@dataclass(frozen=True)
class TimedChar:
    text: str
    start_s: float
    end_s: float


@dataclass(frozen=True)
class DisplayLine:
    chars: tuple[TimedChar, ...]
    start_s: float
    end_s: float

    @property
    def text(self) -> str:
        return "".join(char.text for char in self.chars)


@dataclass(frozen=True)
class ChunkRecord:
    index: int
    text: str
    frame_count: int
    sample_rate: int
    display_index: str


@dataclass(frozen=True)
class RenderPlan:
    lines: tuple[DisplayLine, ...]
    chunks: tuple[ChunkRecord, ...]
    duration_s: float
    sample_rate: int
    source_char_count: int
    rendered_char_count: int
    source_text_sha256: str
    longest_sweep: tuple[float, float]
    timing_source: str = TIMING_SOURCE


@dataclass(frozen=True)
class VideoSegment:
    index: int
    start_s: float
    end_s: float
    frame_count: int

    @property
    def duration_s(self) -> float:
        return self.end_s - self.start_s


def _positive_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise RenderError(f"manifest field {name!r} must be a positive integer")
    return value


def _read_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise RenderError(f"cannot read manifest {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise RenderError(f"manifest {path} is not a JSON object")
    return value


def _chunks_from_manifest(manifest: dict[str, Any], *, allow_unvalidated: bool) -> tuple[ChunkRecord, ...]:
    raw_chunks = manifest.get("chunks")
    if not isinstance(raw_chunks, list) or not raw_chunks:
        raise RenderError("manifest must contain a non-empty chunks array")

    expected = manifest.get("expectedChunkCount")
    if isinstance(expected, int) and expected != len(raw_chunks):
        raise RenderError(
            f"manifest expectedChunkCount={expected}, but contains {len(raw_chunks)} chunks"
        )

    by_index: dict[int, ChunkRecord] = {}
    for position, raw in enumerate(raw_chunks):
        if not isinstance(raw, dict):
            raise RenderError(f"manifest chunk at position {position} is not an object")
        index = raw.get("index")
        if isinstance(index, bool) or not isinstance(index, int):
            raise RenderError(f"manifest chunk at position {position} has no integer index")
        if index in by_index:
            raise RenderError(f"manifest contains duplicate chunk index {index}")

        status = str(raw.get("status", "")).upper()
        if status not in {"COMPLETE", "COMPLETED"}:
            raise RenderError(f"chunk {index} is not complete (status={status!r})")
        if not allow_unvalidated and raw.get("validationPassed") is not True:
            raise RenderError(
                f"chunk {index} is not validationPassed=true; use --allow-unvalidated only "
                "when the visualization is intentionally being built from unvalidated audio"
            )

        text = raw.get("text")
        if not isinstance(text, str):
            raise RenderError(f"chunk {index} has no string text")
        frame_count = _positive_int(raw.get("frameCount"), f"chunks[{index}].frameCount")
        sample_rate = _positive_int(raw.get("sampleRate"), f"chunks[{index}].sampleRate")
        display_index = raw.get("displayIndex")
        if not isinstance(display_index, str):
            display_index = str(index)
        by_index[index] = ChunkRecord(
            index=index,
            text=text,
            frame_count=frame_count,
            sample_rate=sample_rate,
            display_index=display_index,
        )

    ordered = tuple(by_index[index] for index in sorted(by_index))
    expected_indexes = tuple(range(len(ordered)))
    actual_indexes = tuple(chunk.index for chunk in ordered)
    if actual_indexes != expected_indexes:
        raise RenderError(
            "manifest chunk indexes are not contiguous from zero: "
            f"first mismatch expected {expected_indexes[:3]}..., got {actual_indexes[:3]}..."
        )

    sample_rates = {chunk.sample_rate for chunk in ordered}
    if len(sample_rates) != 1:
        raise RenderError(f"combined audio cannot be mapped from mixed sample rates: {sorted(sample_rates)}")
    return ordered


def _normalize_display_text(text: str) -> str:
    """Make text safe for one visual line while preserving visible characters."""
    if text.startswith("\ufeff"):
        text = text[1:]
    # Newlines and tabs are layout controls in the source, not useful visible
    # glyphs for this fixed-row presentation.  Collapse them to one
    # regular space so no words are silently concatenated.
    return re.sub(r"\s+", " ", text)


def _timed_chars(text: str, start_s: float, end_s: float) -> tuple[TimedChar, ...]:
    normalized = _normalize_display_text(text)
    visible = sum(1 for char in normalized if not char.isspace())
    if visible == 0:
        return ()
    result: list[TimedChar] = []
    visible_index = 0
    span = max(0.0, end_s - start_s)
    for char in normalized:
        if char.isspace():
            # A space has no fill width, but retaining it in the text keeps
            # Latin words readable.  Its zero interval is intentional.
            at = start_s + span * visible_index / visible
            result.append(TimedChar(char, at, at))
            continue
        char_start = start_s + span * visible_index / visible
        char_end = start_s + span * (visible_index + 1) / visible
        result.append(TimedChar(char, char_start, char_end))
        visible_index += 1
    return tuple(result)


def _line_bounds(chars: Sequence[TimedChar]) -> tuple[float, float] | None:
    visible = [char for char in chars if not char.text.isspace()]
    if not visible:
        return None
    return visible[0].start_s, visible[-1].end_s


def build_plan(
    chunks: Sequence[ChunkRecord],
    *,
    chars_per_line: int,
    duration_limit_s: float | None,
) -> RenderPlan:
    if chars_per_line < 4 or chars_per_line > 120:
        raise RenderError("--chars-per-line must be between 4 and 120")

    full_duration_s = 0.0
    source_parts: list[str] = []
    all_lines: list[DisplayLine] = []
    sweeps: list[tuple[float, float]] = []
    rendered_char_count = 0

    for chunk in chunks:
        source_parts.append(chunk.text.lstrip("\ufeff"))
        chunk_start = full_duration_s
        chunk_duration = chunk.frame_count / chunk.sample_rate
        full_duration_s += chunk_duration
        chunk_end = full_duration_s
        chars = _timed_chars(chunk.text, chunk_start, chunk_end)
        rendered_char_count += sum(1 for char in chars if not char.text.isspace())
        visible_chars = [char for char in chars if not char.text.isspace()]
        sweeps.extend(
            (char.start_s, char.end_s)
            for char in visible_chars
            if char.end_s > char.start_s
        )

        # Fixed-width wrapping is deliberate: CJK characters are naturally
        # readable one-by-one, and the same width keeps the sweep stable from
        # chapter to chapter.  The renderer later places several of these
        # lines in a centered reading window.
        for offset in range(0, len(chars), chars_per_line):
            line_chars = chars[offset : offset + chars_per_line]
            bounds = _line_bounds(line_chars)
            if bounds is None:
                continue
            all_lines.append(
                DisplayLine(
                    chars=line_chars,
                    start_s=bounds[0],
                    end_s=bounds[1],
                )
            )

    if not all_lines or not sweeps:
        raise RenderError("manifest contains no visible text to render")

    # A line stays active until the next line begins.  The dense ASS layout
    # uses each active interval to redraw the surrounding context rows, so a
    # learner sees several previous and following lines at once.
    final_duration = full_duration_s if duration_limit_s is None else min(full_duration_s, duration_limit_s)
    if final_duration <= 0:
        raise RenderError("the selected render duration is not positive")
    clipped: list[DisplayLine] = []
    for index, line in enumerate(all_lines):
        if line.start_s >= final_duration:
            break
        next_line_start = all_lines[index + 1].start_s if index + 1 < len(all_lines) else final_duration
        line_end = min(final_duration, max(line.end_s, next_line_start))
        if line_end <= line.start_s:
            continue
        clipped.append(DisplayLine(line.chars, line.start_s, line_end))

    if not clipped:
        raise RenderError("the selected render duration contains no display line")
    in_window = [
        (line.start_s, min(line.end_s, final_duration))
        for line in clipped
        if line.start_s < final_duration and line.end_s > line.start_s
    ]
    if not in_window:
        raise RenderError("the selected render duration contains no measurable active-line sweep")
    longest = max(in_window, key=lambda window: window[1] - window[0])
    if longest[1] <= longest[0]:
        raise RenderError("the selected render duration contains no measurable active-line sweep")

    source_text = "\n".join(source_parts).encode("utf-8")
    source_char_count = sum(len(part) for part in source_parts)
    return RenderPlan(
        lines=tuple(clipped),
        chunks=tuple(chunks),
        duration_s=final_duration,
        sample_rate=chunks[0].sample_rate,
        source_char_count=source_char_count,
        rendered_char_count=rendered_char_count,
        source_text_sha256=hashlib.sha256(source_text).hexdigest(),
        longest_sweep=longest,
    )


def _ass_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")


def _ass_timestamp(seconds: float) -> str:
    centiseconds = max(0, int(round(seconds * 100)))
    hours, remainder = divmod(centiseconds, 360000)
    minutes, remainder = divmod(remainder, 6000)
    whole_seconds, cents = divmod(remainder, 100)
    return f"{hours}:{minutes:02d}:{whole_seconds:02d}.{cents:02d}"


def _ass_dialogue_text(line: DisplayLine) -> str:
    parts: list[str] = []
    for char in line.chars:
        if char.text.isspace():
            parts.append(" ")
            continue
        centiseconds = max(1, int(round((char.end_s - char.start_s) * 100)))
        parts.append(f"{{\\kf{centiseconds}}}{_ass_escape(char.text)}")
    return "".join(parts)


def _row_positions(*, height: int, font_size: int, lines_on_screen: int) -> tuple[int, ...]:
    if lines_on_screen < 3 or lines_on_screen > 11 or lines_on_screen % 2 == 0:
        raise RenderError("--lines-on-screen must be an odd number between 3 and 11")
    if height < lines_on_screen * font_size:
        raise RenderError(
            f"height {height} is too small for {lines_on_screen} lines at font-size {font_size}"
        )

    # Keep the reading window away from the edge while using most of the
    # canvas.  Alignment 5 makes each position the vertical centre of a row.
    margin = max(font_size + 12, int(round(height * 0.08)))
    usable_height = height - 2 * margin
    if usable_height <= 0:
        raise RenderError("height leaves no space for the audiobook reading window")
    return tuple(
        round(margin + usable_height * row / (lines_on_screen - 1))
        for row in range(lines_on_screen)
    )


def build_ass(
    plan: RenderPlan,
    *,
    width: int,
    height: int,
    font_size: int,
    font_name: str,
    lines_on_screen: int,
    segment_start_s: float = 0.0,
    segment_end_s: float | None = None,
) -> str:
    if segment_start_s < 0 or segment_start_s >= plan.duration_s:
        raise RenderError(f"subtitle segment start {segment_start_s} is outside the render plan")
    if segment_end_s is None:
        segment_end_s = plan.duration_s
    if segment_end_s <= segment_start_s or segment_end_s > plan.duration_s + 0.001:
        raise RenderError(
            f"subtitle segment {segment_start_s}-{segment_end_s} is outside the render plan"
        )

    row_positions = _row_positions(
        height=height,
        font_size=font_size,
        lines_on_screen=lines_on_screen,
    )

    def style(name: str, primary: str, secondary: str) -> str:
        return ",".join(
            [
                name,
                font_name,
                str(font_size),
                primary,
                secondary,
                "&H00101820",  # outline
                "&H80101820",  # translucent back colour
                "0,0,0,0",      # bold, italic, underline, strikeout
                "100,100",      # scale x/y
                "0,0",          # spacing, angle
                "1,3,1",        # border style, outline, shadow
                "5",            # centred horizontal and vertical alignment
                "30,30,30",     # margins; positions are explicit per row
                "1",            # encoding
            ]
        )

    header = "\n".join(
        [
            "[Script Info]",
            "ScriptType: v4.00+",
            f"PlayResX: {width}",
            f"PlayResY: {height}",
            "WrapStyle: 0",
            "ScaledBorderAndShadow: yes",
            "YCbCr Matrix: TV.601",
            "",
            "[V4+ Styles]",
            (
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
                "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, "
                "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
                "Alignment, MarginL, MarginR, MarginV, Encoding"
            ),
            f"Style: {style('AudiobookActive', SUNG_COLOUR_ASS, UNSUNG_COLOUR_ASS)}",
            f"Style: {style('AudiobookSung', SUNG_COLOUR_ASS, SUNG_COLOUR_ASS)}",
            f"Style: {style('AudiobookFuture', UNSUNG_COLOUR_ASS, UNSUNG_COLOUR_ASS)}",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
        ]
    )
    center_row = lines_on_screen // 2
    events: list[str] = []
    for active_index, active_line in enumerate(plan.lines):
        if active_line.end_s <= segment_start_s or active_line.start_s >= segment_end_s:
            continue
        active_start = max(segment_start_s, active_line.start_s)
        active_end = min(segment_end_s, active_line.end_s)
        if active_end <= active_start:
            continue
        for row, y in enumerate(row_positions):
            line_index = active_index + row - center_row
            if line_index < 0 or line_index >= len(plan.lines):
                continue
            display_line = plan.lines[line_index]
            if line_index == active_index:
                style_name = "AudiobookActive"
                text = _ass_dialogue_text(display_line)
            elif line_index < active_index:
                style_name = "AudiobookSung"
                text = _ass_escape(display_line.text)
            else:
                style_name = "AudiobookFuture"
                text = _ass_escape(display_line.text)
            if not text:
                continue
            positioned = f"{{\\pos({width // 2},{y})}}{text}"
            events.append(
                f"Dialogue: 0,{_ass_timestamp(active_start - segment_start_s)},"
                f"{_ass_timestamp(active_end - segment_start_s)},"
                f"{style_name},,0,0,0,,{positioned}"
            )
    return header + "\n" + "\n".join(events) + "\n"


def _tool_path(name: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which(name)
    if found:
        return found
    raise RenderError(f"{name} is not on PATH; pass --{name} explicitly")


def _run(command: Sequence[str], *, cwd: Path | None = None, timeout_s: float | None = None) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            list(command),
            cwd=str(cwd) if cwd else None,
            capture_output=True,
            timeout=timeout_s,
            check=False,
        )
    except FileNotFoundError as exc:
        raise RenderError(f"unable to start {command[0]!r}: {exc}") from exc
    except subprocess.TimeoutExpired as exc:
        raise RenderError(f"command timed out after {timeout_s}s: {command[0]}") from exc


def _tail_output(proc: subprocess.CompletedProcess[bytes]) -> str:
    data = proc.stderr or proc.stdout or b""
    return data.decode("utf-8", errors="replace")[-3000:].strip()


def _probe_json(ffprobe: str, path: Path) -> dict[str, Any]:
    proc = _run(
        [ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
        timeout_s=120,
    )
    if proc.returncode != 0:
        raise RenderError(f"ffprobe failed for {path.name}: {_tail_output(proc)}")
    try:
        value = json.loads((proc.stdout or b"{}").decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise RenderError(f"ffprobe returned invalid JSON for {path.name}: {exc}") from exc
    if not isinstance(value, dict):
        raise RenderError(f"ffprobe returned a non-object for {path.name}")
    return value


def _stream_duration(stream: dict[str, Any], format_data: dict[str, Any]) -> float | None:
    for value in (stream.get("duration"), format_data.get("duration")):
        try:
            result = float(value)
        except (TypeError, ValueError):
            continue
        if math.isfinite(result) and result >= 0:
            return result
    return None


def _verify_streams(ffprobe: str, video: Path, audio: Path, expected_duration: float) -> dict[str, Any]:
    video_probe = _probe_json(ffprobe, video)
    audio_probe = _probe_json(ffprobe, audio)
    video_streams = [s for s in video_probe.get("streams", []) if s.get("codec_type") == "video"]
    audio_streams = [s for s in video_probe.get("streams", []) if s.get("codec_type") == "audio"]
    source_audio_streams = [s for s in audio_probe.get("streams", []) if s.get("codec_type") == "audio"]
    if len(video_streams) != 1 or len(audio_streams) != 1:
        raise RenderError(
            f"final video must contain exactly one video and one audio stream; "
            f"found video={len(video_streams)}, audio={len(audio_streams)}"
        )
    if len(source_audio_streams) != 1:
        raise RenderError(f"source audio must contain exactly one audio stream; found {len(source_audio_streams)}")
    video_stream = video_streams[0]
    audio_stream = audio_streams[0]
    actual_duration = _stream_duration(video_stream, video_probe.get("format", {}))
    if actual_duration is None:
        raise RenderError("ffprobe did not report a video duration")
    if abs(actual_duration - expected_duration) > max(1.0, 2.0 / 24.0):
        raise RenderError(
            f"final video duration {actual_duration:.3f}s differs from planned "
            f"{expected_duration:.3f}s"
        )
    sample_rate = audio_stream.get("sample_rate")
    if str(sample_rate) != str(source_audio_streams[0].get("sample_rate")):
        raise RenderError("final AAC sample rate does not match the TTS WAV sample rate")
    return {
        "video": {
            "codec": video_stream.get("codec_name"),
            "width": video_stream.get("width"),
            "height": video_stream.get("height"),
            "fps": video_stream.get("r_frame_rate"),
            "durationSeconds": actual_duration,
        },
        "audio": {
            "codec": audio_stream.get("codec_name"),
            "sampleRate": sample_rate,
            "channels": audio_stream.get("channels"),
            "durationSeconds": _stream_duration(audio_stream, video_probe.get("format", {})),
        },
        "sourceAudio": {
            "codec": source_audio_streams[0].get("codec_name"),
            "sampleRate": source_audio_streams[0].get("sample_rate"),
            "channels": source_audio_streams[0].get("channels"),
            "durationSeconds": _stream_duration(source_audio_streams[0], audio_probe.get("format", {})),
        },
    }


def _count_near(raw: bytes, target: tuple[int, int, int], tolerance: int) -> int:
    count = 0
    for offset in range(0, len(raw) - 2, 3):
        if all(abs(raw[offset + channel] - target[channel]) <= tolerance for channel in range(3)):
            count += 1
    return count


def _sample_sung_pixels(ffmpeg: str, video: Path, at_s: float) -> tuple[int, int]:
    proc = _run(
        [
            ffmpeg,
            "-v",
            "error",
            "-ss",
            f"{max(0.0, at_s):.3f}",
            "-i",
            str(video),
            "-frames:v",
            "1",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgb24",
            "-",
        ],
        timeout_s=120,
    )
    if proc.returncode != 0 or not proc.stdout:
        raise RenderError(f"pixel probe found no frame at {at_s:.3f}s in {video.name}: {_tail_output(proc)}")
    return (
        _count_near(proc.stdout, SUNG_RGB, 24),
        _count_near(proc.stdout, UNSUNG_RGB, 40),
    )


def _highlight_advances(counts: Sequence[int]) -> bool:
    if len(counts) < 2:
        return False
    tolerance = max(4, int(0.01 * max(counts)))
    monotone = all(next_value >= current - tolerance for current, next_value in zip(counts, counts[1:]))
    return monotone and counts[-1] - counts[0] > tolerance


def _verify_pixel_sweep(ffmpeg: str, video: Path, plan: RenderPlan, *, samples: int = 5) -> dict[str, Any]:
    start_s, end_s = plan.longest_sweep
    span = end_s - start_s
    if span <= 0.02:
        raise RenderError("longest active-line sweep is too short for a pixel-level verification")
    points = [start_s + span * (0.15 + 0.70 * index / (samples - 1)) for index in range(samples)]
    measurements = [_sample_sung_pixels(ffmpeg, video, point) for point in points]
    sung_counts = [measurement[0] for measurement in measurements]
    if not _highlight_advances(sung_counts):
        raise RenderError(
            "the rendered active-line highlight did not advance: sung-pixel counts "
            f"{sung_counts} at {[round(point, 3) for point in points]}s across "
            f"{start_s:.3f}-{end_s:.3f}s"
        )
    return {
        "passed": True,
        "sweepStartSeconds": start_s,
        "sweepEndSeconds": end_s,
        "sampleTimesSeconds": points,
        "sungPixelCounts": sung_counts,
        "unsungPixelCounts": [measurement[1] for measurement in measurements],
    }


def _render_ffmpeg(
    ffmpeg: str,
    ass_path: Path,
    output_path: Path,
    audio_path: Path,
    *,
    duration_s: float,
    width: int,
    height: int,
    fps: int,
    crf: int,
    audio_bitrate: str,
    timeout_s: float | None,
) -> None:
    command = [
        ffmpeg,
        "-y",
        "-v",
        "error",
        "-f",
        "lavfi",
        "-i",
        f"color=c={DEFAULT_BACKGROUND}:s={width}x{height}:r={fps}:d={duration_s:.3f}",
        "-i",
        str(audio_path.resolve()),
        "-map",
        "0:v:0",
        "-map",
        "1:a:0",
        "-vf",
        f"subtitles={ass_path.name}",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        str(crf),
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-b:a",
        audio_bitrate,
        "-shortest",
        "-movflags",
        "+faststart",
        str(output_path),
    ]
    proc = _run(command, cwd=ass_path.parent, timeout_s=timeout_s)
    if proc.returncode != 0:
        raise RenderError(f"ffmpeg failed rendering {output_path.name}: {_tail_output(proc)}")
    if not output_path.exists() or output_path.stat().st_size <= 0:
        raise RenderError(f"ffmpeg reported success but did not write {output_path}")


def _plan_video_segments(plan: RenderPlan, *, target_duration_s: float, fps: int) -> tuple[VideoSegment, ...]:
    if target_duration_s <= 0:
        raise RenderError("segment duration must be positive")
    if fps <= 0:
        raise RenderError("segment planning requires a positive fps")

    line_starts = [line.start_s for line in plan.lines]
    segments: list[VideoSegment] = []
    start_s = 0.0
    total_frames = max(1, int(round(plan.duration_s * fps)))
    while start_s < plan.duration_s - 0.000001:
        target_end = min(plan.duration_s, start_s + target_duration_s)
        eligible = [
            value
            for value in line_starts
            if value > start_s + 0.000001 and value <= target_end + 0.000001
        ]
        if eligible:
            end_s = max(eligible)
        else:
            later = [value for value in line_starts if value > target_end + 0.000001]
            end_s = later[0] if later else plan.duration_s
        end_s = min(plan.duration_s, max(end_s, start_s + 0.000001))

        start_frame = int(round(start_s * fps))
        end_frame = min(total_frames, int(round(end_s * fps)))
        if end_frame <= start_frame:
            end_frame = min(total_frames, start_frame + 1)
            if end_frame <= start_frame:
                break
        segments.append(
            VideoSegment(
                index=len(segments),
                start_s=start_s,
                end_s=end_s,
                frame_count=end_frame - start_frame,
            )
        )
        start_s = end_s

    if not segments or abs(segments[-1].end_s - plan.duration_s) > 0.001:
        raise RenderError("segment planner did not cover the complete render duration")
    return tuple(segments)


def _render_video_segment(
    ffmpeg: str,
    ass_path: Path,
    output_path: Path,
    segment: VideoSegment,
    *,
    width: int,
    height: int,
    fps: int,
    crf: int,
    timeout_s: float | None,
) -> None:
    duration_s = max(segment.frame_count / fps, 1.0 / fps)
    command = [
        ffmpeg,
        "-y",
        "-v",
        "error",
        "-f",
        "lavfi",
        "-i",
        f"color=c={DEFAULT_BACKGROUND}:s={width}x{height}:r={fps}:d={duration_s:.6f}",
        "-vf",
        f"subtitles={ass_path.name}",
        "-frames:v",
        str(segment.frame_count),
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        str(crf),
        "-pix_fmt",
        "yuv420p",
        str(output_path),
    ]
    proc = _run(command, cwd=ass_path.parent, timeout_s=timeout_s)
    if proc.returncode != 0:
        raise RenderError(
            f"ffmpeg failed rendering video segment {segment.index}: {_tail_output(proc)}"
        )
    if not output_path.exists() or output_path.stat().st_size <= 0:
        raise RenderError(f"ffmpeg reported success but did not write segment {output_path}")


def _concat_video_segments(
    ffmpeg: str,
    segment_paths: Sequence[Path],
    concat_path: Path,
    output_path: Path,
    *,
    timeout_s: float | None,
) -> None:
    concat_path.write_text(
        "".join(f"file '{path.name}'\n" for path in segment_paths),
        encoding="utf-8",
    )
    proc = _run(
        [
            ffmpeg,
            "-y",
            "-v",
            "error",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(concat_path),
            "-c",
            "copy",
            str(output_path),
        ],
        cwd=concat_path.parent,
        timeout_s=timeout_s,
    )
    if proc.returncode != 0:
        raise RenderError(f"ffmpeg failed joining video segments: {_tail_output(proc)}")
    if not output_path.exists() or output_path.stat().st_size <= 0:
        raise RenderError(f"ffmpeg reported success but did not write joined video {output_path}")


def _read_json_object(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _audio_source_identity(audio_path: Path, audio_probe: dict[str, Any]) -> dict[str, Any]:
    try:
        stat = audio_path.stat()
    except OSError as exc:
        raise RenderError(f"cannot stat source audio {audio_path}: {exc}") from exc
    streams = [stream for stream in audio_probe.get("streams", []) if stream.get("codec_type") == "audio"]
    if len(streams) != 1:
        raise RenderError(f"source audio must contain exactly one audio stream; found {len(streams)}")
    stream = streams[0]
    return {
        "path": str(audio_path.resolve()),
        "sizeBytes": stat.st_size,
        "modifiedTimeNs": stat.st_mtime_ns,
        "codec": stream.get("codec_name"),
        "sampleRate": stream.get("sample_rate"),
        "channels": stream.get("channels"),
        "durationSeconds": _stream_duration(stream, audio_probe.get("format", {})),
    }


def _verify_encoded_audio(
    ffprobe: str,
    path: Path,
    *,
    expected_sample_rate: str,
    expected_duration: float,
) -> dict[str, Any]:
    probe = _probe_json(ffprobe, path)
    streams = [stream for stream in probe.get("streams", []) if stream.get("codec_type") == "audio"]
    if len(streams) != 1:
        raise RenderError(f"encoded audio must contain exactly one audio stream; found {len(streams)}")
    stream = streams[0]
    if stream.get("codec_name") != "aac":
        raise RenderError(f"encoded audio must use AAC; found {stream.get('codec_name')!r}")
    if str(stream.get("sample_rate")) != expected_sample_rate:
        raise RenderError(
            f"encoded audio sample rate {stream.get('sample_rate')} differs from manifest {expected_sample_rate}"
        )
    actual_duration = _stream_duration(stream, probe.get("format", {}))
    if actual_duration is None or actual_duration <= 0:
        raise RenderError(f"encoded audio {path.name} has no positive duration")
    if abs(actual_duration - expected_duration) > max(1.0, 2.0 / DEFAULT_FPS):
        raise RenderError(
            f"encoded audio duration {actual_duration:.3f}s differs from planned {expected_duration:.3f}s"
        )
    return {
        "codec": stream.get("codec_name"),
        "sampleRate": stream.get("sample_rate"),
        "channels": stream.get("channels"),
        "durationSeconds": actual_duration,
        "bitRate": stream.get("bit_rate"),
    }


def _encode_audio_artifact(
    ffmpeg: str,
    ffprobe: str,
    source_path: Path,
    source_probe: dict[str, Any],
    output_path: Path,
    metadata_path: Path,
    *,
    expected_sample_rate: str,
    duration_s: float,
    audio_bitrate: str,
    timeout_s: float | None,
) -> dict[str, Any]:
    """Create or reuse one durable AAC artifact for streamable delivery."""
    source_identity = _audio_source_identity(source_path, source_probe)
    expected_metadata = {
        "schemaVersion": 1,
        "source": source_identity,
        "audio": {
            "codec": "aac",
            "bitrate": audio_bitrate,
            "targetDurationSeconds": round(duration_s, 3),
            "sampleRate": expected_sample_rate,
        },
    }
    existing_metadata = _read_json_object(metadata_path) if output_path.is_file() else None
    if existing_metadata == expected_metadata:
        try:
            probe = _verify_encoded_audio(
                ffprobe,
                output_path,
                expected_sample_rate=expected_sample_rate,
                expected_duration=duration_s,
            )
            return {
                "path": output_path,
                "metadata": expected_metadata,
                "probe": probe,
                "reused": True,
            }
        except RenderError:
            # A matching sidecar is not enough if the media was truncated or
            # replaced.  Rebuild the cache atomically below.
            pass

    pending_path = output_path.with_name(output_path.stem + ".pending.m4a")
    pending_metadata_path = metadata_path.with_name(metadata_path.name + ".pending")
    pending_path.unlink(missing_ok=True)
    pending_metadata_path.unlink(missing_ok=True)
    command = [
        ffmpeg,
        "-y",
        "-v",
        "error",
        "-i",
        str(source_path.resolve()),
        "-map",
        "0:a:0",
        "-vn",
        "-c:a",
        "aac",
        "-b:a",
        audio_bitrate,
    ]
    source_duration = source_identity.get("durationSeconds")
    if isinstance(source_duration, (int, float)) and duration_s < float(source_duration) - 0.01:
        command.extend(["-t", f"{duration_s:.6f}"])
    command.extend(["-movflags", "+faststart", str(pending_path)])
    try:
        proc = _run(command, cwd=output_path.parent, timeout_s=timeout_s)
        if proc.returncode != 0:
            raise RenderError(f"ffmpeg failed encoding {output_path.name}: {_tail_output(proc)}")
        probe = _verify_encoded_audio(
            ffprobe,
            pending_path,
            expected_sample_rate=expected_sample_rate,
            expected_duration=duration_s,
        )
        pending_metadata_path.write_text(
            json.dumps(_json_safe(expected_metadata), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        pending_path.replace(output_path)
        pending_metadata_path.replace(metadata_path)
        return {
            "path": output_path,
            "metadata": expected_metadata,
            "probe": probe,
            "reused": False,
        }
    except BaseException:
        pending_path.unlink(missing_ok=True)
        pending_metadata_path.unlink(missing_ok=True)
        raise


def _verify_fragmented_mp4(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as handle:
            prefix = handle.read(8 * 1024 * 1024)
    except OSError as exc:
        raise RenderError(f"cannot inspect fragmented MP4 {path}: {exc}") from exc
    moov = prefix.find(b"moov")
    moof = prefix.find(b"moof")
    if moov < 0 or moof < 0:
        raise RenderError(f"streamable MP4 {path.name} does not contain an initialization and media fragment")
    mdat = prefix.find(b"mdat")
    if mdat >= 0 and moov > mdat:
        raise RenderError(f"streamable MP4 {path.name} places its initialization after media data")
    return {
        "container": "fragmented-mp4",
        "initializationAtomFound": True,
        "mediaFragmentFound": True,
        "initializationBeforeMedia": mdat < 0 or moov < mdat,
    }


def _verify_hls_package(hls_dir: Path, playlist_name: str) -> dict[str, Any]:
    playlist_path = hls_dir / playlist_name
    try:
        lines = playlist_path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise RenderError(f"cannot read HLS playlist {playlist_path}: {exc}") from exc
    if not lines or lines[0].strip() != "#EXTM3U":
        raise RenderError(f"HLS playlist {playlist_name} is missing #EXTM3U")
    if "#EXT-X-ENDLIST" not in lines:
        raise RenderError(f"HLS playlist {playlist_name} is not complete")
    map_lines = [line for line in lines if line.startswith("#EXT-X-MAP:")]
    if len(map_lines) != 1:
        raise RenderError(f"HLS playlist {playlist_name} must contain one fMP4 initialization map")
    media_names = [
        line.strip()
        for line in lines
        if line.strip() and not line.startswith("#")
    ]
    if not media_names:
        raise RenderError(f"HLS playlist {playlist_name} contains no media segments")
    referenced = [map_lines[0].split('URI="', 1)[-1].split('"', 1)[0]] + media_names
    for name in referenced:
        candidate = (hls_dir / name).resolve()
        if candidate.parent != hls_dir.resolve() or not candidate.is_file() or candidate.stat().st_size <= 0:
            raise RenderError(f"HLS playlist references missing or empty artifact {name!r}")
    return {
        "playlist": playlist_path,
        "segmentCount": len(media_names),
        "initialization": referenced[0],
        "segments": media_names,
        "complete": True,
    }


def _write_hls_package(
    ffmpeg: str,
    input_path: Path,
    hls_dir: Path,
    *,
    playlist_name: str,
    segment_duration: float,
    timeout_s: float | None,
) -> dict[str, Any]:
    """Write a transactional HLS fMP4 package from the finalized media."""
    pending_dir = hls_dir.with_name(hls_dir.name + ".pending")
    if pending_dir.exists():
        shutil.rmtree(pending_dir)
    pending_dir.mkdir(parents=True, exist_ok=True)
    pending_playlist = pending_dir / playlist_name
    command = [
        ffmpeg,
        "-y",
        "-v",
        "error",
        "-i",
        str(input_path.resolve()),
        "-map",
        "0:v:0",
        "-map",
        "0:a:0",
        "-c",
        "copy",
        "-f",
        "hls",
        "-hls_time",
        f"{segment_duration:.3f}",
        "-hls_playlist_type",
        "vod",
        "-hls_segment_type",
        "fmp4",
        "-hls_fmp4_init_filename",
        "init.mp4",
        "-hls_segment_filename",
        "segment-%05d.m4s",
        str(pending_playlist),
    ]
    try:
        proc = _run(command, cwd=pending_dir, timeout_s=timeout_s)
        if proc.returncode != 0:
            raise RenderError(f"ffmpeg failed writing HLS package {hls_dir.name}: {_tail_output(proc)}")
        verification = _verify_hls_package(pending_dir, playlist_name)
        if hls_dir.exists():
            shutil.rmtree(hls_dir)
        pending_dir.replace(hls_dir)
        verification["directory"] = hls_dir
        verification["playlist"] = hls_dir / playlist_name
        return verification
    except BaseException:
        if pending_dir.exists():
            shutil.rmtree(pending_dir)
        raise


def _mux_video_audio(
    ffmpeg: str,
    video_path: Path,
    audio_path: Path,
    output_path: Path,
    *,
    audio_bitrate: str | None,
    copy_audio: bool = False,
    fragmented: bool = False,
    timeout_s: float | None,
) -> None:
    command = [
        ffmpeg,
        "-y",
        "-v",
        "error",
        "-i",
        str(video_path.resolve()),
        "-i",
        str(audio_path.resolve()),
        "-map",
        "0:v:0",
        "-map",
        "1:a:0",
        "-c:v",
        "copy",
    ]
    if copy_audio:
        command.extend(["-c:a", "copy"])
    else:
        if not audio_bitrate:
            raise RenderError("audio bitrate is required when audio is not copied")
        command.extend(["-c:a", "aac", "-b:a", audio_bitrate])
    command.extend(["-shortest", "-movflags"])
    command.append("+empty_moov+default_base_moof+frag_keyframe" if fragmented else "+faststart")
    command.append(str(output_path))
    proc = _run(command, cwd=output_path.parent, timeout_s=timeout_s)
    if proc.returncode != 0:
        raise RenderError(f"ffmpeg failed muxing final audio/video: {_tail_output(proc)}")
    if not output_path.exists() or output_path.stat().st_size <= 0:
        raise RenderError(f"ffmpeg reported success but did not write final video {output_path}")


def _remove_paths(paths: Sequence[Path]) -> None:
    for path in paths:
        path.unlink(missing_ok=True)


def _render_video_artifact(
    *,
    ffmpeg: str,
    ffprobe: str,
    plan: RenderPlan,
    audio_path: Path,
    output_path: Path,
    pending_video: Path,
    pending_ass: Path,
    width: int,
    height: int,
    fps: int,
    font_size: int,
    font_name: str,
    lines_on_screen: int,
    crf: int,
    audio_bitrate: str,
    segment_duration: float,
    segment_workers: int,
    keep_segments: bool,
    streamable: bool,
    timeout_s: float | None,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Render, join, mux, and verify one final audiobook video."""
    if segment_duration <= 0:
        if streamable:
            raise RenderError("--streamable requires a positive --segment-duration")
        _render_ffmpeg(
            ffmpeg,
            pending_ass,
            pending_video,
            audio_path,
            duration_s=plan.duration_s,
            width=width,
            height=height,
            fps=fps,
            crf=crf,
            audio_bitrate=audio_bitrate,
            timeout_s=timeout_s,
        )
        streams = _verify_streams(ffprobe, pending_video, audio_path, plan.duration_s)
        sweep = _verify_pixel_sweep(ffmpeg, pending_video, plan)
        segmentation = {
            "mode": "monolithic",
            "targetDurationSeconds": None,
            "workerCount": 1,
            "segmentCount": 1,
            "join": None,
            "audioMux": "single-source-WAV-AAC-encode",
        }
        return streams, sweep, segmentation

    segments = _plan_video_segments(plan, target_duration_s=segment_duration, fps=fps)
    segment_dir = output_path.parent / f"{output_path.stem}-segments"
    segment_dir.mkdir(parents=True, exist_ok=True)
    segment_ass_paths = tuple(
        segment_dir / f"segment-{segment.index:05d}.ass" for segment in segments
    )
    segment_paths = tuple(
        segment_dir / f"segment-{segment.index:05d}.mp4" for segment in segments
    )
    segment_pending_paths = tuple(
        segment_dir / f"segment-{segment.index:05d}.pending.mp4" for segment in segments
    )
    concat_path = segment_dir / "segments.pending.concat.txt"
    joined_path = segment_dir / "joined.pending.mp4"
    segment_metadata_path = segment_dir / "segments.json"
    segment_metadata_pending_path = segment_dir / "segments.pending.json"
    retain_segments = keep_segments or streamable

    segment_descriptor = {
        "schemaVersion": 1,
        "sourceTextSha256": plan.source_text_sha256,
        "durationSeconds": round(plan.duration_s, 3),
        "width": width,
        "height": height,
        "fps": fps,
        "fontName": font_name,
        "fontSize": font_size,
        "linesOnScreen": lines_on_screen,
        "crf": crf,
        "targetDurationSeconds": segment_duration,
        "segments": [
            {
                "index": segment.index,
                "startSeconds": segment.start_s,
                "endSeconds": segment.end_s,
                "frameCount": segment.frame_count,
            }
            for segment in segments
        ],
    }
    cached_descriptor = _read_json_object(segment_metadata_path)
    cache_compatible = streamable and cached_descriptor == segment_descriptor
    reused_segment_count = 0

    for segment, ass_path in zip(segments, segment_ass_paths):
        ass_path.write_text(
            build_ass(
                plan,
                width=width,
                height=height,
                font_size=font_size,
                font_name=font_name,
                lines_on_screen=lines_on_screen,
                segment_start_s=segment.start_s,
                segment_end_s=segment.end_s,
            ),
            encoding="utf-8",
        )

    generated_paths = [
        *segment_ass_paths,
        *segment_paths,
        *segment_pending_paths,
        concat_path,
        joined_path,
        segment_metadata_pending_path,
    ]
    try:
        def render_one(segment: VideoSegment, ass_path: Path, output: Path, pending: Path) -> bool:
            if cache_compatible and output.is_file() and output.stat().st_size > 0:
                return True
            _render_video_segment(
                ffmpeg,
                ass_path,
                pending,
                segment,
                width=width,
                height=height,
                fps=fps,
                crf=crf,
                timeout_s=timeout_s,
            )
            pending.replace(output)
            return False

        with ThreadPoolExecutor(max_workers=segment_workers) as executor:
            futures = [
                executor.submit(render_one, segment, ass_path, output, pending)
                for segment, ass_path, output, pending in zip(
                    segments, segment_ass_paths, segment_paths, segment_pending_paths
                )
            ]
            for future in as_completed(futures):
                if future.result():
                    reused_segment_count += 1

        if streamable:
            segment_metadata_pending_path.write_text(
                json.dumps(_json_safe(segment_descriptor), indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            segment_metadata_pending_path.replace(segment_metadata_path)

        _concat_video_segments(
            ffmpeg,
            segment_paths,
            concat_path,
            joined_path,
            timeout_s=timeout_s,
        )
        _mux_video_audio(
            ffmpeg,
            joined_path,
            audio_path,
            pending_video,
            audio_bitrate=None if streamable else audio_bitrate,
            copy_audio=streamable,
            fragmented=streamable,
            timeout_s=timeout_s,
        )
        streams = _verify_streams(ffprobe, pending_video, audio_path, plan.duration_s)
        sweep = _verify_pixel_sweep(ffmpeg, pending_video, plan)
        stream_package = None
        if streamable:
            fragmented_probe = _verify_fragmented_mp4(pending_video)
            stream_package = _write_hls_package(
                ffmpeg,
                pending_video,
                output_path.parent / f"{output_path.stem}-hls",
                playlist_name=f"{output_path.stem}.m3u8",
                segment_duration=segment_duration,
                timeout_s=timeout_s,
            )
        segmentation = {
            "mode": "parallel-video-segments-streamable" if streamable else "parallel-video-segments",
            "targetDurationSeconds": segment_duration,
            "workerCount": segment_workers,
            "segmentCount": len(segments),
            "join": "concat-demuxer-video-copy",
            "audioMux": "cached-AAC-stream-copy" if streamable else "single-source-WAV-AAC-encode",
            "segmentCache": {
                "enabled": streamable,
                "compatible": cache_compatible,
                "reusedCount": reused_segment_count,
                "retained": retain_segments,
                "metadata": segment_metadata_path if streamable else None,
            },
            "segments": [
                {
                    "index": segment.index,
                    "startSeconds": segment.start_s,
                    "endSeconds": segment.end_s,
                    "durationSeconds": segment.duration_s,
                    "frameCount": segment.frame_count,
                }
                for segment in segments
            ],
        }
        if streamable:
            segmentation["delivery"] = {
                "mode": "fragmented-mp4-plus-hls-fmp4",
                "fragmentedMp4": output_path,
                "fragmentedMp4Probe": fragmented_probe,
                "hls": stream_package,
            }
        if not retain_segments:
            _remove_paths(generated_paths)
            try:
                segment_dir.rmdir()
            except OSError:
                pass
        return streams, sweep, segmentation
    except BaseException:
        _remove_paths([pending_video, pending_ass])
        if not retain_segments:
            _remove_paths(generated_paths)
            try:
                segment_dir.rmdir()
            except OSError:
                pass
        raise


def _json_safe(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


def render(args: argparse.Namespace) -> dict[str, Any]:
    manifest_path = Path(args.manifest).resolve()
    manifest = _read_manifest(manifest_path)
    chunks = _chunks_from_manifest(manifest, allow_unvalidated=args.allow_unvalidated)
    audio_path = Path(args.audio).resolve() if args.audio else manifest_path.parent / "combined.wav"
    if not audio_path.is_file():
        raise RenderError(f"combined TTS audio does not exist: {audio_path}")

    ffmpeg = _tool_path("ffmpeg", args.ffmpeg)
    ffprobe = _tool_path("ffprobe", args.ffprobe)
    probe_audio = _probe_json(ffprobe, audio_path)
    audio_streams = [stream for stream in probe_audio.get("streams", []) if stream.get("codec_type") == "audio"]
    if len(audio_streams) != 1:
        raise RenderError(f"source audio must contain exactly one audio stream; found {len(audio_streams)}")
    expected_sample_rate = str(chunks[0].sample_rate)
    actual_sample_rate = str(audio_streams[0].get("sample_rate"))
    if actual_sample_rate != expected_sample_rate:
        raise RenderError(
            f"manifest chunks use {expected_sample_rate} Hz but combined WAV reports {actual_sample_rate} Hz"
        )

    duration_limit_s = args.duration_limit if args.duration_limit and args.duration_limit > 0 else None
    plan = build_plan(
        chunks,
        chars_per_line=args.chars_per_line,
        duration_limit_s=duration_limit_s,
    )
    output_dir = Path(args.output_dir).resolve() if args.output_dir else manifest_path.parent / "audiobook-video"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / args.output_name
    ass_path = output_dir / (output_path.stem + ".ass")
    report_path = output_dir / (output_path.stem + "-report.json")
    pending_video = output_dir / (output_path.name + ".pending.mp4")
    hls_dir = output_dir / f"{output_path.stem}-hls"
    # Keep the .ass suffix on the temporary file: libass/ffmpeg uses the
    # subtitle extension when selecting the parser on Windows.
    pending_ass = output_dir / (output_path.stem + ".pending.ass")
    if not args.overwrite and (
        output_path.exists()
        or report_path.exists()
        or (args.streamable and hls_dir.exists())
    ):
        raise RenderError(
            f"output already exists in {output_dir}; pass --overwrite to replace the generated artifact"
        )

    ass_text = build_ass(
        plan,
        width=args.width,
        height=args.height,
        font_size=args.font_size,
        font_name=args.font_name,
        lines_on_screen=args.lines_on_screen,
    )
    pending_ass.write_text(ass_text, encoding="utf-8")
    started = time.time()
    audio_for_render = audio_path
    audio_artifact = None
    try:
        if args.streamable:
            audio_cache_path = output_dir / f"{output_path.stem}.audio.m4a"
            audio_cache_metadata_path = output_dir / f"{output_path.stem}.audio.m4a.json"
            audio_artifact = _encode_audio_artifact(
                ffmpeg,
                ffprobe,
                audio_path,
                probe_audio,
                audio_cache_path,
                audio_cache_metadata_path,
                expected_sample_rate=expected_sample_rate,
                duration_s=plan.duration_s,
                audio_bitrate=args.audio_bitrate,
                timeout_s=args.timeout,
            )
            audio_for_render = Path(audio_artifact["path"])
        streams, sweep, segmentation = _render_video_artifact(
            ffmpeg=ffmpeg,
            ffprobe=ffprobe,
            plan=plan,
            audio_path=audio_for_render,
            output_path=output_path,
            pending_video=pending_video,
            pending_ass=pending_ass,
            width=args.width,
            height=args.height,
            fps=args.fps,
            font_size=args.font_size,
            font_name=args.font_name,
            lines_on_screen=args.lines_on_screen,
            crf=args.crf,
            audio_bitrate=args.audio_bitrate,
            segment_duration=args.segment_duration,
            segment_workers=args.segment_workers,
            keep_segments=args.keep_segments,
            streamable=args.streamable,
            timeout_s=args.timeout,
        )
        pending_video.replace(output_path)
        pending_ass.replace(ass_path)
    except BaseException:
        pending_video.unlink(missing_ok=True)
        pending_ass.unlink(missing_ok=True)
        raise

    report = {
        "renderer": "qwen-tts-studio/scripts/render-audiobook-video.py",
        "rendererVersion": SCRIPT_VERSION,
        "source": {
            "manifest": manifest_path,
            "audio": audio_path,
            "batchId": manifest.get("batchId"),
            "manifestRevision": manifest.get("manifestRevision"),
            "manifestTextFingerprint": (manifest.get("metadata") or {}).get("textFingerprint"),
            "sourceTextSha256": plan.source_text_sha256,
        },
        "timing": {
            "source": plan.timing_source,
            "normalization": "UTF-8 BOM stripped; Unicode whitespace collapsed to one space",
            "characterCountInManifest": plan.source_char_count,
            "nonWhitespaceCharactersRendered": plan.rendered_char_count,
            "chunkCount": len(plan.chunks),
            "displayLineCount": len(plan.lines),
            "charsPerLine": args.chars_per_line,
            "fixedSlots": [f"row-{row}" for row in range(args.lines_on_screen)],
            "linesOnScreen": args.lines_on_screen,
            "contextLinesBefore": args.lines_on_screen // 2,
            "contextLinesAfter": args.lines_on_screen // 2,
            "activeLinePlacement": "center",
            "readAheadLines": args.lines_on_screen // 2,
        },
        "render": {
            "output": output_path,
            "ass": ass_path,
            "durationSeconds": plan.duration_s,
            "fullSourceDurationSeconds": sum(chunk.frame_count / chunk.sample_rate for chunk in plan.chunks),
            "durationLimitSeconds": duration_limit_s,
            "width": args.width,
            "height": args.height,
            "fps": args.fps,
            "fontName": args.font_name,
            "fontSize": args.font_size,
            "crf": args.crf,
            "audioBitrate": args.audio_bitrate,
            "elapsedSeconds": time.time() - started,
        },
        "segmentation": segmentation,
        "delivery": {
            "mode": "streamable" if args.streamable else "single-file",
            "audioArtifact": audio_artifact,
        },
        "verification": {
            "manifestValidationRequired": not args.allow_unvalidated,
            "streamProbe": streams,
            "pixelSweep": sweep,
        },
    }
    report_path.write_text(json.dumps(_json_safe(report), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Render validated manifest-backed Qwen TTS audio with a Chinese karaoke sweep"
    )
    parser.add_argument("--manifest", required=True, help="validated batch manifest.json")
    parser.add_argument("--audio", help="combined WAV; defaults to manifest directory/combined.wav")
    parser.add_argument("--output-dir", help="output directory; defaults to manifest directory/audiobook-video")
    parser.add_argument("--output-name", default="audiobook-sweep.mp4")
    parser.add_argument("--duration-limit", type=float, help="render only the first N seconds for a proof")
    parser.add_argument("--chars-per-line", type=int, default=DEFAULT_CHARS_PER_LINE)
    parser.add_argument(
        "--lines-on-screen",
        type=int,
        default=DEFAULT_LINES_ON_SCREEN,
        help="odd number of visible reading rows (3-11); the active line is centered",
    )
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--fps", type=int, default=DEFAULT_FPS)
    parser.add_argument("--font-name", default=DEFAULT_FONT)
    parser.add_argument("--font-size", type=int, default=DEFAULT_FONT_SIZE)
    parser.add_argument("--crf", type=int, default=DEFAULT_CRF)
    parser.add_argument("--audio-bitrate", default=DEFAULT_AUDIO_BITRATE)
    parser.add_argument(
        "--segment-duration",
        type=float,
        default=DEFAULT_SEGMENT_DURATION,
        help="target seconds per parallel video-only segment; 0 disables chunking",
    )
    parser.add_argument(
        "--segment-workers",
        type=int,
        default=DEFAULT_SEGMENT_WORKERS,
        help="number of parallel ffmpeg video segment workers",
    )
    parser.add_argument(
        "--keep-segments",
        action="store_true",
        help="retain intermediate ASS, video segment, join-list, and joined-video files",
    )
    parser.add_argument(
        "--streamable",
        action="store_true",
        help=(
            "write a cached AAC/M4A, fragmented MP4, and HLS fMP4 package; "
            "retains video segments for resumable assembly"
        ),
    )
    parser.add_argument("--ffmpeg")
    parser.add_argument("--ffprobe")
    parser.add_argument("--timeout", type=float, default=None, help="ffmpeg timeout in seconds")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--allow-unvalidated", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    if args.width <= 0 or args.height <= 0 or args.fps <= 0 or args.font_size <= 0:
        parser.error("width, height, fps, and font-size must be positive")
    if args.lines_on_screen < 3 or args.lines_on_screen > 11 or args.lines_on_screen % 2 == 0:
        parser.error("lines-on-screen must be an odd number between 3 and 11")
    if args.segment_duration < 0:
        parser.error("segment-duration must be zero or positive")
    if args.segment_workers <= 0:
        parser.error("segment-workers must be positive")
    if args.streamable and args.segment_duration <= 0:
        parser.error("--streamable requires a positive --segment-duration")
    try:
        report = render(args)
    except RenderError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(
        f"wrote {report['render']['output']} "
        f"({report['render']['durationSeconds']:.3f}s, "
        f"{report['timing']['displayLineCount']} display lines, "
        f"pixel sweep passed)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
