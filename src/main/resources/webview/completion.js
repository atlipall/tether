/**
 * A completion list for a textarea, like the IDE's code completion: it opens while the text before the caret matches
 * one of its sources, narrows as the user types, and puts the chosen item in place of what was typed.
 *
 * Up and Down (or Control+P and Control+N) move through the list, Enter or Tab accepts, and Escape closes it until
 * the typed word changes. While it is open those keys belong to the list, not to the box; while new items are on
 * their way, Enter and Tab wait for them rather than choose an old row or send the half-typed word. Accepting goes through
 * execCommand, so it is one step on the box's undo stack.
 *
 * A source is `{ id, label, match(before), items(query, show), row(item), text(item), empty }`:
 * - `match` takes the text before the caret and returns `{ start, query }` (where the replaced text begins, and what
 *   to look for) or null;
 * - `items` calls `show(list)` with the matches, now or later; a late answer to an older query is ignored;
 * - `row` gives `{ label, detail }` for an item, and `text` what accepting it inserts;
 * - `empty` is the line shown when nothing matches, and `label` names the list for assistive technology;
 * - `cancel`, if given, drops an answer the source is still preparing, for when the list closes.
 */
window.Completion = (function () {
  'use strict';

  /** Most rows shown; the list scrolls past a handful of them. */
  const MAX_ROWS = 50;

  /**
   * Gives [box] a completion list drawn from [sources], the first that matches winning. [list] is the empty element
   * the rows go in, and [status] a polite live region that announces how many there are; both are shown, hidden and
   * filled here. Returns `{ close, isOpen }`.
   */
  function install(box, list, status, sources) {
    let source = null;
    let match = null;
    let items = [];
    let active = -1;
    let request = 0;
    /** The source and start of a word the user dismissed with Escape; it stays closed until either changes. */
    let dismissed = null;

    list.setAttribute('role', 'listbox');
    box.setAttribute('aria-autocomplete', 'list');
    box.setAttribute('aria-controls', list.id);
    status.setAttribute('aria-live', 'polite');
    list.addEventListener('pointerdown', (e) => e.preventDefault());

    const isOpen = () => !list.hidden;

    function close() {
      request++;
      if (source && source.cancel) source.cancel();
      source = null;
      match = null;
      items = [];
      active = -1;
      list.hidden = true;
      list.textContent = '';
      list.removeAttribute('aria-busy');
      status.textContent = '';
      box.removeAttribute('aria-activedescendant');
    }

    const optionId = (index) => list.id + '-' + index;

    function setActive(index) {
      active = index;
      [...list.children].forEach((row, i) => row.setAttribute('aria-selected', String(i === index)));
      const row = items.length ? list.children[index] : null;
      if (!row) return box.removeAttribute('aria-activedescendant');
      box.setAttribute('aria-activedescendant', row.id);
      row.scrollIntoView({ block: 'nearest' });
    }

    function rowFor(item, index) {
      const { label, detail } = source.row(item);
      const row = document.createElement('div');
      row.id = optionId(index);
      row.className = 'completion-row';
      row.setAttribute('role', 'option');
      const name = document.createElement('span');
      name.className = 'completion-label';
      name.textContent = label;
      row.appendChild(name);
      if (detail) {
        row.title = detail;
        const more = document.createElement('span');
        more.className = 'completion-detail';
        more.textContent = detail;
        row.appendChild(more);
      }
      row.addEventListener('pointerdown', () => accept(index));
      row.addEventListener('pointermove', () => { if (active !== index) setActive(index); });
      return row;
    }

    function render(found) {
      items = found.slice(0, MAX_ROWS);
      list.textContent = '';
      list.setAttribute('aria-label', source.label);
      if (!items.length) {
        const none = document.createElement('div');
        none.className = 'completion-empty';
        none.setAttribute('role', 'option');
        none.setAttribute('aria-disabled', 'true');
        none.textContent = source.empty;
        list.appendChild(none);
      } else {
        items.forEach((item, index) => list.appendChild(rowFor(item, index)));
      }
      list.hidden = false;
      list.removeAttribute('aria-busy');
      status.textContent = items.length ? items.length + (items.length === 1 ? ' suggestion' : ' suggestions') : source.empty;
      setActive(items.length ? 0 : -1);
    }

    /** The first source whose trigger the text before the caret ends with, and what it matched; null for none. */
    function matching() {
      if (box.selectionStart !== box.selectionEnd) return null;
      const before = box.value.slice(0, box.selectionStart);
      for (const candidate of sources) {
        const found = candidate.match(before);
        if (found) return { source: candidate, match: found };
      }
      return null;
    }

    const isDismissed = (found) =>
      dismissed && dismissed.source === found.source && dismissed.start === found.match.start;

    /** Opens, narrows or closes the list to follow the text before the caret. */
    function refresh() {
      const found = matching();
      if (!found || isDismissed(found)) {
        if (!found) dismissed = null;
        if (isOpen() || source) close();
        return;
      }
      const unchanged = isOpen() && found.source === source && match
        && found.match.start === match.start && found.match.query === match.query;
      if (unchanged) return;
      if (isOpen()) awaitNewItems();
      source = found.source;
      match = found.match;
      const asked = ++request;
      source.items(match.query, (result) => {
        if (asked === request && source === found.source) render(result);
      });
    }

    /**
     * Leaves the shown rows in place while a new query's items are on their way, but stops them being chosen: what
     * they offer was for the old query, and maybe another source.
     */
    function awaitNewItems() {
      items = [];
      setActive(-1);
      list.setAttribute('aria-busy', 'true');
    }

    function accept(index) {
      const item = items[index];
      if (!item || !match) return;
      const text = source.text(item);
      box.setSelectionRange(match.start, box.selectionStart);
      close();
      document.execCommand('insertText', false, text);
    }

    function dismiss() {
      if (source && match) dismissed = { source, start: match.start };
      close();
    }

    /** The list's keys, or null for a key the box keeps. */
    function keyAction(e) {
      if (e.altKey || e.metaKey) return null;
      const step = (by) => (items.length ? () => setActive((active + by + items.length) % items.length) : null);
      if (e.ctrlKey) return { p: step(-1), n: step(1) }[e.key] || null;
      if (e.shiftKey) return null;
      if (e.key === 'ArrowDown') return step(1);
      if (e.key === 'ArrowUp') return step(-1);
      if (e.key === 'Escape') return dismiss;
      if (e.key !== 'Enter' && e.key !== 'Tab') return null;
      if (list.hasAttribute('aria-busy')) return () => {};
      return active >= 0 ? () => accept(active) : null;
    }

    box.addEventListener('keydown', (e) => {
      if (!isOpen() || window.TextKeys.isComposing(e)) return;
      const action = keyAction(e);
      if (!action) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      action();
    }, true);
    box.addEventListener('input', refresh);
    box.addEventListener('click', refresh);
    box.addEventListener('keyup', (e) => {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) refresh();
    });
    box.addEventListener('blur', close);
    close();
    return { close, isOpen };
  }

  /**
   * Orders [entries] for [query]: names starting with it first, then names containing it, each in their own order;
   * the rest are dropped. Case is ignored.
   */
  function rank(entries, query, nameOf) {
    const q = query.toLowerCase();
    const starts = [];
    const contains = [];
    for (const entry of entries) {
      const name = nameOf(entry).toLowerCase();
      if (name.startsWith(q)) starts.push(entry);
      else if (name.includes(q)) contains.push(entry);
    }
    return starts.concat(contains);
  }

  return { install, rank };
})();
