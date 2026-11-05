# desklink

A small bridge that lets the LocalIntelligence phone app see and answer the coding agents running on this desktop. It is one Python file, stdlib only, and it starts only when you run it.

## What it does

- Lists the agents (herdr panes, or tmux panes).
- Reads an agent's terminal text.
- Sends a message (through `herdr agent prompt`, or a tmux paste) or a single key (1, 2, 3, y, n, enter, esc, up, down).
- Streams the terminal text over server-sent events while the phone has a view open.
- Waits for an agent to reach a state (herdr only).
- Sends a notification to an ntfy topic when a herdr agent finishes or needs an answer.

It never runs anything on its own, never runs a shell, and sends no terminal text in notifications.

## Endpoints

All need the identity header (see "Who can use it"). Errors are `{"error": "<code>", "message": "..."}`.

| Request | Answer |
| --- | --- |
| `GET /v1/health` | `{"ok": true, "backend": "tmux"}` |
| `GET /v1/agents` | `{"agents": [{"target", "name", "status", "state_seq", "cwd"}]}` |
| `GET /v1/agents/<target>/text` | `{"target", "text", "state_seq"}` (last 200 lines) |
| `POST /v1/agents/<target>/prompt` `{"text"}` | `{"ok": true}`; 409 `agent_blocked` on herdr when the agent waits for an answer |
| `POST /v1/agents/<target>/keys` `{"keys": [...], "expect_seq"}` | `{"ok": true}`; 409 `stale` when `expect_seq` is not the current number |
| `GET /v1/agents/<target>/stream` | server-sent events: `event: text` with `{"text", "state_seq", "status"}`, `: keep-alive` comments, `event: error` |
| `GET /v1/agents/<target>/wait?until=idle,done&timeout_ms=60000` | `{"target", "status"}`; 408 `timeout`; 501 `not_supported` on tmux |

`target` is a herdr pane id such as `w1M:p1`, or a tmux pane id such as `%3` (percent-encode it in the path). Real sample answers are in `samples/` (taken from a tmux run).

## Run it

1. Start it with your Tailscale login (the account the phone uses):

   ```
   python3 desktop-bridge/desklink.py --login you@example.com --host mymachine.tailnet-name.ts.net --backend herdr
   ```

   Options: `--port 8787` (default), `--ntfy-url https://ntfy.sh/<your-topic>` (optional), `--backend tmux` for tmux, `--tmux-socket NAME` for a named tmux server (`tmux -L NAME`), `--herdr-socket PATH` (default `$HERDR_SOCKET_PATH`, else `~/.config/herdr/herdr.sock`).

   `--host` is the name tailscale serve answers on. Tailscale may forward the Host header unchanged, so without
   `--host` a request can be refused with 403. The bridge prints the refused Host to stderr when that happens:
   if it differs from the name you gave, set `--host` to the printed value.

2. Publish it on your tailnet with tailscale serve. Check the current syntax with `tailscale serve --help`; it forwards HTTPS on your tailnet to the loopback port:

   ```
   tailscale serve --bg 8787
   ```

   The bridge listens on 127.0.0.1 only. Do not bind it to 0.0.0.0, and do not use `tailscale funnel`, which would publish it to the internet.

3. In the app, open Settings, then My desktop, and enter the `https://` address that tailscale serve prints. In the chat, type a question and tap Ask my desktop, choose the agent, and its reply shows in the agent view.

Stop it with Ctrl+C. Nothing restarts it.

## Who can use it

Every request must carry a `Tailscale-User-Login` header that matches `--login` (compared without regard to upper or lower case). Tailscale serve adds this header itself. Requests with no header, an empty one, another login, a browser `Origin` header, or a `Host` the bridge does not expect are refused with 403. There is no bypass switch and no option that turns the check off, so a request from 127.0.0.1 without the header is refused too.

Known limits:
- Tailscale's own documentation warns that a client who reaches the service without going through serve can set these headers. The bridge listens on loopback only, so this needs local code on this desktop. Anything running as you on this desktop can already reach the agents.
- Tailscale does not fill the identity header for tagged devices, so those requests are refused.

## Backends

- **herdr** (default). Uses `herdr agent list`, `read`, `prompt`, `send-keys` and `wait` (checked against the real CLI: `read --format text` prints the plain pane text, `wait` prints the agent record, errors come as JSON on stderr). Status changes come from herdr's socket API, `events.subscribe`, with `pane.agent_status_changed` for each pane and `pane.created`, `pane.closed` and `pane.agent_detected` to pick up new panes. The same events feed the done and needs-you notifications, and wake an open live view at once. herdr raises no event for plain output, so an open live view also re-reads the pane every 3 s while the agent works and every 5 s otherwise. There is no timer when nothing is open.
- **tmux**. Uses `list-panes`, `capture-pane`, `load-buffer` with `paste-buffer`, and `send-keys`. tmux does not report agent status, so every agent shows `unknown`, `wait` is not supported, no notifications are sent, and a live view re-reads every 1.5 s.

## Safety for approvals

The phone sends the sequence number it showed with each key press. If the agent has changed since, the bridge refuses with `stale`, so a tap cannot answer a question the user did not read. For herdr the number is `state_change_seq`. For tmux it is a checksum of the text last read.

## Tested

`./selftest.sh` repeats the tmux test: it starts a private tmux server with one `cat` session named `desklink-test`, runs the bridge on a free loopback port, checks the refusal cases and every endpoint (list, read, prompt, keys, wait, stream, loopback-only bind), then removes everything. It never touches herdr.

The herdr backend was run read-only against a live herdr (list, read, wait, stream, the event subscription, and 404 on a missing pane). Sending to herdr (`prompt`, `send-keys`) was checked by reading the code and `herdr agent prompt --help`, and by a missing-pane call, never by sending to a real agent. Not yet seen: a real status-change event arriving through `events.subscribe` (the subscription starts and stays open), and the phone app talking to the bridge through `tailscale serve`.
