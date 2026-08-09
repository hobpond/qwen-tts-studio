---
type: Reference
title: Google Open Knowledge Format v0.2 specification
description: Canonical OKF v0.2 specification and the conformance rules applied to this repository bundle.
tags: [okf, specification, conformance]
resource: https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md
generated:
  by: human:project-maintainer
  at: 2026-08-03T00:00:00Z
verified:
  by: human:project-maintainer
  at: 2026-08-03T00:00:00Z
status: stable
sources:
  - id: google-okf-spec
    resource: https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md
    title: GoogleCloudPlatform/knowledge-catalog OKF SPEC.md
  - id: google-okf-reference-agent
    resource: https://github.com/GoogleCloudPlatform/knowledge-catalog/tree/main/okf
    title: Google OKF reference agent and sample implementation
---

# Conformance basis

The canonical specification is maintained by GoogleCloudPlatform in [`okf/SPEC.md`](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md). This bundle follows its v0.2 rules rather than treating the project’s conventions as a new OKF dialect.

# Rules applied here

- Each non-reserved Markdown file is a UTF-8 concept with YAML frontmatter and a non-empty `type`.
- `index.md` is used for progressive disclosure and `log.md` records dated updates; neither is treated as an ordinary concept.
- Concepts use standard Markdown links to express relationships. The bundle prefers stable bundle-root links for concept-to-concept traversal.
- `title`, `description`, `tags`, `resource`, `sources`, `generated`, `verified`, `status`, and `stale_after` remain optional metadata families; unknown keys must be preserved.
- Trust is derived from verification actors, not asserted as an opaque score. Missing verification remains consumable but unverified.

# Implementation reference

Google’s sample implementation separates document parsing, bundle tools, source ingestion, web enrichment, index generation, and viewer concerns. Its tests cover frontmatter round-tripping, type validation, trust-tier normalization, staleness boundaries, and guards against schema/citation shrinkage during enrichment. The project-local [`skills/okf/SKILL.md`](../../../skills/okf/SKILL.md) and [`okf_bundle.py`](../../../skills/okf/scripts/okf_bundle.py) adapt those ideas to repository documentation without importing BigQuery or Gemini-specific behavior.
