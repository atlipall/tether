window.runComposerMenuTests = function () {
  const results = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const receive = (message) => window.chat.receive(message);
  const test = (name, run) => {
    try { run(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const agents = [{ id: 'claude', label: 'Claude' }, { id: 'acp', label: 'Hermes' }];
  const trigger = (id) => document.getElementById(id);
  const menu = () => document.querySelector('[role="menu"]:not([hidden])');
  const entries = () => [...menu().querySelectorAll('[role="menuitemradio"]')];
  const key = (target, name) => target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
  const sent = (type) => window.__sent.filter((message) => message.type === type);
  window.__sent.length = 0;
  receive({ type: 'reset', items: [] });
  test('an unattached composer advertises no agent modes or Claude model controls', () => {
    assert(trigger('mode').hidden && !menu(), 'Modes must stay hidden until advertised');
    assert(trigger('model').hidden && !trigger('model-dialog').open, 'The model picker must wait for Claude metadata');
    assert(!trigger('input').placeholder.includes('Claude') && !trigger('activity-stop').title.includes('Claude'), 'Initial shared labels must be agent-neutral');
  });
  receive({ type: 'agent', agents, selected: 'claude', locked: false });
  receive({ type: 'modes', modes: [
    { id: 'default', label: 'Ask before edits' },
    { id: 'acceptEdits', label: 'Accept edits' },
    { id: 'plan', label: 'Plan only' },
    { id: 'auto', label: 'Auto' },
  ] });
  receive({ type: 'state', state: 'IDLE', sessionId: null, mode: 'default' });

  test('agent choices open visibly above the composer control', () => {
    const trigger = document.getElementById('agent');
    trigger.click();
    const menu = document.querySelector('[role="menu"]:not([hidden])');
    assert(menu && menu.getBoundingClientRect().height > 0, 'Agent menu must be visible');
    const bounds = menu.getBoundingClientRect();
    assert(bounds.bottom <= trigger.getBoundingClientRect().top, 'Menu must open above its trigger');
    assert(bounds.top >= 0 && bounds.left >= 0 && bounds.right <= innerWidth, 'Menu must stay inside the webview');
    assert(menu.querySelectorAll('[role="menuitemradio"]').length === 2, 'Both agents must be available');
  });

  test('choosing Hermes preserves the draft and attachment and sends one selection', () => {
    trigger('input').value = 'Keep this draft';
    receive({ type: 'attachment', id: 'menu-image', name: 'sample.png' });
    entries().find((entry) => entry.textContent.includes('Hermes')).click();
    assert(sent('setAgent').length === 1 && sent('setAgent')[0].agent === 'acp', 'Hermes must be selected exactly once');
    assert(window.__sent.length === 1 && trigger('agent-label').textContent === 'Claude', 'Choosing must only request an agent change and await host confirmation');
    assert(!menu() && document.activeElement === trigger('agent'), 'Selection must close and return focus');
    assert(trigger('input').value === 'Keep this draft' && document.querySelectorAll('#attachments .tile').length === 1, 'Draft and image must survive selection');
    receive({ type: 'agent', agents, selected: 'acp', locked: false });
    receive({ type: 'modes', modes: [] });
    assert(trigger('input').placeholder.includes('Hermes') && trigger('mode').hidden, 'Controls must follow the selected agent');
    assert(trigger('usage').hidden, 'Choosing an agent must not invent context usage');
  });

  test('sending after selection sends the preserved draft and attachment once', () => {
    trigger('send').click();
    const messages = sent('send');
    assert(messages.length === 1 && messages[0].text === 'Keep this draft', 'One send must contain the draft');
    assert(messages[0].attachments.length === 1 && messages[0].attachments[0] === 'menu-image', 'The image must be sent');
    receive({ type: 'agent', agents, selected: 'acp', locked: true });
    trigger('agent').click();
    assert(trigger('agent').disabled && !menu(), 'An active chat must retain its agent');
    assert(trigger('agent').title.includes('Hermes') && trigger('agent').title.includes('new chat'), 'The locked identity must explain how to choose another agent');
  });

  test('permission choices open upward and send the agent-provided mode id', () => {
    receive({ type: 'modes', modes: [{ id: 'safe', label: 'Ask first' }, { id: 'fast', label: 'Accept edits' }] });
    receive({ type: 'state', state: 'RUNNING', sessionId: 'fixture-session', mode: 'safe' });
    trigger('mode').click();
    assert(menu().getBoundingClientRect().bottom <= trigger('mode').getBoundingClientRect().top, 'Permission menu must open upward');
    assert(entries()[0].getAttribute('aria-checked') === 'true', 'Current mode must be marked');
    entries()[1].click();
    assert(sent('setMode').length === 1 && sent('setMode')[0].mode === 'fast', 'Mode selection must send one exact id');
    assert(!menu() && trigger('mode').textContent.includes('Ask first'), 'The host must confirm a mode change');
    receive({ type: 'state', state: 'RUNNING', sessionId: 'fixture-session', mode: 'fast' });
    assert(!menu() && trigger('mode').textContent.includes('Accept edits'), 'Selected mode must be displayed');
  });

  test('Escape closes without selecting or interrupting and returns focus', () => {
    trigger('mode').click();
    key(document.activeElement, 'Escape');
    assert(!menu() && document.activeElement === trigger('mode'), 'Escape must return focus to the trigger');
    assert(sent('setMode').length === 1 && sent('interrupt').length === 0, 'Dismissal must not change mode or interrupt');
    assert(trigger('mode').getAttribute('aria-expanded') === 'false', 'Expanded state must match dismissal');
  });

  test('arrow keys and Home and End move focus without selecting', () => {
    trigger('mode').focus();
    key(trigger('mode'), 'ArrowDown');
    key(document.activeElement, 'Home');
    assert(document.activeElement === entries()[0], 'Home must focus the first choice');
    key(document.activeElement, 'ArrowUp');
    assert(document.activeElement === entries()[1], 'ArrowUp must wrap to the last choice');
    key(document.activeElement, 'ArrowDown');
    assert(document.activeElement === entries()[0], 'ArrowDown must wrap to the first choice');
    key(document.activeElement, 'End');
    assert(document.activeElement === entries()[1], 'End must focus the last choice');
    key(document.activeElement, 'Tab');
    assert(!menu() && sent('setMode').length === 1, 'Tab must dismiss without selection');
  });

  test('outside click and resize dismiss without selecting', () => {
    trigger('mode').click();
    trigger('input').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    trigger('input').click();
    assert(!menu(), 'An outside click must dismiss');
    trigger('mode').click();
    window.dispatchEvent(new Event('resize'));
    assert(!menu() && sent('setMode').length === 1, 'Resize must dismiss without selection');
  });

  test('only one menu opens and incoming control updates close stale choices', () => {
    receive({ type: 'agent', agents, selected: 'acp', locked: false });
    trigger('agent').click();
    trigger('mode').click();
    assert(document.querySelectorAll('[role="menu"]:not([hidden])').length === 1 && menu().getAttribute('aria-label') === 'Permission mode', 'Only the latest menu must remain');
    receive({ type: 'modes', modes: [] });
    assert(trigger('mode').hidden && !menu(), 'Unavailable modes must dismiss their menu');
    trigger('agent').click();
    receive({ type: 'agent', agents, selected: 'acp', locked: true });
    assert(trigger('agent').disabled && !menu(), 'Locking the agent must dismiss stale choices');
  });

  test('a long menu scrolls its keyboard-focused choice into view', () => {
    receive({ type: 'modes', modes: Array.from({ length: 40 }, (_, index) => ({ id: 'mode-' + index, label: 'Mode ' + index })) });
    receive({ type: 'state', state: 'IDLE', sessionId: null, mode: 'mode-0' });
    trigger('mode').click();
    const bounds = menu().getBoundingClientRect();
    assert(bounds.top >= 0 && bounds.bottom <= trigger('mode').getBoundingClientRect().top && bounds.right <= innerWidth, 'Long menu must remain above the trigger and inside the webview');
    assert(menu().scrollHeight > menu().clientHeight, 'Long choices must scroll rather than overflow');
    key(document.activeElement, 'End');
    const focused = document.activeElement.getBoundingClientRect();
    assert(focused.top >= bounds.top && focused.bottom <= bounds.bottom, 'Keyboard-focused last choice must be visible');
    key(document.activeElement, 'Escape');
  });
  test('Claude retains the native model and effort dialog and host-confirmed choices', () => {
    receive({ type: 'agent', agents, selected: 'claude', locked: false });
    receive({ type: 'model', model: 'default', modelName: 'Opus', effort: 'high', options: [
      { value: 'default', name: 'Default', description: 'Opus · Recommended' },
      { value: 'sonnet', name: 'Sonnet', description: 'Sonnet · Fast' },
    ], efforts: ['low', 'high'] });
    assert(!trigger('model').hidden && trigger('model-label').textContent === 'Opus' && trigger('effort-label').textContent === 'High', 'The composer must show the reported model and effort');
    trigger('model').click();
    const dialog = trigger('model-dialog');
    assert(dialog.open && dialog.matches(':modal'), 'The native modal picker must remain available');
    assert(document.activeElement === dialog.querySelector('.choice[aria-pressed="true"]'), 'The current model must receive focus');
    dialog.querySelector('[data-value="sonnet"]').click();
    dialog.querySelector('[data-value="low"]').click();
    assert(sent('setModel').length === 1 && sent('setModel')[0].model === 'sonnet', 'Model requests must preserve the upstream bridge payload');
    assert(sent('setEffort').length === 1 && sent('setEffort')[0].effort === 'low', 'Effort requests must preserve the upstream bridge payload');
    assert(dialog.querySelector('[data-value="default"]').getAttribute('aria-pressed') === 'true', 'The model selection must await the host');
    receive({ type: 'model', model: 'sonnet', effort: 'low', options: [
      { value: 'sonnet', name: 'Sonnet', description: 'Sonnet · Fast' },
    ], efforts: ['low', 'high'] });
    assert(document.activeElement === dialog.querySelector('.choice[aria-pressed="true"]'), 'A host refresh must retain focus on the current model');
    dialog.close();
  });

  test('changing to a non-Claude agent closes and clears stale model and effort controls', () => {
    trigger('model').click();
    receive({ type: 'agent', agents, selected: 'acp', locked: false });
    assert(trigger('model').hidden && !trigger('model-dialog').open, 'Changing agents must hide and close the Claude model picker');
    assert(!trigger('model-options').childElementCount && !trigger('effort-options').childElementCount && trigger('effort-section').hidden, 'Claude choices must not remain actionable for another agent');
    assert(document.activeElement === trigger('input'), 'Closing a now-unavailable model picker must return focus to the composer');
    receive({ type: 'model', model: 'late-claude-model', options: [{ value: 'late-claude-model', name: 'Stale Claude model' }], efforts: ['high'], effort: 'high' });
    trigger('model').click();
    assert(trigger('model').hidden && !trigger('model-dialog').open && !trigger('model-options').childElementCount, 'Late Claude metadata must not revive the picker for Hermes');
    receive({ type: 'agent', agents, selected: 'claude', locked: false });
    assert(trigger('model').hidden && !trigger('model-options').childElementCount, 'Switching back must await fresh model metadata');
  });
  test('ACP permissions retain advertised choice ids and untrusted labels stay text', () => {
    receive({ type: 'item', item: { id: 'acp-permission', kind: 'tool', name: 'Write', status: 'awaiting_permission', permissionRequestId: 'acp-request', choices: [
      { id: 'allow_once', label: '<b>Allow once</b>' }, { id: 'deny', label: 'Deny' },
    ] } });
    const card = trigger('log-content').lastElementChild.querySelector('.perm');
    assert(card && card.querySelectorAll('button').length === 2, 'ACP must show its advertised permission choices');
    assert(card.querySelector('button').textContent === '<b>Allow once</b>' && !card.querySelector('b'), 'Permission labels must not become markup');
    card.querySelector('button').click();
    const messages = sent('permission');
    assert(messages.length === 1 && messages[0].requestId === 'acp-request' && messages[0].optionId === 'allow_once' && !messages[0].decision, 'ACP permission payloads must preserve the exact option id');
    receive({ type: 'item', item: { id: 'claude-permission', kind: 'tool', name: 'Write', status: 'awaiting_permission', permissionRequestId: 'claude-request' } });
    const legacy = trigger('log-content').lastElementChild.querySelector('.perm');
    legacy.querySelector('[data-action="allowSession"]').click();
    const last = sent('permission').at(-1);
    assert(last.requestId === 'claude-request' && last.decision === 'allowSession' && !last.optionId, 'Claude permissions must keep the existing decision payload');
  });
  test('removing modes while their menu owns focus returns focus to the message box', () => {
    receive({ type: 'modes', modes: [{ id: 'safe', label: 'Ask first' }] });
    receive({ type: 'state', state: 'IDLE', mode: 'safe' });
    trigger('mode').click();
    receive({ type: 'modes', modes: [] });
    assert(!menu() && trigger('mode').hidden, 'Removed capabilities must close and hide the control');
    assert(document.activeElement === trigger('input'), 'Focus must not be stranded on an unavailable control');
  });
  test('agent-provided ids and labels survive state-before-capabilities and keyboard selection', () => {
    const label = '<b>Ask first</b>';
    const before = sent('setMode').length;
    receive({ type: 'state', state: 'IDLE', mode: 'custom:safe mode' });
    receive({ type: 'modes', modes: [{ id: 'custom:safe mode', label }, { id: 'custom:fast mode', label: 'Fast' }] });
    trigger('mode').click();
    assert(entries()[0].getAttribute('aria-checked') === 'true' && document.activeElement === entries()[0], 'An earlier state must select the subsequently advertised mode');
    assert(trigger('mode-label').textContent === label && !menu().querySelector('b'), 'Mode labels must remain plain text');
    key(document.activeElement, 'Enter');
    assert(!menu() && sent('setMode').length === before, 'Activating the current mode must dismiss without a redundant request');
    trigger('mode').click();
    key(document.activeElement, 'End');
    key(document.activeElement, ' ');
    assert(!menu() && sent('setMode').length === before + 1 && sent('setMode').at(-1).mode === 'custom:fast mode', 'Space must send the exact agent-provided id once');
    receive({ type: 'state', state: 'IDLE', mode: 'custom:safe mode' });
    assert(trigger('mode-label').textContent === label, 'A rejected choice must leave the reported mode visible');
  });

  test('a long agent list opens at its selected entry inside the webview', () => {
    const choices = Array.from({ length: 40 }, (_, index) => ({ id: 'agent-' + index, label: 'Agent ' + index }));
    choices[39].label = '<b>Selected agent</b> ' + 'Long name '.repeat(20);
    receive({ type: 'agent', agents: choices, selected: 'agent-39', locked: false });
    trigger('agent').click();
    const bounds = menu().getBoundingClientRect();
    const focused = document.activeElement.getBoundingClientRect();
    assert(document.activeElement === entries()[39] && entries()[39].getAttribute('aria-checked') === 'true', 'Opening must focus the current agent, not the first option');
    assert(bounds.top >= 0 && bounds.left >= 0 && bounds.right <= innerWidth && bounds.bottom <= trigger('agent').getBoundingClientRect().top, 'Long agent menus must remain above the trigger inside the webview');
    assert(menu().scrollHeight > menu().clientHeight && focused.top >= bounds.top && focused.bottom <= bounds.bottom, 'The selected last agent must be visible in the scrolling list');
    assert(!trigger('agent').querySelector('b') && !menu().querySelector('b') && document.documentElement.scrollWidth <= innerWidth, 'Long labels must remain text and cannot widen the webview');
    key(document.activeElement, 'Escape');
    receive({ type: 'agent', agents, selected: 'claude', locked: false });
  });
  test('clicking a chat image asks the IDE to open it in the editor, with no viewer in the page', () => {
    const tiny = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    receive({ type: 'item', item: { kind: 'user', id: 'u-img', text: 'look', images: [tiny] } });
    const img = document.querySelector('[data-id="u-img"] img.openable') || [...document.querySelectorAll('img.openable')].at(-1);
    assert(img, 'A sent image must be openable');
    window.__sent.length = 0;
    img.click();
    const asked = sent('openImage');
    assert(asked.length === 1 && asked[0].item === 'u-img' && asked[0].index === 0, 'A click must ask for that image of that message');
    assert(!document.querySelector('dialog[open]') && !document.getElementById('image-viewer'), 'No in-page viewer may open');
    window.__sent.length = 0;
    img.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    assert(sent('openImage').length === 1, 'Enter must open it too');
  });
  test('files dragged over the panel outline the message box until the drag leaves', () => {
    const box = trigger('input-box');
    receive({ type: 'dropTarget', active: true });
    assert(box.classList.contains('drop-target'), 'A file drag the IDE will take must mark the message box');
    assert(getComputedStyle(box).outlineStyle === 'dashed', 'The drop target must be outlined');
    receive({ type: 'dropTarget', active: false });
    assert(!box.classList.contains('drop-target'), 'The outline must go when the drag leaves or drops');
  });
  return results;
};
