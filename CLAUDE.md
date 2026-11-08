# Tether — instructions for Claude

## Comments

Avoid inline comments (`//` or `/* */` inside function bodies). They drift out of date as the code changes and end up saying things that are no longer true.

Instead:

- **Let the language carry the meaning.** Use descriptive names, small well-named functions, types, sealed classes, enums, value classes, named arguments and constants so the code explains itself. If you feel a comment is needed to explain a block, extract that block into a function whose name says what it does.
- **Document declarations with KDoc** (`/** ... */`, or Javadoc/JSDoc in other languages) on classes, functions and properties. Describe what it does, why it exists and any contract (parameters, return value, threading, side effects), not how the body is written line by line.
- **Keep KDoc current.** When you change a declaration's behaviour, update its KDoc in the same change.
- **Don't add inline comments to new code**, and when editing code that has them, prefer turning them into better names, extracted functions or KDoc over leaving or adding to them.

An inline comment is acceptable only when the language can't express the point and it has no natural home in a doc comment, such as a link to an upstream bug that justifies a workaround. Keep those rare and short.

## UI and UX

Use the Apple HIG skills (`apple-hig-skills:hig-*`) when designing or changing anything the user sees, such as the chat webview (`src/main/resources/webview/`), tool windows, tabs, actions and dialogs. Load the skill that fits the work before making the change, for example:

- `hig-foundations` for colour, typography, dark mode, layout, accessibility and writing style.
- `hig-patterns` for loading states, feedback, modality, undo and error handling.
- `hig-components-menus`, `hig-components-controls`, `hig-components-dialogs` and `hig-components-status` for buttons, toolbars, inputs, dialogs and progress indicators.
- `hig-inputs` for keyboard shortcuts and pointer interaction.

Tether runs inside JetBrains IDEs, so where the HIG and the IntelliJ Platform UI guidelines disagree, follow the IDE's conventions so Tether feels native to the host.

## Branches

Don't commit directly to `main`. Every push to `main` that changes code publishes a new plugin version to everyone who installs Tether from the GitHub Pages plugin repository (`.github/workflows/release.yml`), so merging to `main` is a deliberate step.

- Never push `main`, not even when asked to merge into it. Pushing it publishes a release, so the user always does it themselves.
- Start a branch for new work.
- A small change doesn't need its own branch when work is already under way on one; commit it there.
