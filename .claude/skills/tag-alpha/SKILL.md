---
name: tag-alpha
description: Create and push a timestamped alpha tag for the Nucleus 2.0 branch in the format v2.0.0-alpha-YYYYMMDDHHMM. Use when the user asks to "tag", "release alpha", "publish alpha", "cut an alpha", or similar on this project.
---

# Tag alpha — Nucleus 2.0

Creates a timestamped alpha tag on the current HEAD and pushes it to `origin`.

## Format

`v2.0.0-alpha-YYYYMMDDHHMM` — e.g. `v2.0.0-alpha-202605151813` for May 15 2026, 18:13.

Timestamp components come from `date -u +%Y%m%d%H%M` (UTC, no separators, 12 chars).

## Procedure

1. **Verify branch is `nucleus-2.0`** — abort with a clear message if not. Other branches must not produce `v2.0.0-alpha-*` tags.
2. **Verify working tree is clean** — `git status --porcelain` empty. If dirty, ask the user whether to commit first or abort. Never tag a dirty tree.
3. **Verify HEAD is in sync with `origin/nucleus-2.0`** — fetch first (`git fetch origin nucleus-2.0`). If local is behind, abort and tell the user to pull. If local is ahead, ask whether to push first (a tag on an unpushed commit is useless to others).
4. **Generate the timestamp** with `date -u +%Y%m%d%H%M`.
5. **Check the tag doesn't already exist** — `git tag -l v2.0.0-alpha-<ts>`. If it does (clock raced), wait a minute or use the next minute.
6. **Create an annotated tag**:
   ```bash
   git tag -a "v2.0.0-alpha-<ts>" -m "v2.0.0-alpha-<ts>"
   ```
   Annotated (not lightweight) because the published history uses annotated tags.
7. **Push the tag**:
   ```bash
   git push origin "v2.0.0-alpha-<ts>"
   ```
8. **Report the tag name and commit SHA** back to the user.

## Hard rules

- Never tag `main` or any branch other than `nucleus-2.0` with this format.
- Never overwrite an existing tag. If asked to retag the same commit, refuse and explain — the user should bump to the next minute or use a different scheme.
- Never push with `--force` or `--force-with-lease` for tags.
- Never add `Co-Authored-By` or AI attribution to the tag message (per project's CLAUDE.md).
- Tag message body is just the tag name itself — concise, matches the existing convention.

## When NOT to use this skill

- Stable releases (`v2.0.0`, `v2.0.1`) — those need a different process with changelog, signing, etc.
- Tagging from `main` — `main` uses a separate versioning scheme.
- Backporting tags onto old commits — this skill always tags `HEAD`.
