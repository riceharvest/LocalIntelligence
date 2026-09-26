#!/usr/bin/env bash
# Gate: no credential-like string in the repository.
#
# THE DEFECT THIS CATCHES — this repository is PUBLIC. A token, an API key, a
# private key, or a hardcoded password pushed to a public remote is compromised
# the moment it lands, and the credential has to be treated as burned and
# rotated. Nothing in a build catches that: the commit that adds it is green,
# and it stays green forever.
#
# SCOPE, STATED PLAINLY, because a secret scanner that cries wolf gets deleted:
# this looks for (a) well-known credential formats with their real shapes, and
# (b) an assignment of a long literal to a credential-named identifier. It is a
# tripwire, not entropy analysis. It cannot find a secret in an unusual variable
# name, one split across lines, or base64'd inside something else. It is here to
# stop the accident, not to make a claim about the whole history.
#
# WHAT IT DELIBERATELY DOES NOT DO:
#   * no dependency, no network call, no gitleaks/trufflehog action
#   * no baseline file, because a baseline is where secret scanners go to be
#     switched off
#   * it does not exempt the commit that introduced it, because "grandfathered"
#     is how a leaked key stays alive
#
# The repo must be clean for this to pass. Verified: it is.
set -euo pipefail

fail=0

# Only tracked files, and never the build outputs. `git ls-files` is the right
# root: it cannot be fooled by a file that is not in the commit.
tracked=$(git ls-files -z | tr '\0' '\n')
[ -n "$tracked" ] || { echo "::error::git ls-files returned nothing; run this inside a checkout"; exit 1; }

# --- (a) Known credential formats, matched against their documented shapes ---
# Every pattern here is anchored on the vendor's real format/length, so a random
# hex string or a git SHA cannot trip it.
formats='AKIA[0-9A-Z]{16}
ghp_[A-Za-z0-9]{36}
gho_[A-Za-z0-9]{36}
ghu_[A-Za-z0-9]{36}
ghs_[A-Za-z0-9]{36}
github_pat_[A-Za-z0-9_]{22,}
sk-[A-Za-z0-9]{32,}
xox[baprs]-[A-Za-z0-9-]{10,}
AIza[0-9A-Za-z_-]{35}
-----BEGIN [A-Z ]*PRIVATE KEY-----
eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}
ssh-rsa AAAA[0-9A-Za-z+/]{100,}'

n=$(printf '%s\n' "$formats" | grep -c . || true)
echo "== scanning $(printf '%s\n' "$tracked" | wc -l) tracked files against $n known credential formats =="

# mapfile, not `while read <<EOF`. The heredoc form performs command substitution
# on its body, which mangles patterns containing backslashes and dashes; that
# silently disabled the private-key and JWT patterns until this was replaced.
# Verified by injecting each format below and confirming the gate fires on all
# of them.
mapfile -t PATTERNS < <(printf '%s\n' "$formats")

for pat in "${PATTERNS[@]}"; do
  [ -n "$pat" ] || continue
  # git grep -I skips binaries; -E for extended regex; -e is mandatory, not
  # decoration: a pattern starting with "-" (the "-----BEGIN ... PRIVATE
  # KEY-----" format) is otherwise parsed by git grep as a command-line option,
  # the search matches nothing, and the private-key check passes on a
  # committed private key. Verified by injecting a key and watching this gate
  # stay green before -e was added.
  if hits=$(printf '%s\n' "$tracked" | tr '\n' '\0' | \
            xargs -0 -r git grep -nIE -e "$pat" -- 2>/dev/null); then
    echo "::error::a credential-shaped string matching /${pat}/ is committed to a PUBLIC repository. Treat it as compromised and rotate it now."
    printf '%s\n' "$hits" | sed 's/^/  /'
    fail=1
  fi
done

# --- (b) A long literal assigned to a credential-named identifier -----------
# Catches the case where someone's own key does not match a vendor format.
# Requires 20+ characters, which keeps it clear of `password = ""` and of the
# many `token = ...` variables in this repo that hold a counter or a name, not
# a secret.
assign_re='(api[_-]?key|apikey|secret|secret[_-]?key|password|passwd|access[_-]?token|auth[_-]?token|client[_-]?secret|private[_-]?key)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{20,}["'"'"']'
if hits=$(printf '%s\n' "$tracked" | tr '\n' '\0' | \
          xargs -0 -r git grep -nIE "$assign_re" -- 2>/dev/null); then
  # Do not fire on a placeholder. A real placeholder is obviously fake and has
  # no entropy; a real key is not obviously fake.
  real=$(printf '%s\n' "$hits" | grep -viE 'your[_-]?|<[a-z_]+>|xxx|placeholder|example|dummy|redacted|\$\{|\$\(|TODO|CHANGEME|insert[_-]?here' || true)
  if [ -n "$real" ]; then
    echo "::error::a credential-named variable is assigned a 20+ character literal in a PUBLIC repository:"
    printf '%s\n' "$real" | sed 's/^/  /'
    fail=1
  fi
fi

# --- (c) A personal absolute path, which leaks a username and a layout ------
# Same class of accident as a secret and the same consequence in a public repo:
# it exposes the author's home directory, their username, and their disk layout.
# This repository already hardcodes $HOME in CI, which is fine — the check is
# for a specific person's real home, not for the shell variable.
leak_re='/home/[a-z][a-z0-9_-]*/|/Users/[A-Za-z][A-Za-z0-9_-]*/|/mnt/ssd/[a-z]+ [a-z]'
if hits=$(printf '%s\n' "$tracked" | tr '\n' '\0' | \
          xargs -0 -r git grep -nIE "$leak_re" -- 2>/dev/null); then
  # This file necessarily contains the patterns it searches for, so it would
  # always match itself and the gate could never pass. Excluding it is not a
  # loophole: it is the definition of the check, and it holds no credential.
  # Any OTHER file that matches is reported.
  real=$(printf '%s\n' "$hits" | grep -v '\.github/scripts/check-no-secrets\.sh' || true)
  if [ -n "$real" ]; then
    echo "::error::a personal absolute path is committed. In a public repository this leaks a username and a disk layout:"
    printf '%s\n' "$real" | sed 's/^/  /'
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "OK: no credential-shaped string and no personal absolute path in any tracked file."
