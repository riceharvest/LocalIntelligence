# LocalIntelligence

**A minimal Android-native agent harness designed for small local LLMs.**

LocalIntelligence is not a framework. It is a small, auditable agent loop plus a set of Android
tools, sized so that a 1-4B parameter model running entirely on the phone can still
complete real multi-step tasks.

```
install APK
  -> import a local model
  -> ask for something
  -> the agent calls Android APIs, remembers what matters, and answers
```

**On-device by default. No telemetry, no analytics, no crash reporting.** Inference runs
on the phone. The app has no account and no server of its own, except the optional Extras
server (below). Every network feature is off until you set it up, or asks before each call.
With no key, token or address set, that feature makes no network call. Nothing is silent.

| Feature | Turned on by | Sends to | What leaves the phone |
|---|---|---|---|
| Model downloads | You pick a model | Hugging Face Hub | File requests only. Nothing is uploaded. See [docs/hf-hub-download.md](docs/hf-hub-download.md). |
| `web.fetch` | The model calls it; asks first and names the host | Any public HTTP(S) address the model names | The URL, including anything the model put in it. Loopback and private addresses are refused. |
| `web.search` | Asks first | DuckDuckGo by default (`html.duckduckgo.com`, no key). Technical: Brave, Tavily, Exa or your own SearXNG | The search words, to the provider you chose |
| `weather.get`, `wiki.search`, `places.search` | Free, no key; asks first on each call | Open-Meteo, Wikipedia, OpenStreetMap Nominatim | The place or search words. Weather uses the phone's location only when no place is given. |
| Remote chat model (Technical) | You pick it on the Models page and give its key | OpenRouter, Groq, Cerebras, DeepSeek, Together, Mistral, Google Gemini, or your own server (Custom) | The whole conversation, and any attached image or audio. Picking a local model stops it. |
| ChatGPT as the chat model | You sign in with your ChatGPT account | OpenAI | The whole conversation, and any attached image |
| Image and video generation | The OpenRouter plugin on, with your key | openrouter.ai | The prompt the model wrote |
| MCP servers | The MCP plugin on, with up to five server addresses you enter | Those servers | Each tool call's arguments, and your token if you set one. https only. |
| Home Assistant (Technical) | Your address and long-lived token | Your Home Assistant server | Service calls you approve. Entity states come back, fenced as untrusted. |
| Email (Technical) | Your address and an app password | Gmail, iCloud, Fastmail, or your own IMAP/SMTP server | `email.search` and `email.read` fetch mail. `email.send` sends after you confirm. |
| Cloud sandbox (Technical, remote chat model only) | Your Daytona or Vercel key, or your SSH server | Daytona, Vercel, or your SSH server. Termux runs on the phone itself. | Commands and files you approve |
| Cloud browser (Technical) | Your Browser Use key | Browser Use | The task text and any start address. Each task asks first. |
| Telegram (Technical) | Your bot token, then you link a private chat | `api.telegram.org` | Messages from linked private chats, and the replies. Groups are never offered. |
| Extras (paid, Google Play; not live yet) | A Play subscription, once the Extras server is deployed | Our Extras server, then OpenRouter (chat) and Exa (search) | The Play purchase token and a random install id to our server; chat and searches to those providers |

Keys and tokens are stored encrypted with the Android Keystore. They go only over https, except
to a server on your own network (a private IP address or a `.local` name), where plain
http is allowed for the custom model endpoint and Home Assistant. See
[docs/threat-model.md](docs/threat-model.md) (T7 to T9) for what each path does and does not protect.

Every network tool call asks you to confirm first, and the confirmation names the host where the
host is fixed or chosen by the model. Decline those confirmations if you do not want outbound
requests. Two Settings → Approvals switches can skip them, both off by default:
"Open websites without asking" skips the web and other network tools, and "Send messages without
asking" skips sends. Texts and calls still ask under the second one.

Nothing else in the app opens a socket. Apart from the integrations above, which you turn on
yourself, no conversation content, memory, file, contact, or calendar entry is sent by the app.

---

## Why this exists

On-device models got small enough to be useful and are still too small to be trusted
with a large context, many tools, or a complicated protocol. Most agent frameworks
assume a frontier model on a server. LocalIntelligence assumes the opposite and optimizes
everything for that assumption:

- **Constrained output.** On the llama.cpp backend, grammar-constrained generation
  means the model cannot emit a malformed action or a tool it was not offered. The
  LiteRT-LM backend takes no grammar, so there the parser rejects both instead.
- **Small tool surface.** Up to 6 tools visible per turn (`AgentConfig.maxVisibleTools`)
  out of a registry of 32 built-in tools (plus opt-in OpenRouter and MCP tools),
  chosen by lexical scoring. Not a second LLM call.
- **Deterministic reliability.** Loop detection, argument validation, risk gating and
  context budgeting are plain Kotlin. The model is used only where language
  understanding is actually required.
- **On-device by default, no telemetry.** Cloud features are opt-in; the table at the top lists each one and where it sends data.

> Complexity is considered a regression unless it measurably improves agent task
> success, inference efficiency, Android capability, or reliability.

## Status

**It builds. Almost nothing about it is measured.**

`:app:assembleDebug` produces an installable debug APK from a clean checkout.
See [docs/build.md](docs/build.md). Beyond that:

- **There are no tests and no CI.** The test suite and the fake-backed eval harness
  were deleted at the owner's explicit instruction, and CI was removed.
  Do not propose restoring them as a goal.
- **There is no eval runner**, so the project's central question — how small
  can the model get before the loop stops being reliable — is unanswered.
- **RAM is unmeasured.** The fit model is derived from real GGUF headers and
  measured quant tables, but no `dumpsys meminfo` figure has been taken on any
  device. The procedure is written down; the number is not.
- **Performance is unmeasured.** The only generation rate ever observed is
  ~0.67 on an x86_64 *emulator*, which says nothing about a phone and is not a
  device number. [docs/measurements.md](docs/measurements.md) is the
  authoritative list of what has and has not been measured.
- **NPU acceleration is implemented for Google Tensor (Pixel) and unverified.**
  LiteRT-LM 0.17.1 plus Google's Tensor dispatch library runs models compiled
  for the phone's Tensor generation on its NPU; nothing has run on a device yet.
  See [docs/acceleration.md](docs/acceleration.md).

See [docs/roadmap.md](docs/roadmap.md) for the item-by-item state.

## Architecture in one paragraph

`:core` is a pure Kotlin/JVM module with zero Android dependencies — the agent loop,
tool registry, selection, loop detection, context building, compaction and memory
search. `:android` is the only module allowed to touch the Android framework.
`:app` is Compose. There is no DI framework, no event bus, and no workflow engine;
a dependency is a constructor parameter. The loop is a `while` loop, deliberately:
no planner node, no critic, no reflection pass, and no ADK.

Full detail: [docs/architecture.md](docs/architecture.md).

## Documentation

| Document | What it is |
|---|---|
| [docs/build.md](docs/build.md) | **Start here.** How to build, what the native build needs, what is measured. |
| [docs/architecture.md](docs/architecture.md) | North star, module map, loop, budgets. |
| [docs/tool-contract.md](docs/tool-contract.md) | The frozen tool contract. Implement, do not redesign. |
| [docs/agent-loop.md](docs/agent-loop.md) | The loop, step by step, with failure paths. |
| [docs/roadmap.md](docs/roadmap.md) | What is built, what is verified, what is deliberately not. |
| [docs/evals.md](docs/evals.md) | Why there is no eval suite, and what is unmeasured. |
| [docs/memory-model.md](docs/memory-model.md) | The RAM fit model: derivation, what is measured, and the device procedure. |
| [docs/acceleration.md](docs/acceleration.md) | GPU/NPU reality on LiteRT-LM, including the Tensor NPU path. |
| [docs/litertlm-backend.md](docs/litertlm-backend.md) | The second backend and why it cannot load a model yet. |
| [docs/hf-hub-download.md](docs/hf-hub-download.md) | Hugging Face download, resume, gating, auth. |

## Building

Requires **JDK 21** and an Android SDK (platform 36, an NDK, CMake 3.22.1).

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

# llama.cpp is pinned to tag b11988 and is not vendored.
git clone --depth 1 --branch b11988 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp

PIDROID_LLAMA_DIR=/tmp/llama.cpp ./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` — **MEASURED** at
~68.0 MB (64.9 MiB, debug, both ABIs; native libraries compressed, extracted at install).

Full instructions, required SDK components, and two traps that will
otherwise cost you an hour: [docs/build.md](docs/build.md).

## Contributing

Read [docs/architecture.md](docs/architecture.md) and
[docs/tool-contract.md](docs/tool-contract.md) first. Both are binding.

The short version:

```
Do not redesign architecture.
Do not add dependencies unless it is genuinely necessary.
Do not create abstractions for hypothetical future use.
Do not modify interfaces owned by another workstream.
Do not add telemetry.
Cloud features and MCP are opt-in: off by default, no key or server means no network call.
Prefer Android/Kotlin platform APIs over wrappers.
Keep model-visible outputs extremely compact.
```

Two rules that are easy to get wrong here:

- **Do not restore the test suite or add CI workflows.** The deletion was
  deliberate. Verification means running a real model on a real device.
- **Do not quote a performance or RAM number that was not measured.** There are
  none. Write "unmeasured" and say how to measure it.

## License

Apache-2.0. See [LICENSE](LICENSE).
