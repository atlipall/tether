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

  await test('Up in an empty box recalls, and Up and Down step through sent messages', () => {
    clear();
    receive({ type: 'history', entries: ['first', 'second\nline'] });
    assert(key('ArrowUp') && input.value === 'second\nline', 'Up recalls the last message');
    assert(key('ArrowUp') && input.value === 'first', 'Up again steps back, wherever the caret is');
    assert(key('ArrowDown') && input.value === 'second\nline', 'Down steps forward');
    assert(key('ArrowDown') && input.value === '', 'Down past the newest returns to the empty box');
  });

  await test('Up and Down move the caret in text being written, even on its first or last line', () => {
    clear();
    receive({ type: 'history', entries: ['old'] });
    type('line one\nline two');
    input.setSelectionRange(3, 3);
    assert(!key('ArrowUp'), 'Up on the first line is left to the box');
    input.setSelectionRange(input.value.length, input.value.length);
    assert(!key('ArrowDown'), 'Down on the last line is left to the box');
    assert(input.value === 'line one\nline two', 'The text stays');
  });

  await test('moving the caret in a recalled message ends recall', () => {
    clear();
    receive({ type: 'history', entries: ['first', 'second'] });
    key('ArrowUp');
    key('ArrowLeft');
    assert(!key('ArrowUp') && input.value === 'second', 'Up now moves the caret');
    clear();
    key('ArrowUp');
    input.dispatchEvent(new PointerEvent('pointerdown'));
    assert(!key('ArrowUp') && input.value === 'second', 'A click ends recall too');
    clear();
    key('ArrowUp');
    key('ArrowUp', { shiftKey: true });
    assert(!key('ArrowUp') && input.value === 'second', 'Selecting with Shift+Up ends recall');
    clear();
    key('ArrowUp');
    key('Shift');
    assert(key('ArrowUp') && input.value === 'first', 'A lone modifier keeps recall going');
    clear();
    assert(!key('ArrowDown'), 'Down in an empty box does nothing');
  });

  const sendMessage = (text, id) => {
    clear();
    type(text);
    key('Enter');
    receive({ type: 'item', item: { kind: 'user', id, text, revertable: true } });
    receive({ type: 'activity', phase: 'thinking' });
    window.__sent.length = 0;
  };

  await test('Escape right after sending stops the turn and brings the text back', () => {
    sendMessage('take this back', 'u1');
    assert(key('Escape'), 'Escape is handled');
    assert(input.value === 'take this back', `The text is back: ${JSON.stringify(input.value)}`);
    assert(sent('interrupt').length === 1, 'The running turn is stopped');
    receive({ type: 'activity', phase: null });
  });

  await test('a second Escape asks to revert to before that message', () => {
    sendMessage('revert me', 'u2');
    key('Escape');
    receive({ type: 'activity', phase: null });
    assert(key('Escape'), 'The second Escape is handled');
    const asks = sent('revertTo');
    assert(asks.length === 1 && asks[0].id === 'u2', `Revert to here for the last message: ${JSON.stringify(asks)}`);
  });

  await test('the rewind that follows does not put the text in twice', () => {
    sendMessage('only once', 'u3');
    key('Escape');
    receive({ type: 'activity', phase: null });
    key('Escape');
    receive({ type: 'insert', text: 'only once' });
    assert(input.value === 'only once', `The text appears once: ${JSON.stringify(input.value)}`);
  });

  await test('the rewind copy is recognised though the sent text was trimmed', () => {
    sendMessage('padded  ', 'u6');
    key('Escape');
    receive({ type: 'activity', phase: null });
    key('Escape');
    receive({ type: 'insert', text: 'padded' });
    assert(input.value === 'padded  ', `No second copy: ${JSON.stringify(input.value)}`);
  });

  await test('a message sent while a turn runs is queued, and Escape leaves it and the turn alone', () => {
    clear();
    receive({ type: 'activity', phase: 'thinking' });
    type('for later');
    key('Enter');
    window.__sent.length = 0;
    key('Escape');
    assert(input.value === '', 'The queued text is not brought back');
    assert(sent('interrupt').length === 1, 'Escape in the empty box only stops the turn, as before');
    receive({ type: 'activity', phase: null });
  });

  await test('taking back a message stops a turn even while it waits for an answer', () => {
    sendMessage('asked a question', 'u7');
    receive({ type: 'activity', phase: 'waiting' });
    key('Escape');
    assert(input.value === 'asked a question' && sent('interrupt').length === 1, 'Text back and the turn stopped');
    receive({ type: 'activity', phase: null });
  });

  await test('the second Escape waits for the turn to stop before asking to revert', () => {
    sendMessage('slow to stop', 'u8');
    key('Escape');
    assert(key('Escape') && sent('revertTo').length === 0, 'No revert while the turn is still stopping');
    receive({ type: 'activity', phase: null });
    key('Escape');
    assert(sent('revertTo').length === 1 && sent('revertTo')[0].id === 'u8', 'It asks once the turn has stopped');
  });

  await test('the second Escape never reverts an older message', () => {
    sendMessage('older', 'u9');
    receive({ type: 'activity', phase: null });
    clear();
    type('newer, not shown yet');
    key('Enter');
    window.__sent.length = 0;
    key('Escape');
    assert(input.value === 'newer, not shown yet', 'The newer message is brought back');
    key('Escape');
    assert(sent('revertTo').length === 0, 'No revert for a message the chat does not show yet');
  });

  await test('a message that cannot be reverted is only brought back', () => {
    clear();
    type('no checkpoint');
    key('Enter');
    receive({ type: 'item', item: { kind: 'user', id: 'u10', text: 'no checkpoint', revertable: false } });
    key('Escape');
    assert(!key('Escape') && sent('revertTo').length === 0, 'The second Escape does nothing');
  });

  await test('a reset, recall or an image-only send leaves nothing to take back', () => {
    sendMessage('before reset', 'u11');
    receive({ type: 'activity', phase: null });
    receive({ type: 'reset', items: [] });
    key('Escape');
    assert(input.value === '', 'Nothing comes back after a reset');
    sendMessage('recalled', 'u12');
    receive({ type: 'activity', phase: null });
    receive({ type: 'history', entries: ['recalled'] });
    key('ArrowUp');
    key('ArrowDown');
    key('Escape');
    assert(input.value === '', 'Nothing comes back after recall returned to the empty box');
  });

  await test('brought-back text that starts a command leaves Escape free for the revert', () => {
    sendMessage('/simplify', 'u13');
    key('Escape');
    receive({ type: 'activity', phase: null });
    assert(list.hidden, 'No list opens for the brought-back text');
    key('Escape');
    assert(sent('revertTo').length === 1, 'The second Escape asks to revert');
  });

  await test('the rewind copy is skipped across the reset the rewind sends first', () => {
    sendMessage('kept once', 'u14');
    key('Escape');
    receive({ type: 'activity', phase: null });
    key('Escape');
    receive({ type: 'reset', items: [] });
    receive({ type: 'insert', text: 'kept once' });
    assert(input.value === 'kept once', `One copy: ${JSON.stringify(input.value)}`);
  });

  await test('Escape brings nothing back once the user has typed since sending', () => {
    sendMessage('sent', 'u4');
    type('x');
    input.value = '';
    input.dispatchEvent(new Event('input'));
    window.__sent.length = 0;
    key('Escape');
    assert(input.value === '', 'The sent text stays sent');
    assert(sent('interrupt').length === 1, 'Escape still stops the turn');
    receive({ type: 'activity', phase: null });
  });

  await test('editing the brought-back text makes Escape a plain key again', () => {
    sendMessage('edit me', 'u5');
    key('Escape');
    receive({ type: 'activity', phase: null });
    type('!');
    key('Escape');
    assert(sent('revertTo').length === 0, 'No revert once the text was edited');
  });

  clear();
  return results;
};
