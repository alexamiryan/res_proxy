#!/usr/bin/env bash
# PreToolUse(Bash) hook: blocks `git commit` when code, build or native files change but AGENTS.md
# doesn't, so the agent guide stays current. Bypass for a change that needs no doc update:
#   AGENTS_MD_OK=1 git commit ...
input=$(cat)
printf '%s' "$input" | grep -Eq 'git( -C [^ ]+)? commit' || exit 0
printf '%s' "$input" | grep -q 'AGENTS_MD_OK=1' && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
changed=$(git diff --cached --name-only 2>/dev/null)
# `git add … && git commit` in one command: the add hasn't run yet, so count working-tree changes too.
if printf '%s' "$input" | grep -Eq 'git( -C [^ ]+)? add|commit -[a-zA-Z]*a'; then
  changed="$changed
$(git status --porcelain 2>/dev/null | cut -c4-)"
fi

relevant=$(printf '%s\n' "$changed" | grep -E '^(android/|chrome/(src|static|test)/|chrome/[^/]*\.(mjs|json)$|native/|shared/|scripts/)' | grep -v 'package-lock\.json$' | head -5)
[ -z "$relevant" ] && exit 0
printf '%s\n' "$changed" | grep -qx 'AGENTS.md' && exit 0

{
  echo "AGENTS.md is not part of this commit, but these changed:"
  printf '  %s\n' $relevant
  echo "Review AGENTS.md (architecture, layout, build/test, invariants, gotchas) and update it in this commit."
  echo "If nothing there needs to change, rerun the commit prefixed with AGENTS_MD_OK=1."
} >&2
exit 2
