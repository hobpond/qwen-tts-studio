"""Small OKF v0.2 document and bundle model, adapted from Google's reference agent."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from urllib.parse import urlparse

import yaml


class OKFDocumentError(ValueError):
    """Raised when an OKF document cannot be parsed or validated."""


@dataclass
class OKFDocument:
    frontmatter: dict = field(default_factory=dict)
    body: str = ""
    path: Path | None = None

    @classmethod
    def parse(cls, source: str, path: Path | None = None) -> "OKFDocument":
        if not source.startswith("---\n"):
            return cls({}, source, path)
        lines = source.splitlines(keepends=True)
        try:
            closing = next(i for i, line in enumerate(lines[1:], start=1) if line.rstrip("\r\n") == "---")
        except StopIteration as exc:
            raise OKFDocumentError("unterminated YAML frontmatter") from exc
        raw = "".join(lines[1:closing])
        frontmatter = yaml.safe_load(raw) or {}
        if not isinstance(frontmatter, dict):
            raise OKFDocumentError("frontmatter must be a YAML mapping")
        return cls(frontmatter, "".join(lines[closing + 1 :]), path)

    def validate(self) -> None:
        value = self.frontmatter.get("type")
        if not isinstance(value, str) or not value.strip():
            raise OKFDocumentError("concept frontmatter requires a non-empty type")

    def serialize(self) -> str:
        if not self.frontmatter:
            return self.body
        header = yaml.safe_dump(self.frontmatter, sort_keys=False, allow_unicode=True).rstrip()
        return f"---\n{header}\n---\n{self.body}"


def normalize_verified(frontmatter: dict) -> list[dict]:
    value = frontmatter.get("verified")
    if not value:
        return []
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    return []


def trust_tier(frontmatter: dict) -> str:
    events = normalize_verified(frontmatter)
    if not events:
        return "unverified"
    if any(str(event.get("by", "")).startswith("human:") for event in events):
        return "human-reviewed"
    return "machine-confirmed"


def is_stale(frontmatter: dict, today: date | None = None) -> bool:
    raw = frontmatter.get("stale_after")
    if not raw:
        return False
    try:
        cutoff = date.fromisoformat(str(raw))
    except ValueError:
        return False
    return (today or date.today()) >= cutoff


_LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")


class OKFBundle:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.repository_root = self.root.parent.parent

    def documents(self) -> list[tuple[str, OKFDocument]]:
        result = []
        for path in sorted(self.root.rglob("*.md")):
            relative = path.relative_to(self.root).as_posix()
            result.append((relative, OKFDocument.parse(path.read_text(encoding="utf-8"), path)))
        return result

    def validate(self) -> list[str]:
        errors: list[str] = []
        if not (self.root / "index.md").exists():
            errors.append("bundle is missing root index.md")
        for relative, document in self.documents():
            reserved = Path(relative).name in {"index.md", "log.md"}
            if not reserved:
                try:
                    document.validate()
                except OKFDocumentError as exc:
                    errors.append(f"{relative}: {exc}")
            elif Path(relative).name == "index.md" and relative != "index.md" and document.frontmatter:
                errors.append(f"{relative}: nested index.md must not have frontmatter")
            for target in _LINK_RE.findall(document.body):
                errors.extend(self._validate_link(relative, target))
        return errors

    def _validate_link(self, source: str, target: str) -> list[str]:
        clean = target.split("#", 1)[0].split("?", 1)[0]
        if not clean or urlparse(clean).scheme in {"http", "https", "mailto"}:
            return []
        if clean.startswith("/"):
            bundle_target = self.root / clean.lstrip("/")
            repo_target = self.repository_root / clean.lstrip("/")
            return [] if bundle_target.exists() or repo_target.exists() else [f"{source}: unresolved link {target!r}"]
        bundle_target = (self.root / source).parent / clean
        try:
            bundle_target.resolve().relative_to(self.root)
        except ValueError:
            return []  # Repository-source links may intentionally leave the bundle.
        return [] if bundle_target.exists() else [f"{source}: unresolved link {target!r}"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate an OKF v0.2 bundle")
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    errors = OKFBundle(args.bundle).validate()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    count = sum(Path(relative).name not in {"index.md", "log.md"} for relative, _ in OKFBundle(args.bundle).documents())
    print(f"OKF validation passed: {count} concept files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
