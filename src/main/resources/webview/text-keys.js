/**
 * The standard text-editing keyboard shortcuts for a textarea, performed by the page itself.
 *
 * Tether's chat runs in JCEF's off-screen browser, which forwards raw key events to Chromium. On macOS, Chromium
 * relies on the operating system to turn keys such as Option+Left or Control+K into editing commands, and an
 * off-screen browser never receives those commands, so the page sees the keydown but nothing happens. [TextKeys]
 * performs those commands through the Selection and editing APIs instead, which keeps Chromium's own word, line and
 * character boundaries and leaves every edit on the textarea's native undo stack.
 *
 * Moves extend the selection when Shift is held. Clipboard shortcuts and Select All are left to the browser and the
 * IDE, which already handle them.
 */
window.TextKeys = (function () {
  'use strict';

  const collapsed = (box) => box.selectionStart === box.selectionEnd;

  /** Moves the caret, or extends the selection, by one [unit] (a Selection.modify granularity) in [direction]. */
  function modify(extend, direction, unit) {
    getSelection().modify(extend ? 'extend' : 'move', direction, unit);
  }

  /** Where the moving end of [box]'s selection is: the caret, or the end Shift-moves would carry. */
  const focusOffset = (box) => (box.selectionDirection === 'backward' ? box.selectionStart : box.selectionEnd);

  /**
   * A shortcut that moves by [unit] in [direction], and extends the selection when called with `extend`. Only moves
   * take Shift this way, which [actionFor] knows from [isMove].
   */
  function move(direction, unit) {
    const action = (box, extend) => modify(extend, direction, unit);
    action.isMove = true;
    return action;
  }

  /**
   * A shortcut that moves to the paragraph boundary in [direction], and on to the next paragraph's boundary when
   * already on one, as Option+Up and Option+Down do in macOS text views.
   */
  function paragraph(direction) {
    const action = (box, extend) => {
      const before = focusOffset(box);
      modify(extend, direction, 'paragraphboundary');
      if (focusOffset(box) !== before) return;
      modify(extend, direction, 'character');
      modify(extend, direction, 'paragraphboundary');
    };
    action.isMove = true;
    return action;
  }

  /**
   * Selects what a deleting shortcut removes: the selection if there is one, otherwise one [unit] from the caret in
   * [direction], or one character when that unit is empty (the line break at a paragraph's end). False if nothing is.
   */
  function selectToDelete(box, direction, unit) {
    if (!collapsed(box)) return true;
    modify(true, direction, unit);
    if (collapsed(box)) modify(true, direction, 'character');
    return !collapsed(box);
  }

  /** A shortcut that deletes the selection, or by one [unit] from the caret in [direction]. */
  const erase = (direction, unit) => (box) => {
    if (selectToDelete(box, direction, unit)) document.execCommand('delete');
  };

  /** Text cut by Control+K, which Control+Y pastes back; separate from the system clipboard, as in macOS. */
  let killed = '';

  /** Control+K: cuts the selection, or to the paragraph's end, or the line break when already there. */
  function kill(box) {
    if (!selectToDelete(box, 'forward', 'paragraphboundary')) return;
    killed = box.value.slice(box.selectionStart, box.selectionEnd);
    document.execCommand('delete');
  }

  /** Control+Y: pastes what Control+K last cut. */
  function yank() {
    if (killed) document.execCommand('insertText', false, killed);
  }

  /**
   * Control+T: swaps the characters either side of the caret and steps past them; at the end of a line it swaps the
   * two before the caret instead, as in macOS. Characters are whole graphemes, so emoji stay intact.
   */
  function transpose(box) {
    if (!collapsed(box)) return;
    const end = box.selectionEnd;
    const atLineEnd = end === box.value.length || box.value[end] === '\n';
    if (!atLineEnd) modify(false, 'forward', 'character');
    modify(true, 'backward', 'character');
    modify(true, 'backward', 'character');
    const pair = box.value.slice(box.selectionStart, box.selectionEnd);
    const [first, second, ...rest] = [...new Intl.Segmenter().segment(pair)].map((s) => s.segment);
    if (!second || rest.length || pair.includes('\n')) {
      box.setSelectionRange(end, end);
      return;
    }
    document.execCommand('insertText', false, second + first);
  }

  const undo = () => document.execCommand('undo');
  const redo = () => document.execCommand('redo');

  /** macOS text-system bindings: Cocoa's arrows and deletes plus the Emacs-style Control keys. */
  const MAC = {
    'Alt+ArrowLeft': move('backward', 'word'),
    'Alt+ArrowRight': move('forward', 'word'),
    'Meta+ArrowLeft': move('backward', 'lineboundary'),
    'Meta+ArrowRight': move('forward', 'lineboundary'),
    'Meta+ArrowUp': move('backward', 'documentboundary'),
    'Meta+ArrowDown': move('forward', 'documentboundary'),
    'Alt+ArrowUp': paragraph('backward'),
    'Alt+ArrowDown': paragraph('forward'),
    'Ctrl+a': move('backward', 'paragraphboundary'),
    'Ctrl+e': move('forward', 'paragraphboundary'),
    'Ctrl+b': move('backward', 'character'),
    'Ctrl+f': move('forward', 'character'),
    'Ctrl+p': move('backward', 'line'),
    'Ctrl+n': move('forward', 'line'),
    'Alt+Backspace': erase('backward', 'word'),
    'Alt+Delete': erase('forward', 'word'),
    'Meta+Backspace': erase('backward', 'lineboundary'),
    'Meta+Delete': erase('forward', 'lineboundary'),
    'Ctrl+h': erase('backward', 'character'),
    'Ctrl+d': erase('forward', 'character'),
    'Ctrl+k': kill,
    'Ctrl+y': yank,
    'Ctrl+t': transpose,
    'Meta+z': undo,
    'Shift+Meta+z': redo,
  };

  /**
   * Windows and Linux bindings. Chromium normally performs these itself; Tether performs them too so the box behaves
   * the same whatever the host's browser forwards, and so Control+Up and Control+Down step by paragraph everywhere.
   */
  const PC = {
    'Ctrl+ArrowLeft': move('backward', 'word'),
    'Ctrl+ArrowRight': move('forward', 'word'),
    'Home': move('backward', 'lineboundary'),
    'End': move('forward', 'lineboundary'),
    'Ctrl+Home': move('backward', 'documentboundary'),
    'Ctrl+End': move('forward', 'documentboundary'),
    'Ctrl+ArrowUp': paragraph('backward'),
    'Ctrl+ArrowDown': paragraph('forward'),
    'Ctrl+Backspace': erase('backward', 'word'),
    'Ctrl+Delete': erase('forward', 'word'),
    'Ctrl+z': undo,
    'Ctrl+y': redo,
    'Shift+Ctrl+z': redo,
  };

  /**
   * The event's key, letters in lower case. With Control held the letter comes from the physical key, as Cocoa reads
   * it: a forwarded Control+letter can arrive as a control character, and on a non-Latin layout as another letter.
   */
  function keyName(e) {
    if (e.ctrlKey && /^Key[A-Z]$/.test(e.code)) return e.code.slice(3).toLowerCase();
    return e.key.length === 1 ? e.key.toLowerCase() : e.key;
  }

  /** [e]'s key with its modifiers other than Shift, as 'Ctrl+Alt+Meta+key'. */
  const chord = (e) => (e.ctrlKey ? 'Ctrl+' : '') + (e.altKey ? 'Alt+' : '') + (e.metaKey ? 'Meta+' : '') + keyName(e);

  /**
   * The shortcut [e] asks for under [keymap], as a function of the box: the exact chord, or with Shift held a move's
   * chord without it, which then extends the selection. Null when the key is not a shortcut.
   */
  function actionFor(e, keymap) {
    const keys = chord(e);
    const exact = keymap[(e.shiftKey ? 'Shift+' : '') + keys];
    if (exact) return (box) => exact(box, false);
    const shifted = e.shiftKey && keymap[keys];
    return shifted && shifted.isMove ? (box) => shifted(box, true) : null;
  }

  /** Whether [e] is typed while an input method composes text; such keys belong to the input method. */
  const isComposing = (e) => e.isComposing || e.keyCode === 229;

  /**
   * Makes [box] answer the platform's text-editing shortcuts. [mac] picks the macOS bindings; it defaults to the
   * platform the page runs on.
   */
  function install(box, mac = /Mac/.test(navigator.platform)) {
    const keymap = mac ? MAC : PC;
    box.addEventListener('keydown', (e) => {
      if (isComposing(e)) return;
      const action = actionFor(e, keymap);
      if (!action) return;
      e.preventDefault();
      action(box);
    });
  }

  return { install, isComposing };
})();
