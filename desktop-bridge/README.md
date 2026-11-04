# desklink

A small bridge that lets the LocalIntelligence phone app see and answer the coding agents running on this desktop. It is one Python file, stdlib only, and it starts only when you run it.

## What it does

- Lists the agents (herdr panes, or tmux panes).
- Reads an agent's terminal text.
- Sends a message (through `herdr agent prompt`, or a tmux paste) or a single key (1, 2, 3, y, n, enter, esc, up, down).
- Streams the terminal text over server-sent events while the phone has a view open.
- Sends a notification to an ntfy topic when a herdr agent finishes or needs an answer.

It never runs anything on its own, never runs a shell, and sends no terminal text in notifications.

## Run it

1. Start it with your Tailscale login (the account the phone uses):

   ```
   python3 desktop-bridge/desklink.py --login you@example.com --host mymachine.tailnet-name.ts.net --backend herdr
   ```

   Options: `--port 8787` (default), `--ntfy-url https://ntfy.sh/<your-topic>` (optional), `--backend tmux` for tmux.

   `--host` is the name tailscale serve answers on. Tailscale may forward the Host header unchanged, so without
   `--host` a request can be refused with 403. The bridge prints the refused Host to stderr when that happens:
   if it differs from the name you gave, set `--host` to the printed value.

2. Publish it on your tailnet with tailscale serve. Check the current syntax with `tailscale serve --help`; it forwards HTTPS on your tailnet to the loopback port:

   ```
   tailscale serve --bg 8787
   ```

   The bridge listens on 127.0.0.1 only. Do not bind it to 0.0.0.0, and do not use `tailscale funnel`, which would publish it to the internet.

3. In the app, open Settings, then My desktop, and enter the `https://` address that tailscale serve prints.

Stop it with Ctrl+C. Nothing restarts it.

## Who can use it

Every request must carry a `Tailscale-User-Login` header that matches `--login` exactly. Tailscale serve adds this header itself. Requests with a browser `Origin` header, or with a `Host` the bridge does not expect, are refused.

Known limits:
- Tailscale's own documentation warns that a client who reaches the service without going through serve can set these headers. The bridge listens on loopback only, so this needs local code on this desktop. Anything running as you on this desktop can already reach the agents.
- Tailscale does not fill the identity header for tagged devices, so those requests are refused.

## Backends

- **herdr** (default). Uses `herdr agent list`, `read`, `prompt`, `send-keys` and `wait`. The agent status and a state sequence number come from `herdr agent list`. Done and needs-you notifications come from `herdr agent wait`, with one waiting thread per agent. There is no timer. An agent that appears after startup is picked up the next time the phone lists agents, because the bridge does not poll for new panes.
- **tmux**. Uses `list-panes`, `capture-pane`, `load-buffer` with `paste-buffer`, and `send-keys`. tmux does not report agent status, so every agent shows `unknown` and no notifications are sent.

## Safety for approvals

The phone sends the sequence number it showed with each key press. If the agent has changed since, the bridge refuses with `stale`, so a tap cannot answer a question the user did not read. For herdr the number is `state_change_seq`. For tmux it is a checksum of the last 200 lines of text.

## Not verified here

This file was compiled with `python3 -m py_compile` only. It has not been run against live herdr or tmux, and the herdr output shapes for `agent read` and `agent wait` are taken from the CLI help and the API schema, not from a real run.
