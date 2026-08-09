---
name: okf
description: Create, maintain, validate, and review Google Open Knowledge Format bundles in repositories. Use when documenting a codebase as linked Markdown concepts, updating an OKF bundle, checking YAML frontmatter/index/log conformance, preserving provenance and lifecycle metadata, or hardening an agent workflow around OKF v0.2.
---

# Open Knowledge Format

Use this skill for the repository’s OKF bundle at [`docs/okf/`](../../docs/okf/). The bundle is the source of truth; `AGENTS.md` is only the entrypoint that tells agents to read it.

## Core contract

- Treat every non-reserved `.md` file as one concept. It must be UTF-8 Markdown with a YAML frontmatter block and a non-empty `type`.
- Treat `index.md` and `log.md` as reserved files. Indexes have no frontmatter except the bundle-root `okf_version`; logs are date-grouped prose with newest entries first.
- Use standard Markdown links for concept relationships. Prefer bundle-root absolute links such as `/architecture/project-architecture.md` for stable cross-links; use relative links for neighboring concepts.
- Keep structured, queryable metadata in frontmatter and explanatory detail, schemas, procedures, and examples in the body.
- Preserve unknown frontmatter keys when editing. Do not invent a fixed type taxonomy.
- Record sources in `sources`; record production with `generated`; record review with `verified`; use `status` and `stale_after` when lifecycle matters.
- Derive trust rather than asserting it: no `verified` means unverified, machine/process-only verification is machine-confirmed, and any `human:<id>` verification is human-reviewed.
- Separate provenance from verification. A source explains where a claim came from; verification explains who or what checked it.

## Repository workflow

1. Read `docs/okf/index.md`, then only the relevant family index and concepts.
2. Inspect authoritative repository files before writing claims. Mark uncertainty explicitly as inference or limitation.
3. Add or update the smallest concept set that captures the new knowledge. Link concepts deliberately; do not create a monolithic orientation file.
4. Add `sources` entries for external material and repository paths where useful. Use stable `id` values when body claims need footnote attribution.
5. Update the applicable `index.md` and append a dated entry to `docs/okf/log.md` for material changes.
6. Run `powershell -ExecutionPolicy Bypass -File skills/okf/scripts/validate-okf.ps1 -BundlePath docs/okf` before handing off.

## Safe enrichment rules

When an agent enriches an existing concept, preserve authoritative sections and facts. In particular, do not shrink a schema, delete citations, or replace verified content merely because a later pass has less context. Prefer additive edits and flag conflicts for human review. A source refresh may remove obsolete facts only when the source-of-truth pass explicitly establishes that removal.

## Work planning model

Use OKF to record the operating plan for multi-agent work:

- `EPIC` is the outcome, scope, and success boundary.
- `Story` is a linked user- or system-visible slice of the EPIC.
- `Task` is an executable unit inside a Story, with an owner, acceptance checks, and evidence.
- `Milestone` carries the delivery `Wave`; link Stories and Tasks to it explicitly.

Keep these as linked concepts with stable IDs and explicit status (`planned`, `ready`, `in_progress`, `blocked`, `review`, `done`, or `cancelled`). Do not mark a parent complete while required children remain blocked or unaccepted. Record dependencies and non-goals in the body, and keep the actual plan in OKF rather than transient chat text.

The orchestrating session owns the user conversation, plan, delegation, integration, and final handoff. Subagents receive bounded Stories or Tasks and return evidence, changed files, validation results, risks, and recommendations. They must not broaden scope or silently change the EPIC or Wave.

## Google reference implementation

Use Google’s [OKF specification](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md) as the normative format reference and its [reference-agent implementation](https://github.com/GoogleCloudPlatform/knowledge-catalog/tree/main/okf) as a design reference. The sample implementation uses a parser/document model, bundle tools, generated indexes, trust/staleness helpers, and tests for round-tripping and destructive enrichment guards. Do not copy its BigQuery or Gemini-specific runtime into this project; adapt the document and bundle discipline to repository code knowledge.

## Scope boundary

This skill maintains knowledge artifacts. It does not replace application code, native build scripts, or the project’s Kotlin tests. When code changes alter an architectural fact, update the affected OKF concept after verifying the implementation.
