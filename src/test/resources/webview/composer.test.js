window.runComposerTests = async function () {
  const results = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const test = async (name, run) => {
    try { await run(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const receive = (message) => window.chat.receive(message);
  const input = document.getElementById('input');
  const sent = (type) => window.__sent.filter((message) => message.type === type);
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const type = (text) => {
    input.focus();
    document.execCommand('insertText', false, text);
  };
  const clear = () => {
    input.value = '';
    input.dispatchEvent(new Event('input'));
    window.__sent.length = 0;
  };

  receive({ type: 'reset', items: [] });
  receive({ type: 'agent', agents: [{ id: 'claude', label: 'Claude' }], selected: 'claude', locked: false });
  receive({ type: 'state', state: 'IDLE', sessionId: null, mode: 'default' });

  await test('typing reports the unsent text once the box is still', async () => {
    clear();
    type('half');
    type(' a thought');
    assert(sent('unsentText').length === 0, 'Nothing is reported mid-burst');
    await wait(600);
    const reports = sent('unsentText');
    assert(reports.length === 1 && reports[0].text === 'half a thought', `One report of the whole text: ${JSON.stringify(reports)}`);
  });

  await test('leaving the box reports its text at once', async () => {
    clear();
    type('leaving now');
    input.blur();
    assert(sent('unsentText').some((m) => m.text === 'leaving now'), 'Blur reports the text');
  });

  await test('unsent text from the host fills an empty box', async () => {
    clear();
    receive({ type: 'unsentText', text: 'from before the reload' });
    assert(input.value === 'from before the reload', 'The text is restored');
  });

  await test('unsent text from the host never replaces what the user typed', async () => {
    clear();
    type('typed here');
    receive({ type: 'unsentText', text: 'stale' });
    assert(input.value === 'typed here', 'The typed text stays');
  });

  const list = document.getElementById('completion');
  const rows = () => [...list.querySelectorAll('[role="option"]')];
  const labels = () => rows().map((row) => row.querySelector('.completion-label').textContent);
  const selected = () => list.querySelector('[aria-selected="true"] .completion-label').textContent;
  const key = (name, modifiers = {}) => {
    const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true, ...modifiers });
    input.dispatchEvent(event);
    return event.defaultPrevented;
  };
  receive({ type: 'commands', items: [
    { name: 'simplify', description: 'Simplify code', hint: '' },
    { name: 'review', description: 'Review a pull request', hint: '<pr>' },
    { name: 'security-review', description: 'Review security', hint: '' },
  ] });

  await test('a slash at the start lists every command', () => {
    clear();
    type('/');
    assert(!list.hidden, 'The list opens');
    assert(input.getAttribute('aria-activedescendant') === rows()[0].id, 'The box points at the selected row');
    assert(document.getElementById('completion-status').textContent === '3 suggestions', 'The count is announced');
    assert(labels().join() === '/simplify,/review <pr>,/security-review', `All commands, hint included: ${labels()}`);
  });

  await test('typing narrows the list, names starting with it first', () => {
    clear();
    type('/rev');
    assert(labels().join() === '/review <pr>,/security-review', `Prefix match first: ${labels()}`);
  });

  await test('arrows move the selection and Enter inserts the command', () => {
    clear();
    type('/rev');
    assert(key('ArrowDown'), 'Down belongs to the list');
    assert(selected() === '/security-review', 'Down moves to the next row');
    assert(key('Enter'), 'Enter belongs to the list');
    assert(input.value === '/security-review ', `The command replaces what was typed: ${JSON.stringify(input.value)}`);
    assert(list.hidden, 'The list closes');
    assert(sent('send').length === 0, 'Enter does not send while choosing');
  });

  await test('Tab accepts and wraps from the top to the bottom', () => {
    clear();
    type('/');
    key('ArrowUp');
    assert(selected() === '/security-review', 'Up from the first row wraps to the last');
    key('Tab');
    assert(input.value === '/security-review ', 'Tab accepts');
  });

  await test('Escape closes the list until the word changes', () => {
    clear();
    type('/re');
    assert(key('Escape'), 'Escape belongs to the open list');
    assert(list.hidden, 'Escape closes it');
    type('v');
    assert(list.hidden, 'It stays closed while finishing the same word');
    clear();
    type('/');
    assert(!list.hidden, 'A new word opens it again');
  });

  await test('no match says so, and Enter then sends as usual', () => {
    clear();
    type('/zzz');
    assert(!list.hidden && list.textContent.includes('No matching commands'), 'The empty state is shown');
    key('Enter');
    assert(sent('send').length === 1 && sent('send')[0].text === '/zzz', 'Enter sends the message');
  });

  await test('a slash after other text, or past the first word, lists nothing', () => {
    clear();
    type('see /rev');
    assert(list.hidden, 'Not mid-message');
    clear();
    type('/review 12');
    assert(list.hidden, 'Not once arguments are being typed');
  });

  await test('accepting is one undo step', () => {
    clear();
    type('/sim');
    key('Enter');
    document.execCommand('undo');
    assert(input.value === '/sim', `Undo brings back what was typed: ${JSON.stringify(input.value)}`);
  });

  const answerFiles = (items) => {
    const ask = sent('findFiles').pop();
    receive({ type: 'files', reqId: ask.reqId, items });
    return ask;
  };
  const files = [
    { mention: '@src/webview/chat.js', name: 'chat.js', folder: 'src/webview' },
    { mention: '@src/ChatBridge.kt', name: 'ChatBridge.kt', folder: 'src' },
  ];

  await test('an @ asks the IDE for files once typing pauses and lists them', async () => {
    clear();
    type('look at @ch');
    type('a');
    assert(sent('findFiles').length === 0, 'No request mid-burst');
    await wait(200);
    assert(sent('findFiles').length === 1, 'One request once typing pauses');
    const ask = answerFiles(files);
    assert(ask.query === 'cha', `The typed part is the query: ${ask.query}`);
    assert(labels().join() === 'chat.js,ChatBridge.kt', `The files are listed: ${labels()}`);
    assert(list.querySelector('.completion-detail').textContent === 'src/webview', 'Each shows its folder');
  });

  await test('accepting a file inserts its mention in place of what was typed', async () => {
    clear();
    type('see @cha');
    await wait(200);
    answerFiles(files);
    key('Enter');
    assert(input.value === 'see @src/webview/chat.js ', `The mention replaces the query: ${JSON.stringify(input.value)}`);
  });

  await test('an answer to an older query is ignored', async () => {
    clear();
    type('@a');
    await wait(200);
    const old = sent('findFiles').pop();
    type('b');
    await wait(200);
    receive({ type: 'files', reqId: old.reqId, items: [files[1]] });
    answerFiles([files[0]]);
    assert(labels().join() === 'chat.js', `Only the latest answer shows: ${labels()}`);
  });

  await test('an @ inside a word is not a mention', async () => {
    clear();
    type('me@example');
    await wait(200);
    assert(sent('findFiles').length === 0 && list.hidden, 'No file search for an email address');
  });

  await test('Escape in an empty box stops a running turn', () => {
    clear();
    receive({ type: 'activity', phase: 'thinking' });
    assert(key('Escape'), 'Escape is handled');
    assert(sent('interrupt').length === 1, 'The turn is stopped once');
    receive({ type: 'activity', phase: null });
  });

  await test('Escape stops nothing while the box has text or nothing runs', () => {
    clear();
    receive({ type: 'activity', phase: 'thinking' });
    type('still typing');
    key('Escape');
    receive({ type: 'activity', phase: null });
    clear();
    key('Escape');
    assert(sent('interrupt').length === 0, 'No interrupt');
  });

  await test('Escape closes an open list before it stops anything', () => {
    clear();
    receive({ type: 'activity', phase: 'thinking' });
    type('/');
    key('Escape');
    assert(list.hidden && sent('interrupt').length === 0, 'The list closes and the turn runs on');
    receive({ type: 'activity', phase: null });
  });

  await test('undo straight after sending brings the text back', () => {
    clear();
    type('sent too soon');
    key('Enter');
    assert(input.value === '' && sent('send').length === 1, 'The message is sent and the box emptied');
    document.execCommand('undo');
    assert(input.value === 'sent too soon', `Undo restores it: ${JSON.stringify(input.value)}`);
  });

  await test('sending with the button also leaves the text undoable', () => {
    clear();
    type('by button');
    document.getElementById('send').click();
    assert(input.value === '' && document.activeElement === input, 'The box is emptied and keeps focus');
    document.execCommand('undo');
    assert(input.value === 'by button', 'Undo restores it');
  });

  await test('the placeholder names the agent and nothing more', () => {
    assert(input.placeholder === 'Message Claude', `Short placeholder: ${input.placeholder}`);
  });

  await test('the hint shows only while the box is focused and empty', () => {
    const hint = document.getElementById('input-hint');
    const shown = () => getComputedStyle(hint).visibility === 'visible';
    clear();
    input.focus();
    assert(shown(), 'Shown in a focused empty box');
    type('x');
    assert(!shown(), 'Hidden once there is text');
    clear();
    input.blur();
    assert(!shown(), 'Hidden when the box is not focused');
    assert(input.getAttribute('aria-describedby') === 'input-hint', 'The box is described by the hint');
  });

  await test('arrow keys stay with the box when nothing matches', () => {
    clear();
    type('/zzz');
    assert(!key('ArrowUp'), 'Up is not swallowed by an empty list');
  });

  await test('rows for an older @ query cannot be chosen while a new one is on its way', async () => {
    clear();
    type('@cha');
    await wait(200);
    answerFiles(files);
    type('t');
    key('Enter');
    assert(input.value === '@chat', `Enter does not pick a stale row: ${JSON.stringify(input.value)}`);
    await wait(200);
    answerFiles([files[0]]);
    key('Enter');
    assert(input.value === '@src/webview/chat.js ', 'The fresh row can be chosen');
  });

  await test('Escape does not stop a turn that is waiting on the user', () => {
    clear();
    receive({ type: 'activity', phase: 'waiting' });
    key('Escape');
    assert(sent('interrupt').length === 0, 'No interrupt while waiting');
    receive({ type: 'activity', phase: null });
  });

  await test('switching agent drops the old agent\'s commands and the hint follows', () => {
    const hintCommands = document.getElementById('hint-commands');
    assert(!hintCommands.hidden, 'The hint mentions / while there are commands');
    receive({ type: 'agent', agents: [{ id: 'claude', label: 'Claude' }, { id: 'acp', label: 'Hermes' }], selected: 'acp', locked: false });
    clear();
    type('/');
    assert(list.hidden, 'No commands are offered for the new agent');
    assert(hintCommands.hidden, 'The hint no longer mentions /');
    receive({ type: 'agent', agents: [{ id: 'claude', label: 'Claude' }], selected: 'claude', locked: false });
  });

  await test('restored text takes the place of a suggestion', () => {
    clear();
    receive({ type: 'suggestion', text: 'run the tests' });
    receive({ type: 'unsentText', text: 'my own words' });
    assert(input.placeholder === 'Message Claude', `The suggestion is gone: ${input.placeholder}`);
  });

  clear();
  return results;
};
