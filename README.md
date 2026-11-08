# Tether for coding agents

A personal JetBrains plugin (built for WebStorm 2026.2) that gives coding agents a native chat panel
inside the IDE: the [Claude Code](https://docs.claude.com/en/docs/claude-code) CLI, and
[Agent Client Protocol](#other-agents-acp) agents such as Hermes.

It runs your locally installed `claude` binary (and ACP agent), so it uses whatever login that agent
already has (e.g. your Claude Pro subscription). The plugin never reads, stores or forwards credentials.

## Features

- **Native chat panel.** A "Tether" tool window with streaming replies, markdown, tool calls,
  and permission prompts (Allow / Allow for session / Deny with a reason). Stop, restart,
  permission mode (Ask before edits / Accept edits / Plan / Auto), model and effort are in the
  header. Model and effort change the running session at once; a reopened chat keeps its mode,
  model and effort.
- **Several chats at once.** One tab per chat, each with its own agent process. The `+` button opens a draft in this project; no process starts until its first message. **Chat in Workspace…** offers a new git worktree (`claude --worktree <name>`), an existing worktree, or a past Claude session to resume.
- **The IDE follows the selected chat.** Selecting a worktree chat switches the Project view to that
  checkout's scope, swaps open editors to the same files in that checkout (caret kept), and defaults
  Find in Files to it. A chat whose checkout the IDE isn't showing says which one it is
  (`IDE: main` / `IDE: <worktree>`) in its header.
- **Files are clickable.** Paths Claude reads, edits or mentions (in backticks or markdown links)
  open in the editor; edits have a "View diff" link.
- **See everything that changed.** **View changes** in the chat header opens the IDE's changes browser for the
  chat's checkout: since it forked from `main` (or `master`) on a branch, otherwise since the commit
  HEAD was at when the session started (for a resumed session, the last commit before its first
  message). It covers commits, uncommitted edits and new untracked files, yours included.
- **See how full the context is.** A meter at the right of the chat header shows how much of the
  context window is used. Hover it for tokens used/max, what fills it, and where autocompact kicks in.
  It turns amber within 10 points of autocompact and red at it. It updates when the session starts
  and after every turn, using the CLI's `get_context_usage` request (no model call).
- **Claude knows what you edited.** Files you save between messages are listed in a hidden note
  on your next message, so Claude re-reads them instead of working from stale contents.
- **Claude knows what you did in git.** If you commit, push, pull or fetch, switch branch, reset or amend, stash, or
  start or finish a merge or rebase between messages, from the IDE or a terminal, a hidden note on your next message
  says what changed (with the commits). Claude's own git commands aren't reported back to it.
- **Claude knows where you are looking.** Every message carries your current file, caret line,
  selection and enclosing function, so "what does this function do?" just works.
- **Claude can ask the IDE.** Claude gets two read-only tools, called without a prompt:
  `mcp__tether__diagnostics` returns the errors and warnings the IDE reports for files (the editor's own analysis
  plus the TypeScript service), and `mcp__tether__find_usages` returns where a symbol is declared and every
  reference to it, from the IDE's index (only in the chat's own checkout). They are served in-process over the
  CLI's stdin/stdout, with no separate MCP server.
- **Claude can use the IDE's fixes and refactorings.** `mcp__tether__fixes` lists the quick fixes the IDE offers
  for the problems on a line (what Alt+Enter shows, including the TypeScript service's, e.g. missing imports), and
  `mcp__tether__apply_fix` applies one. `mcp__tether__rename_symbol` and `mcp__tether__move_file` run the IDE's
  rename and move refactorings, updating every reference and import in the chat's checkout. These change files, so
  they ask for permission like Claude's own edits; they run without dialogs (conflicts are reported to Claude), save
  what they changed, report the errors left, and can be undone in the IDE.
- **Claude's edits are checked.** After each `Edit`/`Write`, the IDE checks the file and tells Claude about any
  errors in the same turn, so it fixes them without being asked. Clean files add nothing.
- **Fix with Tether / Send to Tether.** Alt+Enter on any highlighted error or warning offers **Fix with Tether**,
  which asks the selected chat to fix the problems on that line. In the test runner, **Send Failures to Tether**
  (right-click) puts the selected failed tests' messages and stack traces into the message box; in a run console,
  **Send to Tether** puts the selected output (or all of it) there.
- **Revert to before a message.** Each message sent in this IDE session gets a **Revert to here** link (on hover).
  It asks what to revert: **Files and Conversation**, **Files Only** or **Conversation Only**. Files go back to how
  they were before that message went out, using the IDE's Local History (undoable from there; only files Local
  History tracks, so not excluded folders such as worktrees by default). The conversation goes back too: Claude
  forgets that message and everything after it, the chat continues in a fork of the session, and the message
  returns to the input box.
- **Images.** Paste, drop or pick (paperclip button) images to send with a message (PNG/JPEG/GIF/WebP, up to 10,
  5 MB each, large ones downscaled). HEIC photos are converted to JPEG with `sips` on macOS, or `heif-convert` or
  ImageMagick elsewhere. Images Claude reads, and local project images referenced in its
  replies, are shown inline. Remote images are never loaded.
- **Up/Down recall** of previously sent messages, per project, across restarts.
- **Add to Tether** (Cmd+Shift+Option+K, or the editor context menu) inserts `@path#Lx-y`
  for the current selection.

## Requirements

- WebStorm 2026.2 (build 262) — other 2026.2 JetBrains IDEs with JCEF should also work.
- [Claude Code](https://docs.claude.com/en/docs/claude-code) installed and logged in
  (run `claude` once in a terminal). The plugin looks for `claude` on your PATH, then in
  `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin`; you can also set the path in settings.

## Install

Every push to `main` builds a new version and publishes it to a plugin repository on GitHub Pages.
To install from there and get update notifications in the IDE:

1. Settings → Plugins → ⚙️ → **Manage Plugin Repositories…** → add
   `https://atlipall.github.io/tether/updatePlugins.xml`.
2. Search the **Marketplace** tab for **Tether** and install it.

The IDE then offers each new version through its usual plugin update notifications. Every version's
zip is also attached to the [GitHub releases](https://github.com/atlipall/tether/releases).

Versions are `<major.minor>.<patch>`: `version.txt` holds the major and minor, and the patch counts the
commits since `version.txt` last changed. To start a new minor version, edit `version.txt`; the commit
that changes it releases as `.0`.

### From source

Quit the IDE, then from the project root:

```sh
./install.sh              # builds and installs into the newest WebStorm config folder
./install.sh --ide pycharm # the same for PyCharm (2026.2 or later; not the old Community Edition)
./install.sh --ide idea    # any JetBrains IDE: idea, goland, clion, rider, phpstorm, rustrover, …
./install.sh --no-build   # install the existing zip
./install.sh --force      # install while the IDE runs; restart it afterwards
```

Or manually, after `./gw buildPlugin`, either:

- Settings → Plugins → ⚙️ → **Install Plugin from Disk…** → `build/distributions/tether-0.2.0.zip`, and restart; or
- with WebStorm closed, unzip it into the plugins folder:
  ```sh
  unzip -o build/distributions/tether-0.2.0.zip \
    -d "$HOME/Library/Application Support/JetBrains/WebStorm2026.2/plugins"
  ```

### Worktree indexing

WebStorm excludes `.claude/worktrees` from indexing by default. The first time you select a
worktree chat, the plugin offers **Include and restart** to change that (it never changes the setting
without your click). Until then, editors still follow the worktree but without code insight, and the
Project view / Find scope are left alone.

## Settings

Settings → Tools → **Tether**, or the gear in the Tether title bar:

| Setting | Default |
|---|---|
| **All agents** | |
| IDE follows the selected chat's checkout | on |
| Attach editor context to messages | on |
| **Claude** | |
| Claude binary | auto-detect |
| Default permission mode | the CLI's own setting |
| Extra CLI arguments (e.g. `--model sonnet`) | none |
| Let Claude use IDE tools | on (new or restarted chats) |
| Check Claude's edits for errors | on (new or restarted chats) |
| **ACP agent** | |
| Command | `hermes acp` |

### Other agents (ACP)

Set **Command** under **ACP Agent** to the command for an Agent Client Protocol agent, such as `hermes acp`, and choose it with the agent picker in a new draft's header, before the model. Selection is remembered and keeps the draft text and attachments. A chat keeps its agent after starting; create another chat to use a different agent.

ACP agents keep their own authentication, model configuration and approval rules. Their advertised permission modes and approval choices are shown directly. Open ACP tabs can be restored through the agent's `session/load`; the past-session browser, archive, conversation rewind, Claude model/effort picker, IDE tools and new-worktree creation remain Claude-only. Images require the agent to advertise image support.

## Development

There is no system Java needed: `./gw` runs Gradle with WebStorm's bundled JBR
(`/Applications/WebStorm.app/Contents/jbr`).

```sh
./gw test          # unit + platform tests
./gw buildPlugin   # build/distributions/tether-0.2.0.zip
./gw runIde        # sandbox WebStorm with the plugin loaded
```

Stack: Kotlin, IntelliJ Platform Gradle Plugin 2.x, JCEF for the chat page (plain JS with vendored
`marked` and `DOMPurify`). The plugin talks to the CLI with
`claude -p --input-format stream-json --output-format stream-json --permission-prompt-tool stdio`.

Layout:

```
src/main/kotlin/tether/
  acp/        ACP client protocol and session adapter
  cli/        CLI protocol, process transport, command line
  session/    chats, sessions, transcripts, prompt history
  context/    editor context (file, caret, enclosing symbol)
  follow/     IDE follows the selected chat's checkout
  git/        worktree discovery and naming
  image/      image validation and scaling
  ide/        edit tracking, file links, editor actions
  ui/         tool window, tabs, JCEF bridge, popups
  settings/   settings and configurable
src/main/resources/webview/   chat page (HTML/CSS/JS)
docs/superpowers/             design specs and implementation plans
```

## Security notes

The chat page renders model output, so it is treated as untrusted: a Content Security Policy allows
only inline scripts/styles and `data:` images supplied by the plugin; markdown is sanitized with
DOMPurify (no images, forms, styles); only page-created elements can open files, answer permission
prompts or show images; the page can't navigate away; only http(s) links open in the browser.
`ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` are removed from the CLI's environment so it always uses
your CLI login.

This filtering applies only to Claude; ACP agents receive the IDE environment unchanged and handle their own authentication.

## Status

Personal open-source project, not published to the JetBrains Marketplace.

## License

[MIT](LICENSE). The bundled [marked](https://github.com/markedjs/marked) (MIT) and
[DOMPurify](https://github.com/cure53/DOMPurify) (Apache-2.0 or MPL-2.0) keep their own licences,
noted at the top of each file in `src/main/resources/webview/vendor/`.
