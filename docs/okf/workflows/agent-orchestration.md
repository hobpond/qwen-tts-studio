---
type: Workflow
title: Orchestrated multi-agent delivery
status: active
generated:
  by: codex
  at: 2026-08-03
verified:
  - machine: repository skill validation
sources:
  - id: repository-agent-guide
    path: /AGENTS.md
  - id: orchestration-skill
    path: /skills/agent-orchestration/SKILL.md
---

# Orchestrated multi-agent delivery

One orchestrating session owns the user conversation, the plan, delegation, integration decisions, and final handoff. Research and bounded implementation work may be delegated to subagents; their output is evidence and artifacts for the orchestrator to inspect, not an independent user-facing plan.

## Planning hierarchy

`EPIC -> Story -> Task`

An EPIC defines the outcome and scope. A Story is a linked user- or system-visible slice. A Task is an executable unit inside a Story with an owner, acceptance checks, and evidence. A Milestone carries the delivery Wave and links the Stories and Tasks that should move together.

Use stable IDs, explicit links, dependencies, non-goals, and lifecycle status. A parent cannot be `done` while required children are blocked or unaccepted. Each subagent request names its parent Story/Task, Milestone/Wave, bounded scope, expected artifact, and acceptance checks.

Subagents return changed files, evidence, validation performed, unresolved risks, and a recommendation. They do not silently broaden scope, rewrite the EPIC, change the Wave, or communicate the final result.
