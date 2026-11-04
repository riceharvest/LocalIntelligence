# LocalIntelligence

An Android assistant that runs a small language model on the phone and acts through tools.
Cloud features are optional and off until the user turns them on.

## Chats and runs

**Chat**:
One thread of messages between the user and the assistant. It lasts until the user starts a new chat.
_Avoid_: session, conversation, thread

**Message**:
One entry in a chat, written by the user or by the assistant.

**Run**:
Everything the assistant does to answer one user message or one firing of a scheduled task. It ends with a reply or a stop.
_Avoid_: task, job, turn

**Step**:
One decision inside a run: the model either replies or calls one tool.
_Avoid_: turn, iteration

**Unattended run**:
A run that nobody is watching, such as a scheduled task or a Telegram message. A tool call that needs approval is declined unless auto-accept covers it.

**Scheduled task**:
An instruction the user saves to run on a schedule. Each firing starts an unattended run.
_Avoid_: task (bare), job, routine, automation

## Tools and extensions

**Tool**:
One action the assistant can take, with a name, arguments and a risk tier. Examples: set a timer, read the battery level.
_Avoid_: function, action, command

**Core tool**:
A tool the model sees in full, with its arguments, in every message. Every other enabled tool is listed by name and can be called directly.

**Observation**:
What a tool returned, as the model reads it.
_Avoid_: tool output, tool result

**Skill**:
Written instructions the assistant follows for one kind of job. A skill has no code.
_Avoid_: prompt, recipe

**Plugin**:
A switchable bundle of tools, settings and behaviour. Turning it off removes all of it. The built-in tool families are plugins too.
_Avoid_: extension, add-on, module

**Script plugin**:
A plugin the user installs whose tools run as sandboxed JavaScript with no network or file access.

**MCP server**:
A remote server the user connects. Its tools join the assistant's tools.

## Safety

**Risk tier**:
How much harm a tool call can do: read-only, reversible or destructive.
_Avoid_: danger level, safety level

**Approval**:
The user's yes to one specific tool call before it runs.
_Avoid_: confirmation, consent, permission

**Auto-accept**:
A setting, per risk tier, that gives approval without asking.

**Android permission**:
An access grant from the operating system, such as contacts or SMS. Always write it out in full.
_Avoid_: permission (bare), access

**Untrusted text**:
Content that came from outside the user, such as web pages, MCP servers and script plugins. The assistant treats it as data and never as instructions.

## Memory

**Memory**:
A fact the assistant saved about the user so it can use it in later chats.
_Avoid_: using "memory" for RAM

**About you**:
Text the user writes about themselves that the assistant sees in every chat.
_Avoid_: profile, bio

**RAM footprint**:
How much RAM a model needs once it is loaded, context included.
_Avoid_: memory, memory estimate

## Models and compute

**Model**:
A language model file on the phone that the assistant thinks with.

**Projector**:
The extra file that lets a model see images or hear audio.
_Avoid_: mmproj (in user-facing text)

**Engine**:
The library that runs a model on the phone: llama.cpp or LiteRT-LM.
_Avoid_: backend, runtime

**Compute**:
The chip that does the engine's math: CPU, GPU or NPU.
_Avoid_: backend, accelerator

**Remote model**:
A model that runs on another machine, such as OpenRouter, a ChatGPT plan or a server on the home network. It is used only when the user turns it on.
_Avoid_: cloud model (a home-network server is remote but not cloud), API model

**Provider**:
An outside service the user connects with a key or account, such as OpenRouter, Brave Search, Daytona or Home Assistant.
_Avoid_: integration, service, backend

**Own key**:
A key the user creates with a provider and pastes into the app.
_Avoid_: BYOK, API key (bare)

## Modes and subscription

**Simple mode**:
The default settings view. Everything in it works with no key, account or setup, and the app picks the model and settings for the phone.
_Avoid_: basic mode, easy mode

**Technical mode**:
The settings view where every option can be changed, including keys and inference settings.
_Avoid_: advanced mode, expert mode

**Play build**:
The version of the app on Google Play. It does everything Play policy allows.

**Full build**:
The version installed outside Play. It adds what Play forbids, such as screen control.
_Avoid_: sideload flavor, pro build

**Extras**:
The one paid subscription. It turns on cloud features in Simple mode with keys the app provides.
_Avoid_: managed tier, premium, pro

## Evals

**Eval task**:
One fixed request with a pass or fail check, used to compare models and changes.
_Avoid_: test, task (bare)
