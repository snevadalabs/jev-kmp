---
description: "Fan out several tickets from the local tracker through Herdr: one worktree + one panel + one /resolve-issue agent each, panels named #<n>, each panel closed automatically once its worktree is closed. Argument: ticket numbers, space or comma separated."
---

This repo's tracker is local markdown, not GitHub — `/resolve-issue` reads `.scratch/jev-kmp-sdk/issues/<n>-*.md`, so the gates below are file checks, not `gh` queries.

Split `$ARGUMENTS` on commas and whitespace into ticket numbers, then resolve them in parallel, each in its own Herdr worktree and its own panel in the current workspace, by running `/resolve-issue <n>` in one agent per panel. No numbers → say so and stop; never guess a set.

## Gate (report the failure and stop)

- `test "${HERDR_ENV:-}" = 1` — false means this is not a Herdr-managed pane; stop rather than drive Herdr from outside.
- `.pi/prompts/resolve-issue.md` exists — the per-ticket agents depend on it.
- The tracker is tracked by git (`git ls-files --error-unmatch .scratch/jev-kmp-sdk/map.md`). If it is not, worktrees cut from `origin/main` will not contain the tickets and every agent will fail on step 1.
- Per ticket `<n>`: `Status: open`, all `Blocked by:` ids `resolved`, and no `issue-<n>-*` branch on the remote. Any gate fails → drop that one ticket and keep the rest; none survive → report and stop.
- **Same-file collisions** → separate waves; never one wave. A shared *directory* is fine when each ticket writes a different file (that is what the ADR numbering rule in `/resolve-issue` is for). The hotspots in this repo are `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `docs/adr/`, and `map.md`.

## Fan out

`herdr worktree create` refuses to run from a linked worktree (`linked_worktree_source`); it must start from the repo's parent checkout:

    repo_root=$(herdr worktree list --cwd "$PWD" | python3 -c \
      'import sys,json;print(json.load(sys.stdin)["result"]["source"]["repo_root"])')
    GIT_TERMINAL_PROMPT=0 git -C "$repo_root" fetch origin --quiet

For each ticket `<n>`, with `<slug>` = up to 4 kebab-case words from its title:

    out=$(herdr worktree create --cwd "$repo_root" --branch "issue-<n>-<slug>" \
            --base origin/main --label "issue-<n>" --no-focus)
    pane=$(printf '%s' "$out" | python3 -c 'import sys,json;print(json.load(sys.stdin)["result"]["root_pane"]["pane_id"])')
    wt=$(printf '%s' "$out" | python3 -c 'import sys,json;print(json.load(sys.stdin)["result"]["worktree"]["path"])')
    herdr agent start issue-<n> --kind pi --pane "$pane" --timeout 90000
    herdr agent prompt issue-<n> "/resolve-issue <n>"

Then pull that panel into this workspace and name it after its ticket. `herdr pane move` into an existing workspace needs `--tab` + `--split` + `--target-pane` (`--workspace` alone is rejected): the anchor is `$HERDR_PANE_ID` for the first panel, then whichever pane the Layout rule below splits off — it is required, `pane move` will not guess it. Read the surviving id from `.result.move_result.pane.pane_id`, since a moved pane gets a new id:

    new_pane=$(herdr pane move "$pane" --tab "$HERDR_TAB_ID" --split <right|down> \
                 --target-pane <anchor> | python3 -c \
                 'import sys,json;print(json.load(sys.stdin)["result"]["move_result"]["pane"]["pane_id"])')
    herdr pane rename "$new_pane" "#<n>"

Layout, up to 4 tickets — a 3×2 grid, your own pane holding the top-left, at most three same-direction splits per column: the first two panels `--split right` off `$HERDR_PANE_ID` (reading order across the top), the third `--split down` off `$HERDR_PANE_ID`, the fourth `--split down` off the first panel you moved. Pass `--no-focus` on every command that accepts it (`herdr worktree create`; `pane move` does not) so the human's focus never moves. Herdr closes each empty per-worktree workspace on its own — do not close them yourself.

## Arm the panel reaper

A resolve-issue agent removes its own worktree in step 6, so "worktree closed" is the completion signal. Write `/tmp/herdr-ticket-reaper.sh` with one `name|pane|worktree` row per ticket: the agent name, the **post-move** pane id (the one you just renamed — the pre-move id is stale), and the `$wt` path you extracted above:

```bash
#!/usr/bin/env bash
LOG=/tmp/herdr-ticket-reaper.log
log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >>"$LOG"; }
TARGETS=(
  "issue-<n>|<new_pane>|<worktree path from .result.worktree.path>"
)
deadline=$(( $(date +%s) + 43200 ))            # give up after 12h
log "reaper start pid=$$"
while :; do
  active=0
  for t in "${TARGETS[@]}"; do
    IFS='|' read -r name pane wt <<<"$t"
    herdr pane get "$pane" >/dev/null 2>&1 || continue   # panel already gone
    active=1
    [ -d "$wt" ] && continue                             # worktree still open
    herdr agent wait "$name" --timeout 60000 >/dev/null 2>&1 || true
    status=$(herdr agent get "$name" 2>/dev/null | python3 -c \
      'import sys,json;print(json.load(sys.stdin)["result"]["agent"]["agent_status"])' 2>/dev/null)
    [ "$status" = "working" ] && continue                # let the live turn finish
    herdr pane close "$pane" >>"$LOG" 2>&1
    log "$name: worktree closed -> closed panel $pane (status=${status:-none})"
  done
  [ "$active" -eq 0 ] && { log "all panels closed; reaper exit"; break; }
  [ "$(date +%s)" -ge "$deadline" ] && { log "deadline; reaper exit"; break; }
  sleep 20
done
```

Launch it detached — `nohup bash /tmp/herdr-ticket-reaper.sh </dev/null >/dev/null 2>&1 & disown` — and report the pid and log path. It exits when every panel is closed. A `blocked` agent (question or approval UI) never removes its worktree, so its panel intentionally stays up for the human.

## Report, then return

One table — ticket → branch → worktree path → agent name → panel id + label — plus the reaper pid. Then stop; do not wait on the agents, they report in their own panels.
