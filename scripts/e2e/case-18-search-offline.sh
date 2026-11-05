#!/usr/bin/env bash
# Case 18: search with no connection. Airplane mode on, the model is sent to web.search (scripted call), and the
# real model reads the result. The person gets a plain sentence or an answer from memory, no raw error, and the
# run is quick (the chain gives up at once on "no such host"). Back online, the same question searches again.
source "$(dirname "$0")/lib.sh"
case_begin 18 "Search offline: plain sentence, answer from memory"
trap 'airplane_off >/dev/null 2>&1; search_routes_off; script_set ""; shub_stop' EXIT

install_and_ready
shub_start || fail "no fake search services"
Q="Search for the capital of France"
SEARCH='<tool name="web.search">{"query":"capital of France"}</tool>'

airplane_on
sleep 6
wifi_is_default && fail "airplane mode is on but Wi-Fi is still the default network"
# No routes here: the four real services are asked, with no network under them.
search_routes_off
t0="$(now)"
line="$(scripted_ask "$Q" "$SEARCH|||@real" 180)"
secs=$(( $(now) - t0 ))
echo "  done in ${secs}s: $(echo "$line" | head -c 500)"
steps="$(tool_steps)"
echo "  step: $(echo "$steps" | head -c 300)"
[[ "$steps" == *"web.search | False"* ]] || fail "offline: web.search did not report a failure: $steps"
[[ "$steps" == *"Search is not available right now"* ]] || fail "offline: the tool did not say the one plain sentence: $steps"
[[ "$(echo "$line" | json_field outcome)" == answer ]] || fail "offline: no answer: $line"
answer="$(last_answer)"
echo "  answer: $answer"
[[ -n "$answer" && "$answer" != null ]] || fail "offline: empty answer"
echo "$answer" | grep -Eqi "Paris" || fail "offline: the answer is not a reply from memory (no Paris): $answer"
echo "$answer" | grep -Eqi "UnknownHost|Exception|captcha|bot check|HTTP [0-9]{3}|search service" && fail "offline: a raw error reached the person: $answer"
assert_no_banned_words
(( secs < 60 )) || fail "offline: the run took ${secs}s"
airplane_off
for i in $(seq 1 60); do wifi_is_default && break; sleep 2; done
wifi_is_default || fail "the network did not come back after airplane mode"
shubctl "reset=1"; search_routes_on
line="$(scripted_ask "Search for the capital of Testland" "$(tool_call web.search '{"query":"capital of Testland"}')|||@echo" 120)"
tool_steps | grep -q "web.search | True" || fail "back online: the search did not work again: $(tool_steps)"
case_end "offline: tool says the plain sentence, answer from memory (Paris) in ${secs}s, nothing raw shown; online again: search works"
