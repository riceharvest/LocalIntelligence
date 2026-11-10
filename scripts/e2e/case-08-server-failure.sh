#!/usr/bin/env bash
# Case 8: the download server fails: 503 on the listing, 503 on the file, 404 on the listing. Each gives
# a plain sentence, and Try again works once the server is back.
source "$(dirname "$0")/lib.sh"
case_begin 08 "Server failure (5xx, 404): plain sentence, Try again works"

fresh_install
launch
wait_text "Get started" 40 || fail "no first-run panel"
# Each failure mode and a word its sentence must carry. A 5xx is the site's trouble; a 404 is not a dead connection.
for pair in "status=503&where=api|download site" "status=503&where=file|download site" "status=404&where=api|find the assistant" "status=404&where=file|find the assistant"; do
  mode="${pair%%|*}"
  want="${pair##*|}"
  hubctl "reset=1&$mode"
  hub_log_clear
  if on_screen "Get started"; then tap_text "Get started"; else tap_text "Try again"; fi
  if wait_text "^Allow$" 4; then tap_text "Allow"; fi
  wait_text "Try again" 60 || fail "[$mode] no Try again after a server failure"
  shown="$(screen_text | grep -v '^Settings$\|^Message$\|Ask anything\|Attach\|Record\|hands-free\|^Send$' | tr '\n' '|')"
  echo "  [$mode] shown: $shown"
  assert_no_banned_words
  screen_text | grep -qi "$want" || fail "[$mode] the sentence should say '$want': $shown"
  screen_text | grep -Eqi "HTTP|exception|[45][0-9][0-9]|null|Error" && fail "[$mode] a technical word leaked: $shown"
  [[ -n "$(app_pid)" ]] || fail "[$mode] the app died"
done
hubctl "reset=1&rate=40000000"
tap_text "Try again"
wait_percent 1 60 >/dev/null || fail "Try again did not start the download once the server was back"
case_end "four failure modes shown plainly; recovered"
