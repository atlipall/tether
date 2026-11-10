window.runTasksTests = function () {
  const results = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const receive = (message) => window.chat.receive(message);
  const test = (name, run) => {
    try { run(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const tasks = () => document.getElementById('tasks');
  const log = () => document.getElementById('log-content');
  const activity = () => document.getElementById('activity');
  const sent = (type) => window.__sent.filter((message) => message.type === type);
  const agentCall = (id, state, extra) => ({
    id, kind: 'tool', name: 'Agent', input: { description: 'Review ' + id }, status: state === 'running' ? 'running' : 'done',
    agent: Object.assign({ subagentType: 'apiary-expert', background: true, state, toolUses: 3, durationMs: 4000, taskId: 'task-' + id }, extra || {}),
  });
  const head = () => tasks().querySelector('.agents-head');
  const earlier = () => tasks().querySelector('.agents-earlier');
  const card = (id) => [...tasks().querySelectorAll(':scope > .agent')].find((el) => el.querySelector('.agent-head').dataset.id === id);
  window.__sent.length = 0;
  receive({ type: 'reset', items: [] });

  test('the task rows stay hidden with nothing running', () => {
    assert(tasks().hidden, 'An empty task area must be hidden');
  });

  receive({ type: 'items', items: [{ id: 'u1', kind: 'user', text: 'review the branches' }, agentCall('a1', 'running'), agentCall('a2', 'running')] });

  test('top-level agents are pinned above the message box with a line left in the log', () => {
    assert(!tasks().hidden, 'Running agents must show the task rows');
    assert(card('a1') && card('a2'), 'Both agents must be pinned');
    assert(!log().querySelector('.agent'), 'No agent card may stay in the log');
    assert(log().querySelectorAll('.agent-stub').length === 2, 'Each agent must leave a line in the log');
    assert([...log().querySelectorAll('.agent-stub')].every((stub) => stub.hidden), 'A line must not repeat an agent shown above the message box');
    assert(card('a1').querySelector('.tool-name').textContent === 'apiary-expert', 'A pinned agent is labelled with its type');
  });

  test('a running agent can be stopped by its task id', () => {
    const stop = card('a1').querySelector('[data-action="stopTask"]');
    assert(stop, 'A running agent must offer Stop');
    stop.click();
    assert(sent('stopTask').length === 1 && sent('stopTask')[0].id === 'task-a1', 'Stop must name the agent\'s task');
    receive({ type: 'item', item: agentCall('a1', 'running', { toolUses: 4 }) });
    assert(card('a1').querySelector('[data-action="stopTask"]').disabled, 'Stop must stay disabled after a re-render');
  });

  receive({ type: 'item', item: agentCall('a1', 'done') });

  test('a finished agent sorts after running ones and can be dismissed', () => {
    assert(getComputedStyle(card('a1')).order > getComputedStyle(card('a2')).order, 'Finished agents must sort last');
    assert(!card('a1').querySelector('[data-action="stopTask"]'), 'A finished agent offers no Stop');
    card('a1').querySelector('[data-action="dismissTask"]').click();
    assert(card('a1').hidden, 'Dismiss must hide the finished agent');
    assert(!tasks().hidden, 'The running agent keeps the rows shown');
  });

  test('the log line brings a dismissed agent back, opened', () => {
    assert(!log().querySelector('.agent-stub .tool-row[data-id="a1"]').parentNode.hidden, 'A dismissed agent\'s line must show');
    log().querySelector('.agent-stub .tool-row[data-id="a1"]').click();
    assert(!card('a1').hidden, 'The agent must show again');
    assert(!card('a1').querySelector('.agent-body').hidden, 'The agent must open');
    assert(log().querySelector('.agent-stub .tool-row[data-id="a1"]').parentNode.hidden, 'The line must hide again once the agent shows');
  });

  receive({ type: 'item', item: agentCall('a2', 'done') });
  receive({ type: 'item', item: { id: 'u2', kind: 'user', text: 'thanks' } });

  test('a new message folds the agent box when no agent runs, and keeps it', () => {
    assert(!tasks().hidden && !head().hidden, 'The box must stay for the rest of the session');
    assert(head().getAttribute('aria-expanded') === 'false', 'The box must fold');
    assert(head().querySelector('.tool-desc').textContent === '2 done', 'The header must count the agents');
    assert(card('a1').hidden && card('a2').hidden, 'A folded box shows no agents');
    assert([...log().querySelectorAll('.agent-stub')].every((stub) => !stub.hidden), 'Folded agents must keep their lines in the log');
  });

  test('the header unfolds and folds the box', () => {
    head().click();
    assert(head().getAttribute('aria-expanded') === 'true' && !card('a1').hidden && !card('a2').hidden, 'Unfolding must show the agents');
    head().click();
    assert(card('a1').hidden && card('a2').hidden, 'Folding must hide them again');
  });

  receive({ type: 'activity', phase: null });
  receive({ type: 'processes', processes: [{ id: 'b1', kind: 'Bash', description: 'npm run dev', startedAt: Date.now() - 65000 }] });

  test('a background process shows with its running time and a Stop, without the activity strip', () => {
    const row = tasks().querySelector('.process');
    assert(!tasks().hidden && row, 'The process must show');
    assert(row.querySelector('.tool-name').textContent === 'Bash', 'The row is labelled with its kind');
    assert(row.querySelector('.process-time').textContent === '1m 5s', 'The row shows how long it has run');
    assert(activity().hidden, 'Background work alone shows no activity strip');
    row.querySelector('[data-action="stopTask"]').click();
    assert(sent('stopTask').some((m) => m.id === 'b1'), 'Stop must name the process');
  });

  test('the activity strip still shows while the model works', () => {
    receive({ type: 'activity', phase: 'thinking' });
    assert(!activity().hidden, 'Thinking must show the strip');
    receive({ type: 'activity', phase: null });
  });

  receive({ type: 'processes', processes: [] });

  test('a process that ends leaves the rows', () => {
    assert(!tasks().querySelector('.process'), 'The ended process must go');
    assert(!head().hidden && card('a1').hidden, 'Only the folded agent box must stay');
  });

  receive({ type: 'items', items: [{ id: 'u3', kind: 'user', text: 'check again' }, agentCall('a3', 'running')] });

  test('a message that starts an agent opens the box with the earlier agents', () => {
    assert(head().getAttribute('aria-expanded') === 'true', 'A new agent must open the box');
    assert(!card('a1').hidden && !card('a2').hidden && !card('a3').hidden, 'A short list shows every agent');
    assert(head().querySelector('.tool-desc').textContent === '1 running \u00b7 2 done', 'The header must count by state');
  });

  receive({ type: 'item', item: { id: 'u4', kind: 'user', text: 'meanwhile' } });

  test('a running agent keeps the box open on a new message', () => {
    assert(head().getAttribute('aria-expanded') === 'true' && !card('a3').hidden, 'The running agent must stay in view');
  });

  receive({ type: 'items', items: ['a4', 'a5', 'a6'].map((id) => agentCall(id, 'running')) });

  test('a long list leaves out finished agents of earlier messages', () => {
    assert(card('a1').hidden && card('a2').hidden, 'Earlier finished agents must be left out');
    assert(!card('a3').hidden && !card('a6').hidden, 'Running agents must show');
    assert(!earlier().hidden && earlier().textContent === 'Show 2 earlier agents', 'The box must offer the left-out agents');
    earlier().click();
    assert(!card('a1').hidden && !card('a2').hidden && earlier().hidden, 'Asking must bring them back');
  });

  receive({ type: 'item', item: { id: 'u5', kind: 'user', text: 'and now' } });

  test('the log line brings back a left-out agent', () => {
    assert(card('a1').hidden, 'A new message leaves earlier agents out again');
    log().querySelector('.agent-stub .tool-row[data-id="a1"]').click();
    assert(!card('a1').hidden && card('a2').hidden, 'Only the asked-for agent must come back');
  });

  receive({ type: 'reset', items: [] });

  test('a reset clears the agent box', () => {
    assert(tasks().hidden && head().hidden, 'An empty chat has no agent box');
  });

  return results;
};
