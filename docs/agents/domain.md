# Domain docs

Engineering skills use this repo's domain documentation when exploring the codebase.

## Before exploring

Read:

- `CONTEXT.md` at the repo root
- Relevant ADRs under `docs/adr/`

If either location does not exist, proceed silently. Create domain documentation lazily when terminology or decisions are resolved.

## File structure

This repo uses a single-context layout:

    /
    ├── CONTEXT.md
    ├── docs/adr/
    └── src/

## Use glossary vocabulary

When output names a domain concept, use the term defined in `CONTEXT.md`. Do not drift towards synonyms that the glossary excludes.

If a required concept is absent, reconsider whether the term belongs to the project or note the gap for domain modelling.

## Flag ADR conflicts

Explicitly surface output that contradicts an existing ADR instead of silently overriding it.
