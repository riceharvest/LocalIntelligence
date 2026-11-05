#!/usr/bin/env bash
# Case 17: the search chain. Each service is a stand-in on the host (searchhub.py) that the debug build's
# `searchroute` points the four services at, so each rung can be forced: DuckDuckGo, then Bing, then Brave,
# then Wikipedia. Forced: a bot check, an HTTP error, an empty page, and a service that hangs. Whatever
# happens, the next one answers inside the 20 s budget, and when none does the person hears one plain sentence.
# The model's own reply is scripted here (it is not what this case is about); the search tool, the chain, the
# policy and the transcript are the app's real code. Then: a link from a result opens with no card, and an
# address nobody searched for asks first.
source "$(dirname "$0")/lib.sh"
case_begin 17 "Search chain: each rung, hang, result link, invented address"
trap 'search_routes_off; script_set ""; shub_stop' EXIT

install_and_ready
shub_start || fail "no fake search services"
shubctl "reset=1"; shub_log_clear
Q="Search for the capital of Testland"
SEARCH='<tool name="web.search">{"query":"capital of Testland"}</tool>'

# rung NAME "ctl" EXPECT_TOWN [MAX_MS]
rung() {
  local name="$1" ctl="$2" want="$3" max="${4:-20000}" line ms steps
  echo "--- $name"
  shubctl "reset=1"; shub_log_clear
  [[ -n "$ctl" ]] && shubctl "$ctl"
  search_routes_on
  line="$(scripted_ask "$Q" "$SEARCH|||@echo")"
  echo "  done: $(echo "$line" | head -c 300)"
  [[ "$(echo "$line" | json_field outcome)" == answer ]] || fail "$name: the run did not end with an answer: $line"
  steps="$(tool_steps)"
  echo "  step: $(echo "$steps" | head -c 200)"
  [[ "$steps" == *"web.search | True"* && "$steps" == *"$want"* ]] || fail "$name: the search did not come from the expected service ($want): $steps"
  ms="$(done_total_ms "$line")"
  echo "  run took ${ms} ms; services asked: $(shub_log | awk '{print $2"="$3}' | tr '\n' ' ')"
  [[ "$ms" =~ ^[0-9]+$ ]] && (( ms <= max )) || fail "$name: took ${ms} ms, over the ${max} ms limit"
  LAST_MS="$ms"
}

rung "all fine: DuckDuckGo" "" Ddgville 8000
shub_log | grep -q " bing " && fail "Bing was asked although DuckDuckGo answered"
rung "DuckDuckGo shows a bot check: Bing" "ddg=block" Bingtown 8000
rung "DuckDuckGo error 503: Bing" "ddg=503" Bingtown 8000
rung "DuckDuckGo empty: Bing" "ddg=empty" Bingtown 8000
rung "DuckDuckGo and Bing down: Brave" "ddg=block&bing=503" Bravehaven 8000
rung "the three web pages down: Wikipedia" "ddg=block&bing=503&brave=empty" Wikipolis 8000
# A service that never answers: the app gives each 6 s and moves on, so the answer arrives inside the 20 s budget.
rung "DuckDuckGo hangs: Bing" "ddg=hang" Bingtown 15000
(( LAST_MS >= 4000 )) || fail "DuckDuckGo hangs: the run took only ${LAST_MS} ms, so the hang was not really waited on"
rung "DuckDuckGo, Bing and Brave hang: Wikipedia" "ddg=hang&bing=hang&brave=hang" Wikipolis 24000
(( LAST_MS >= 12000 )) || fail "three hangs: the run took only ${LAST_MS} ms"

echo "--- every service down"
shubctl "reset=1"; shubctl "ddg=503&bing=503&brave=503&wiki=503"; shub_log_clear; search_routes_on
line="$(scripted_ask "$Q" "$SEARCH|||@real" 180)"
echo "  done: $(echo "$line" | head -c 400)"
steps="$(tool_steps)"
[[ "$steps" == *"Search is not available right now"* ]] || fail "no plain sentence from the search tool when every service failed: $steps"
[[ "$(echo "$line" | json_field outcome)" == answer ]] || fail "every service down: the run did not end with an answer: $line"
answer="$(last_answer)"
[[ -n "$answer" && "$answer" != null ]] || fail "every service down: empty answer"
echo "$answer" | grep -Eqi "captcha|bot check|blocked|503" && fail "every service down: the answer names a block or an error code: $answer"
assert_no_banned_words
screen_text | grep -Eqi "captcha|bot check|HTTP 5|blocked" && fail "every service down: the screen names a block or an error code"

echo "--- a link from a result opens with no card"
shubctl "reset=1"; search_routes_on
line="$(scripted_ask "$Q" "$SEARCH|||$(tool_call web.fetch '{"url":"https://example.com/?r=ddg"}')|||@echo" 180)"
echo "  done: $(echo "$line" | head -c 300)"
[[ "$(echo "$line" | json_field outcome)" == answer ]] || fail "result link: the run stopped at '$(echo "$line" | json_field outcome)' (a card?): $line"
steps="$(tool_steps)"
[[ "$steps" == *"web.fetch | True"* ]] || fail "result link: web.fetch did not run: $steps"

echo "--- an address nobody searched for asks first"
line="$(scripted_ask "$Q" "$(tool_call web.fetch '{"url":"https://invented-address.example.net/page"}')|||@echo" 120)"
echo "  done: $(echo "$line" | head -c 300)"
[[ "$(echo "$line" | json_field outcome)" == awaiting_approval ]] || fail "invented address: expected a card, the run went on: $line"
wait_text "invented-address|Allow|OK" 20 || fail "invented address: no card on screen: $(screen_text | tr '\n' '|' | head -c 300)"
echo "  card: $(screen_text | tr '\n' '|' | head -c 300)"
assert_no_banned_words
dctl approve deny 60 >/dev/null
wait_run_end 60
tool_steps | grep -q "web.fetch | True" && fail "invented address: it was fetched although the person said no"
case_end "DuckDuckGo, Bing, Brave, Wikipedia each forced; bot check, 503, empty and hang all hand over inside the budget; plain sentence when all fail; result link no card; invented address asks"
