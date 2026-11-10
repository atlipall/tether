window.runTextKeysTests = function () {
  const results = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const test = (name, run) => {
    try { run(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const box = (mac, value, start, end = start) => {
    const area = document.createElement('textarea');
    area.style.width = '400px';
    document.body.appendChild(area);
    window.TextKeys.install(area, mac);
    area.value = value;
    area.focus();
    area.setSelectionRange(start, end);
    return area;
  };
  const press = (area, key, modifiers = {}) => {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...modifiers });
    area.dispatchEvent(event);
    return event.defaultPrevented;
  };
  const caret = (area) => [area.selectionStart, area.selectionEnd];
  const same = (actual, expected, message) =>
    assert(JSON.stringify(actual) === JSON.stringify(expected), `${message}: got ${JSON.stringify(actual)}`);
  const text = 'hello brave new world\nsecond line here';

  test('Option+arrows move a word at a time on macOS', () => {
    const area = box(true, text, 8);
    press(area, 'ArrowLeft', { altKey: true });
    same(caret(area), [6, 6], 'Option+Left goes to the start of the word');
    press(area, 'ArrowRight', { altKey: true });
    same(caret(area), [11, 11], 'Option+Right goes to the end of the word');
  });

  test('Command+arrows go to the ends of the line and of the text', () => {
    const area = box(true, text, 8);
    press(area, 'ArrowRight', { metaKey: true });
    same(caret(area), [21, 21], 'Command+Right ends the line');
    press(area, 'ArrowLeft', { metaKey: true });
    same(caret(area), [0, 0], 'Command+Left starts the line');
    press(area, 'ArrowDown', { metaKey: true });
    same(caret(area), [text.length, text.length], 'Command+Down ends the text');
    press(area, 'ArrowUp', { metaKey: true });
    same(caret(area), [0, 0], 'Command+Up starts the text');
  });

  test('Shift extends the selection for every move', () => {
    const area = box(true, text, 8);
    press(area, 'ArrowRight', { altKey: true, shiftKey: true });
    same(caret(area), [8, 11], 'Shift+Option+Right selects to the end of the word');
    press(area, 'ArrowDown', { metaKey: true, shiftKey: true });
    same(caret(area), [8, text.length], 'Shift+Command+Down selects to the end of the text');
  });

  test('Option+Up and Option+Down step between paragraphs', () => {
    const area = box(true, text, 30);
    press(area, 'ArrowUp', { altKey: true });
    same(caret(area), [22, 22], 'Option+Up goes to the start of the paragraph');
    press(area, 'ArrowUp', { altKey: true });
    same(caret(area), [0, 0], 'Option+Up again goes to the previous paragraph');
    press(area, 'ArrowDown', { altKey: true });
    same(caret(area), [21, 21], 'Option+Down goes to the end of the paragraph');
  });

  test('Emacs-style Control keys move the caret', () => {
    const area = box(true, text, 8);
    press(area, 'e', { ctrlKey: true });
    same(caret(area), [21, 21], 'Control+E ends the paragraph');
    press(area, 'a', { ctrlKey: true });
    same(caret(area), [0, 0], 'Control+A starts the paragraph');
    press(area, 'f', { ctrlKey: true });
    same(caret(area), [1, 1], 'Control+F steps forward');
    press(area, 'b', { ctrlKey: true });
    same(caret(area), [0, 0], 'Control+B steps back');
    press(area, 'n', { ctrlKey: true });
    same(caret(area), [22, 22], 'Control+N goes down a line');
    press(area, 'p', { ctrlKey: true });
    same(caret(area), [0, 0], 'Control+P goes up a line');
  });

  test('Option+Delete and Command+Delete delete a word and to the line start', () => {
    const area = box(true, 'hello brave world', 11);
    press(area, 'Backspace', { altKey: true });
    same(area.value, 'hello  world', 'Option+Delete deletes the word before the caret');
    press(area, 'Delete', { altKey: true });
    same(area.value, 'hello ', 'Option+Forward Delete deletes the word after the caret');
    press(area, 'Backspace', { metaKey: true });
    same(area.value, '', 'Command+Delete deletes to the start of the line');
  });

  test('A selection is deleted as a whole', () => {
    const area = box(true, 'hello brave world', 6, 12);
    press(area, 'Backspace', { altKey: true });
    same(area.value, 'hello world', 'Only the selection goes');
  });

  test('Control+K cuts to the end of the paragraph and Control+Y puts it back', () => {
    const area = box(true, text, 6);
    press(area, 'k', { ctrlKey: true });
    same(area.value, 'hello \nsecond line here', 'Control+K cuts the rest of the paragraph');
    press(area, 'k', { ctrlKey: true });
    same(area.value, 'hello second line here', 'Control+K at the end of a paragraph joins the next');
    press(area, 'e', { ctrlKey: true });
    press(area, 'y', { ctrlKey: true });
    same(area.value, 'hello second line here\n', 'Control+Y pastes the last cut');
  });

  test('A Control+letter that arrives as a control character still counts', () => {
    const area = box(true, text, 8);
    press(area, '\u0005', { ctrlKey: true, code: 'KeyE' });
    same(caret(area), [21, 21], 'Control+E ends the paragraph');
  });

  test('Control+letters follow the physical key on a non-Latin layout', () => {
    const area = box(true, text, 8);
    press(area, 'у', { ctrlKey: true, code: 'KeyE' });
    same(caret(area), [21, 21], 'Control+E on a Cyrillic layout ends the paragraph');
  });

  test('Control+T swaps whole characters, emoji included', () => {
    const area = box(true, 'a😀b', 3);
    press(area, 't', { ctrlKey: true });
    same(area.value, 'ab😀', 'The emoji moves as one character');
    area.setSelectionRange(0, 0);
    assert(press(area, 't', { ctrlKey: true }), 'Control+T at the start is still handled');
    same(area.value, 'ab😀', 'Nothing to swap at the start');
  });

  test('Control+H and Control+D delete a character either side', () => {
    const area = box(true, 'abc', 1);
    press(area, 'd', { ctrlKey: true });
    same(area.value, 'ac', 'Control+D deletes forward');
    press(area, 'h', { ctrlKey: true });
    same(area.value, 'c', 'Control+H deletes backward');
  });

  test('Control+T swaps the characters around the caret', () => {
    const area = box(true, 'abcd', 1);
    press(area, 't', { ctrlKey: true });
    same([area.value, caret(area)], ['bacd', [2, 2]], 'Control+T swaps and steps past');
    area.setSelectionRange(4, 4);
    press(area, 't', { ctrlKey: true });
    same([area.value, caret(area)], ['badc', [4, 4]], 'At the end it swaps the last two');
  });

  test('Command+Z undoes and Shift+Command+Z redoes a shortcut edit', () => {
    const area = box(true, 'hello brave world', 11);
    press(area, 'Backspace', { altKey: true });
    press(area, 'z', { metaKey: true });
    same(area.value, 'hello brave world', 'Command+Z restores the word');
    press(area, 'z', { metaKey: true, shiftKey: true });
    same(area.value, 'hello  world', 'Shift+Command+Z deletes it again');
  });

  test('Windows and Linux bindings move and delete by word, line and text', () => {
    const area = box(false, text, 8);
    press(area, 'End');
    same(caret(area), [21, 21], 'End ends the line');
    press(area, 'Home', { shiftKey: true });
    same(caret(area), [0, 21], 'Shift+Home selects to the start of the line');
    press(area, 'End', { ctrlKey: true });
    same(caret(area), [text.length, text.length], 'Control+End ends the text');
    press(area, 'Backspace', { ctrlKey: true });
    same(area.value, 'hello brave new world\nsecond line ', 'Control+Backspace deletes the word');
    press(area, 'z', { ctrlKey: true });
    same(area.value, text, 'Control+Z undoes');
    press(area, 'y', { ctrlKey: true });
    same(area.value, 'hello brave new world\nsecond line ', 'Control+Y redoes');
  });

  test('macOS bindings are not applied on other platforms, nor the reverse', () => {
    assert(!press(box(false, text, 8), 'k', { ctrlKey: true }), 'Control+K is not a Windows binding');
    assert(!press(box(true, text, 8), 'Home'), 'Home is left to the browser on macOS');
  });

  test('Keys typed while an input method composes are left alone', () => {
    const area = box(true, text, 8);
    assert(!press(area, 'ArrowLeft', { altKey: true, isComposing: true }), 'Composition keys pass through');
    same(caret(area), [8, 8], 'The caret stays');
  });

  test('The message box answers the shortcuts', () => {
    const input = document.getElementById('input');
    input.value = 'hello world';
    input.focus();
    input.setSelectionRange(11, 11);
    const wordModifier = /Mac/.test(navigator.platform) ? { altKey: true } : { ctrlKey: true };
    assert(press(input, 'ArrowLeft', wordModifier), 'The word move must be handled');
    same(caret(input), [6, 6], 'The caret moves to the start of the last word');
  });

  document.querySelectorAll('body > textarea:not(#input)').forEach((area) => area.remove());
  return results;
};
