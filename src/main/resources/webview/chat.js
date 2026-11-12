(function () {
  'use strict';
  /** The element of every item shown, by item id. */
  const items = new Map();
  /** The scroller. */
  const log = document.getElementById('log');
  /** What [log] scrolls: the items. */
  const logContent = document.getElementById('log-content');
  const toBottom = document.getElementById('to-bottom');
  const input = document.getElementById('input');
  const sendBtn = document.getElementById('send');
  const agentPicker = { button: document.getElementById('agent'), menu: document.getElementById('agent-menu'), label: document.getElementById('agent-label'), name: 'Agent' };
  const modePicker = { button: document.getElementById('mode'), menu: document.getElementById('mode-menu'), label: document.getElementById('mode-label'), name: 'Permission mode' };
  const pickers = [agentPicker, modePicker];
  /** The session's reported identity and permission mode; choosing only requests a change from the host. */
  let agent = '', mode = '', agentName = 'the agent';
  const modelBtn = document.getElementById('model');
  const modelLabel = document.getElementById('model-label');
  const effortLabel = document.getElementById('effort-label');
  const modelDialog = document.getElementById('model-dialog');
  const modelOptions = document.getElementById('model-options');
  const effortSection = document.getElementById('effort-section');
  const effortOptions = document.getElementById('effort-options');
  const activityEl = document.getElementById('activity');
  const activityDots = document.getElementById('activity-dots');
  const activityLabel = document.getElementById('activity-label');
  const activitySecs = document.getElementById('activity-secs');
  const activityStop = document.getElementById('activity-stop');
  const restartBtn = document.getElementById('restart');
  const statusEl = document.getElementById('status');
  const checkoutEl = document.getElementById('checkout');
  const branchBar = document.getElementById('branch-bar');
  const branchBarText = document.getElementById('branch-bar-text');
  const branchBarNote = document.getElementById('branch-bar-note');
  const branchSwitchBtn = document.getElementById('branch-switch');
  const branchDismissBtn = document.getElementById('branch-dismiss');
  const openPrBtn = document.getElementById('open-pr');
  const openPrNumber = document.getElementById('open-pr-number');
  const usageEl = document.getElementById('usage');
  const usageRing = document.getElementById('usage-ring');
  const usagePct = document.getElementById('usage-pct');
  const queueEl = document.getElementById('queue');
  const tasksEl = document.getElementById('tasks');
  const attachEl = document.getElementById('attachments');
  const attachBtn = document.getElementById('attach');
  /** The tiles in the attachment tray, by attachment id: { el, ready }. */
  const pending = new Map();
  let attachSeq = 0;
  const pendingResolve = new Map();
  let reqSeq = 0;
  /** Sent messages, oldest first: shared by every tab and kept by the IDE. */
  let history = [];
  /** The slash commands the agent offers, as `{ name, description, hint }`; empty until it reports them. */
  let commands = [];
  /**
   * Index into [history] while a recalled message is shown untouched, -1 otherwise. Recall starts with Up in an empty
   * box; typing, clicking in the box or any key but plain Up and Down ends it, and the arrows then move the caret
   * until the box is empty again.
   */
  let recall = -1;
  /**
   * The message just sent, while the box has stayed empty since: Escape brings it back. Null once the box changes, the
   * chat is reset, or for a message that went into the queue (its row's Edit takes it back) or had no text.
   */
  let sentText = null;
  /**
   * The text Escape brought back, while the box still holds it untouched: another Escape asks to revert to before that
   * message. Null once the box is edited.
   */
  let takenBack = null;
  /** The text a requested revert will put back in the box, which already holds it; that copy is not added again. */
  let rewinding = null;
  /** The predicted next prompt shown in the empty box; Tab takes it. */
  let suggestion = '';
  /** The last state was IDLE: a suggestion that lands later than a new turn's state is stale. */
  let idle = false;

  /** The header says nothing else; the strip above the input shows the turn. */
  const STATUS = { DEAD: 'Stopped' };
  const TOOL_STATUS = { running: 'running', awaiting_permission: 'needs permission', done: 'done', error: 'failed', denied: 'denied' };
  const PATH_RE = /^[\w@.\/~-]*[\w-]\.[A-Za-z0-9]{1,10}(?:(?::|#L)\d+(?:[:-]\d+)?)?$/;
  const MAX_PRE = 6000;
  /** Images per message; the IDE enforces it too. */
  const MAX_ATTACH = 10;
  /** Base64 characters per bridge message (a multiple of 4). */
  const CHUNK = 262144;
  /** The IDE refuses larger files without reading them. */
  const MAX_INPUT = 20 * 1024 * 1024;
  const DATA_IMG = /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+\/]+=*$/;
  const SANITIZE = {
    FORBID_TAGS: ['style', 'form', 'button', 'input', 'textarea', 'select', 'option', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'img', 'picture', 'source', 'video', 'audio', 'track', 'svg', 'math'],
    FORBID_ATTR: ['style', 'class', 'id'],
    ALLOW_DATA_ATTR: false,
  };
  /** Elements created by h(): the only ones the click handler acts on, as model markup is never trusted. */
  const trusted = new WeakSet();
  /**
   * Markdown images never become <img> in model markup: the renderer leaves a marker (unguessable per page load) that
   * survives sanitizing as text; the page swaps it for alt text, and for a trusted <img> once the IDE resolves the file.
   */
  const IMG_NONCE = Array.from(crypto.getRandomValues(new Uint32Array(2)), (n) => n.toString(36)).join('');
  const IMG_MARK = new RegExp('IMG' + IMG_NONCE + '_(\\d+)_', 'g');
  /** The image tokens of the message being parsed. */
  let imgSlots = null;
  marked.use({ renderer: { image(token) {
    if (!imgSlots) return '';
    imgSlots.push({ href: String(token.href || ''), alt: String(token.text || '') });
    return 'IMG' + IMG_NONCE + '_' + (imgSlots.length - 1) + '_';
  } } });

  function send(msg) { if (window.__send) window.__send(JSON.stringify(msg)); }

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    trusted.add(el);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v; else el.setAttribute(k, v === true ? '' : String(v));
    }
    for (const c of children) if (c !== null && c !== undefined) el.append(c instanceof Node ? c : String(c));
    return el;
  }
  const clip = (s) => (s && s.length > MAX_PRE ? s.slice(0, MAX_PRE) + '\n…' : s || '');

  /** A web address in plain text: http or https up to whitespace or a character that can't be part of one. */
  const URL_RE = /\bhttps?:\/\/[^\s<>"'`]+/gi;

  /**
   * [url] without punctuation that ends the sentence around it rather than the address: trailing stops, commas and
   * quotes, and a closing bracket that has no opening one inside the address, as in "(see https://example.com)".
   */
  function trimUrl(url) {
    let end = url.replace(/[.,;:!?'"]+$/, '');
    for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}']]) {
      while (end.endsWith(close) && end.split(close).length > end.split(open).length) end = end.slice(0, -1).replace(/[.,;:!?'"]+$/, '');
    }
    return end;
  }

  /**
   * Plain [text] with each web address in it as a trusted link, which the click handler opens in the system browser;
   * the rest stays text, never markup.
   */
  function withLinks(text) {
    const out = document.createDocumentFragment();
    const str = String(text || '');
    let at = 0;
    for (const match of str.matchAll(URL_RE)) {
      const url = trimUrl(match[0]);
      if (!url.replace(/^https?:\/\//i, '')) continue;
      out.append(str.slice(at, match.index), h('a', { class: 'url', href: url, title: url }, url));
      at = match.index + url.length;
    }
    out.append(str.slice(at));
    return out;
  }
  const shortPath = (p) => p.split('/').slice(-2).join('/');
  /** A page-created image whose src is a data: URL the IDE built from validated bytes; anything else becomes alt text. */
  const imageOrAlt = (src, alt) => (typeof src === 'string' && DATA_IMG.test(src) ? h('img', { src, alt }) : h('span', { class: 'img-alt' }, alt));

  /**
   * [imageOrAlt], with the image made a button that opens it in the IDE's editor (see [openImage]). [source] says which
   * image it is: { item, index } for one sent with user message [item], { tool, index } for one in tool call [tool]'s
   * result, or { path } for a local image a reply shows.
   */
  function openableImage(src, alt, source) {
    const el = imageOrAlt(src, alt);
    if (el.tagName !== 'IMG') return el;
    el.classList.add('openable');
    el.setAttribute('role', 'button');
    el.tabIndex = 0;
    el.setAttribute('aria-label', alt && alt !== '[image]' ? 'Open image in editor: ' + alt : 'Open image in editor');
    for (const [key, value] of Object.entries(source)) el.dataset[key] = String(value);
    return el;
  }

  window.chat = {
    receive(msg) {
      switch (msg.type) {
        case 'reset':
          items.clear(); childBoxes.clear(); waitingFor.clear(); waitingOn.clear(); parentOf.clear(); reports.clear();
          sentText = takenBack = null;
          stubs.clear(); dismissed.clear(); stopping.clear();
          turnOf.clear(); revealed.clear(); turn = 0; agentsCollapsed = false;
          logContent.textContent = '';
          tasksEl.querySelectorAll(':scope > .agent').forEach((el) => el.remove());
          setProcesses([]);
          follow();
          replaying = true;
          try { msg.items.forEach(upsert); } finally { replaying = false; }
          showTasks();
          break;
        case 'items': msg.items.forEach(upsert); break;
        case 'item': upsert(msg.item); break;
        case 'state': setState(msg); break;
        case 'modes': setModes(msg.modes); break;
        case 'agent': setAgent(msg); break;
        case 'activity': setActivity(msg); break;
        case 'resolved': answer(msg.reqId, msg.paths); break;
        case 'images': answer(msg.reqId, msg.images || {}); break;
        case 'files': answer(msg.reqId, Array.isArray(msg.items) ? msg.items : []); break;
        case 'insert': insertText(msg.text); break;
        case 'unsentText': restoreUnsentText(msg.text); break;
        case 'theme': applyTheme(msg); break;
        case 'history': history = Array.isArray(msg.entries) ? msg.entries : []; break;
        case 'commands': setCommands(Array.isArray(msg.items) ? msg.items : []); break;
        case 'checkout': setCheckout(msg.text); break;
        case 'branchBar': setBranchBar(msg); break;
        case 'pullRequest': setPullRequest(msg); break;
        case 'usage': setUsage(msg); break;
        case 'model': setModel(msg); break;
        case 'attachment': setAttachment(msg); break;
        case 'dropTarget': showDropTarget(msg.active === true); break;
        case 'suggestion': setSuggestion(msg.text); break;
        case 'queue': setQueue(msg.items); break;
        case 'processes': setProcesses(msg.processes); break;
      }
    }
  };

  /**
   * Following: the log stays pinned to its latest line. It is a state, not re-measured on each update, because content
   * also grows outside updates (an image decoding, a markdown image resolving later) and that is not the user leaving.
   * Only the user scrolling up turns it off; scrolling back down to the bottom, or the arrow, turns it on again.
   */
  let following = true;
  let lastTop = 0;
  /** Pixels: this close to the bottom counts as at it. */
  const NEAR_BOTTOM = 40;
  const gap = () => log.scrollHeight - log.scrollTop - log.clientHeight;
  function pin() {
    log.scrollTop = log.scrollHeight;
    lastTop = log.scrollTop;
  }
  function showArrow() {
    const hide = following || gap() < NEAR_BOTTOM;
    if (toBottom.hidden !== hide) toBottom.hidden = hide;
  }
  function follow(smooth) {
    following = true;
    if (smooth && !matchMedia('(prefers-reduced-motion: reduce)').matches) log.scrollTo({ top: log.scrollHeight, behavior: 'smooth' }); else pin();
    showArrow();
  }

  /**
   * Moving up while not at the very bottom is the user leaving, so following stops; a shrink of the log that clamps
   * scrollTop also moves it up but leaves the gap at 0, so it does not count. Reaching the bottom again resumes it.
   */
  function trackFollowing() {
    const top = log.scrollTop, g = gap();
    if (top < lastTop - 1 && g > 1) following = false;
    else if (top >= lastTop && g < NEAR_BOTTOM) following = true;
    lastTop = top;
    showArrow();
  }
  log.addEventListener('scroll', trackFollowing, { passive: true });

  /**
   * The log's box shrinks when the activity strip, the image tray or a taller message box appears, and its content grows
   * with every update, image and expanded detail: while following, either keeps the latest line in view.
   */
  const resized = new ResizeObserver(() => {
    if (following) pin();
    showArrow();
  });
  resized.observe(log);
  resized.observe(logContent);

  toBottom.addEventListener('click', () => follow(true));

  /** The container of each subagent card's items, by card id: it survives the card's re-renders. */
  const childBoxes = new Map();
  /** Elements waiting for their card to be shown, by card id. */
  const waitingFor = new Map();
  /** The card id each waiting element waits for. */
  const waitingOn = new Map();
  /** The card id of each item that has one. */
  const parentOf = new Map();
  /** Each card's { text, node }: a report is rendered once, not on every progress update. */
  const reports = new Map();
  /** An Agent/Task call is shown as a card whose container holds its subagent's items. */
  const isAgentCall = (item) => item.kind === 'tool' && (item.name === 'Agent' || item.name === 'Task');

  function childBox(id) {
    let box = childBoxes.get(id);
    if (!box) { box = h('div', { class: 'agent-children' }); childBoxes.set(id, box); }
    return box;
  }

  /**
   * Puts [el] in its card's container ([logContent] without a parent, or [tasksEl] for a [pinned] card, ahead of the
   * background processes listed below the agents), or has it wait until the card is shown.
   */
  function place(el, parentId, pinned) {
    const waited = waitingOn.get(el);
    if (waited !== undefined && waited !== parentId) {
      waitingOn.delete(el);
      const list = (waitingFor.get(waited) || []).filter((x) => x !== el);
      if (list.length) waitingFor.set(waited, list); else waitingFor.delete(waited);
    }
    const box = parentId ? childBoxes.get(parentId) : pinned ? tasksEl : logContent;
    if (!box) {
      el.remove();
      if (waitingOn.get(el) !== parentId) {
        waitingOn.set(el, parentId);
        waitingFor.set(parentId, (waitingFor.get(parentId) || []).concat(el));
      }
      return;
    }
    if (el.parentNode !== box) box.insertBefore(el, box === tasksEl ? tasksEl.querySelector(':scope > .process') : null);
  }

  /** The <details> of [el] itself: a card's own are only its Prompt; those in its container belong to the items there. */
  const ownDetails = (el) => el.classList.contains('agent') ? el.querySelectorAll(':scope > .agent-body > details') : el.querySelectorAll('details');

  function upsert(item) {
    let el = items.get(item.id);
    if (!el) { el = document.createElement('div'); items.set(item.id, el); }
    const parentId = typeof item.parentId === 'string' && item.parentId ? item.parentId : null;
    if (parentId) parentOf.set(item.id, parentId); else parentOf.delete(item.id);
    const pinned = !parentId && isAgentCall(item);
    if (item.kind === 'user' && !el.isConnected) startTurn();
    if (pinned) placeStub(item);
    const startsAgent = pinned && !turnOf.has(item.id);
    if (startsAgent) turnOf.set(item.id, turn);
    place(el, parentId, pinned);
    const wasWaiting = el.classList.contains('status-awaiting_permission');
    const open = [...ownDetails(el)].map((d) => d.open);
    el.textContent = '';
    switch (item.kind) {
      case 'user': renderUser(el, item); break;
      case 'assistant': renderAssistant(el, item); break;
      case 'tool': if (isAgentCall(item)) renderAgent(el, item, pinned); else renderTool(el, item); break;
      case 'result': renderResult(el, item); break;
      case 'notice': el.className = 'msg notice' + (item.isError ? ' error' : ''); el.append(withLinks(item.text)); break;
    }
    ownDetails(el).forEach((d, i) => { if (open[i] !== undefined) d.open = open[i]; });
    if (startsAgent) unfoldAgents();
    else if (pinned) showTasks();
    const startedWaiting = item.kind === 'tool' && item.status === 'awaiting_permission' && !wasWaiting;
    if (startedWaiting) expandAgentsFrom(parentId);
  }

  /**
   * Opens the card [id] and every card around it, and the agent box if they sit there, so a subagent that starts waiting
   * for an answer comes into view.
   */
  function expandAgentsFrom(id) {
    let top = null;
    for (let p = id; p; p = parentOf.get(p)) { expandAgent(p); top = p; }
    if (top && turnOf.has(top)) unfoldAgents(top);
  }

  /** Registers [onReply] for the IDE's answer to a request and returns the id to send the request with. */
  function expectReply(onReply) {
    const reqId = ++reqSeq;
    pendingResolve.set(reqId, onReply);
    return reqId;
  }

  /** Hands the IDE's answer to request [reqId] to the callback waiting for it, once. */
  function answer(reqId, value) {
    const onReply = pendingResolve.get(reqId);
    pendingResolve.delete(reqId);
    if (onReply) onReply(value);
  }

  /**
   * A user message: its text is plain, never markup, with its web addresses as links; an "Explain This Change" request
   * shows as a collapsed row instead (see [renderExplainRequest]).
   */
  function renderUser(el, item) {
    el.className = 'msg user';
    el.dataset.text = typeof item.text === 'string' ? item.text.trim() : '';
    if (item.revertable) {
      el.append(h('button', { class: 'link revert', 'data-action': 'revertTo', 'data-id': item.id,
        title: 'Go back to before this message: files, conversation or both (asks first)' }, 'Revert to here'));
    }
    const images = Array.isArray(item.images) ? item.images : [];
    if (images.length) el.append(h('div', { class: 'thumbs' }, ...images.map((src, index) => openableImage(src, '[image]', { item: item.id, index }))));
    const request = explainRequest(item.text);
    if (request) renderExplainRequest(el, request, item.id);
    else if (item.text) el.append(withLinks(item.text));
  }

  /** [text] matched literally inside a regular expression. */
  const escapeRe = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  /** A regular expression matching all of a text made of [pieces]: plain strings literally, regular expressions as they are. */
  const wholeText = (...pieces) =>
    new RegExp('^' + pieces.map((p) => (typeof p === 'string' ? escapeRe(p) : p.source)).join('') + '$');

  const MENTION = /@(?<mention>\S+)/;
  const QUOTED_DIFF = /:\n```diff\n(?<diff>[\s\S]*)\n```\n/;
  const USE_HISTORY = 'Use the git history of these lines (commit messages, `git log -L`, blame) and the surrounding code.';
  const WHY_NOT_WHAT = 'Focus on why it changed, not a line-by-line restatement of what changed.';

  /**
   * The messages "Explain This Change" sends, word for word as TetherMessages.explainChange writes them (its
   * EXPLAIN_WORDING), with the title each shows as.
   */
  const EXPLAIN_FORMS = [
    { title: 'Explain this change',
      re: wholeText('Explain why this change at ', MENTION, ' was made', /(?: \((?<revisions>.+)\))?/, QUOTED_DIFF, USE_HISTORY, ' ', WHY_NOT_WHAT) },
    { title: 'Explain this uncommitted change',
      re: wholeText('Explain this uncommitted change at ', MENTION, QUOTED_DIFF, USE_HISTORY, ' ', WHY_NOT_WHAT) },
    { title: 'Explain this line',
      re: wholeText('Explain why ', MENTION, ' is the way it is. Find the commit that last changed it ', /\(`git blame [^`\n]*`\)/,
        ", read that commit's message and diff, and explain why the change was made. ", WHY_NOT_WHAT) },
  ];

  /**
   * What the IDE's "Explain This Change" action asked, read back from the message it sent: { title, mention, revisions,
   * diff }, or null for any other text, which then shows as written. Only an exact match counts, so a message the user
   * typed or edited is never shown as something it isn't (nor is one whose path has a space, which the mention can't
   * hold). The request is read from the text because that is all a resumed chat's history keeps of it.
   */
  function explainRequest(text) {
    const str = String(text || '');
    for (const form of EXPLAIN_FORMS) {
      const m = form.re.exec(str);
      if (m) return { title: form.title, revisions: null, diff: null, ...m.groups };
    }
    return null;
  }

  /**
   * [request] (see [explainRequest]) as a row like a tool call's: what was asked and the file and lines as a link,
   * with the quoted hunk behind a disclosure toggle, collapsed until the user opens it. The instructions to Claude
   * stay out of it; they are the same every time. [id] is the message's, so the row stays open across re-renders.
   */
  function renderExplainRequest(el, request, id) {
    el.classList.add('request');
    const desc = h('span', { class: 'tool-desc' }, fileMention(request.mention));
    if (request.revisions) desc.append(h('span', { class: 'request-revisions', title: request.revisions }, request.revisions));
    const name = h('span', { class: 'tool-name' }, request.title);
    if (!request.diff) { el.append(h('div', { class: 'tool-row' }, name, desc)); return; }
    const row = h('div', { class: 'tool-row expandable', 'data-id': id }, name, desc, h('span', { class: 'tool-side' }, rowToggle('the change')));
    el.append(row, h('div', { class: 'tool-detail', hidden: true }, diffBlock(request.diff)));
    setRowExpanded(row, expandedTools.has(id));
  }

  /**
   * An @-mention ("path#L3-4") as its file name and lines, with the full path as its tooltip; it becomes a link to
   * the lines once the IDE resolves the path.
   */
  function fileMention(mention) {
    const at = mention.lastIndexOf('#L');
    const path = at < 0 ? mention : mention.slice(0, at);
    const label = path.split('/').pop() + linesLabel(at < 0 ? '' : mention.slice(at + 2));
    const el = h('span', { class: 'mention', title: mention }, label);
    const reqId = expectReply((paths) => {
      const r = paths && paths[mention];
      if (!r || !el.isConnected) return;
      el.replaceWith(resolvedFileLink(r, { class: 'file mention', title: mention }, label));
    });
    send({ type: 'resolvePaths', reqId, candidates: [mention] });
    return el;
  }

  /** A mention's line part ("3" or "3-4") as " line 3" or " lines 3–4"; empty for none. */
  function linesLabel(lines) {
    if (!lines) return '';
    if (!lines.includes('-')) return ' line ' + lines;
    return ' lines ' + lines.replace('-', '\u2013');
  }

  const DIFF_LINE_CLASS = { '+': 'add', '-': 'del' };

  /** Unified-diff [text] with its added and removed lines marked, scrolling sideways rather than wrapping code. */
  function diffBlock(text) {
    return h('pre', { class: 'diff' }, ...text.split('\n').map((line) => h('span', { class: DIFF_LINE_CLASS[line[0]] || null }, line || ' ')));
  }

  /**
   * Messages sent while the agent works wait here, under the log, until their turn starts; then they enter the log.
   * Only a text message can be edited: its images could not go back to the tray.
   */
  function setQueue(list) {
    const queued = Array.isArray(list) ? list : [];
    queueEl.textContent = '';
    for (const m of queued) {
      if (!m || typeof m.id !== 'string') continue;
      const editable = !(Array.isArray(m.images) && m.images.length);
      const el = h('div');
      renderUser(el, m);
      el.classList.add('queued');
      el.prepend(h('div', { class: 'queued-bar' },
          h('span', { class: 'queued-label' }, 'Queued'),
          h('button', { class: 'link', 'data-action': 'queueSendNow', 'data-id': m.id, title: 'Stop ' + agentName + ' and send this' }, 'Send now'),
          editable ? h('button', { class: 'link', 'data-action': 'queueEdit', 'data-id': m.id, title: 'Move back to the message box' }, 'Edit') : null,
          h('button', { class: 'link', 'data-action': 'queueRemove', 'data-id': m.id, title: 'Don’t send this' }, 'Remove')));
      queueEl.append(el);
    }
    queueEl.hidden = !queueEl.childElementCount;
  }

  /** Model markdown into [el]: parsed, sanitized, with images as markers until the IDE resolves them. */
  function renderMarkdown(el, text, streaming) {
    imgSlots = [];
    let html, slots;
    try { html = marked.parse(text || ''); } finally { slots = imgSlots; imgSlots = null; }
    el.innerHTML = DOMPurify.sanitize(html, SANITIZE);
    const images = placeImages(el, slots);
    if (!streaming) { linkify(el); resolveImages(images); }
  }

  /** An assistant message as markdown, or a local command's output (such as /usage) as the plain text it is. */
  function renderAssistant(el, item) {
    el.className = 'msg assistant' + (item.commandOutput ? ' command-output' : '');
    if (item.commandOutput) el.textContent = item.text;
    else renderMarkdown(el, item.text, item.streaming);
    el.toggleAttribute('aria-busy', !!item.streaming);
  }

  /** Replaces each image marker with its alt text in a trusted span; returns { span, href, alt } for each. */
  function placeImages(root, slots) {
    const out = [];
    if (!slots.length) return out;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      const text = node.nodeValue;
      IMG_MARK.lastIndex = 0;
      if (!IMG_MARK.test(text)) continue;
      IMG_MARK.lastIndex = 0;
      const frag = document.createDocumentFragment();
      let last = 0, m;
      while ((m = IMG_MARK.exec(text))) {
        frag.append(text.slice(last, m.index));
        const slot = slots[Number(m[1])];
        if (slot) {
          const span = h('span', { class: 'img-alt' }, slot.alt);
          out.push({ span, href: slot.href, alt: slot.alt });
          frag.append(span);
        }
        last = m.index + m[0].length;
      }
      frag.append(text.slice(last));
      node.replaceWith(frag);
    }
    return out;
  }

  const decodedOrAsWritten = (s) => { try { return decodeURI(s); } catch (e) { return s; } };
  /** The path the IDE is asked for, from a markdown image's href. */
  const imageKey = (href) => decodedOrAsWritten(href.trim().replace(/^<(.*)>$/, '$1')).replace(/^\.\//, '');

  /** Only local paths are asked for; URLs (http:, https:, data:, file:, //host) stay alt text and are never fetched. */
  function resolveImages(images) {
    const wanted = images.filter((x) => x.href && !/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(x.href.trim()));
    if (!wanted.length) return;
    const reqId = expectReply((found) => {
      for (const x of wanted) {
        const src = Object.prototype.hasOwnProperty.call(found, imageKey(x.href)) ? found[imageKey(x.href)] : null;
        if (!x.span.isConnected) continue;
        const img = x.span.closest('a') ? imageOrAlt(src, x.alt) : openableImage(src, x.alt, { path: imageKey(x.href) });
        if (img.tagName !== 'IMG') continue;
        img.classList.add('md-img');
        x.span.replaceWith(img);
      }
    });
    send({ type: 'resolveImages', reqId, paths: [...new Set(wanted.map((x) => imageKey(x.href)))] });
  }

  /** A link that opens the file the IDE resolved a path to ([r], from a resolvePaths answer) at its line. */
  const resolvedFileLink = (r, attrs, ...children) =>
    h('a', { class: 'file', href: '#', 'data-target': r.target, 'data-line': r.line, ...attrs }, ...children);

  /**
   * Turns inline code that looks like a path, and markdown links whose target is not a web URL (those may point at
   * project files), into file links for the paths the IDE resolves.
   */
  function linkify(root) {
    const codes = [...root.querySelectorAll('code')].filter((c) => !c.closest('pre') && PATH_RE.test(c.textContent.trim()));
    const anchors = [...root.querySelectorAll('a[href]')]
      .filter((a) => !trusted.has(a) && !/^(?:https?:|mailto:)/i.test((a.getAttribute('href') || '').trim()))
      .map((a) => ({ a, key: (a.getAttribute('href') || '').trim().replace(/^\.\//, '') }))
      .filter((x) => x.key);
    if (!codes.length && !anchors.length) return;
    const reqId = expectReply((paths) => {
      for (const c of codes) {
        const r = paths[c.textContent.trim()];
        if (!r || !c.isConnected) continue;
        const a = resolvedFileLink(r);
        c.replaceWith(a);
        a.appendChild(c);
      }
      for (const { a: old, key } of anchors) {
        const r = paths[key];
        if (!r || !old.isConnected) continue;
        const a = resolvedFileLink(r);
        while (old.firstChild) a.appendChild(old.firstChild);
        old.replaceWith(a);
      }
    });
    const candidates = [...codes.map((c) => c.textContent.trim()), ...anchors.map((x) => x.key)];
    send({ type: 'resolvePaths', reqId, candidates: [...new Set(candidates)] });
  }

  const FILE_TOOLS = new Set(['Read', 'Edit', 'MultiEdit', 'Write', 'NotebookEdit']);
  const firstLine = (s) => String(s || '').split('\n').find((l) => l.trim() !== '') || '';

  /** One-line description of a tool call as plain text (file tools are shown as a file link instead; see renderToolRow). */
  function toolDescription(t) {
    const i = t.input || {};
    const str = (v) => (typeof v === 'string' ? v : '');
    switch (t.name) {
      case 'Bash': return firstLine(str(i.description)).trim() || firstLine(str(i.command)).trim();
      case 'Grep': return str(i.pattern) ? str(i.pattern) + (str(i.path) ? ' in ' + str(i.path) : '') : '';
      case 'Glob': return str(i.pattern);
      case 'WebFetch': {
        try { const u = new URL(str(i.url)); return u.host + (u.pathname === '/' ? '' : u.pathname); } catch (e) { return str(i.url); }
      }
      case 'WebSearch': return str(i.query);
      case 'Task': case 'Agent': return str(i.description);
      case 'TodoWrite': return 'Update todos';
      default:
        for (const v of Object.values(i)) if (typeof v === 'string' && v.trim() !== '') return firstLine(v).trim();
        return '';
    }
  }

  /** [label, nodes] for the input of a tool call, or null when there is none. */
  function inputBody(t) {
    const i = t.input || {};
    if (t.name === 'Edit') return ['Input', [h('pre', { class: 'del' }, clip(i.old_string)), h('pre', { class: 'add' }, clip(i.new_string))]];
    if (t.name === 'MultiEdit') return ['Input', (i.edits || []).flatMap((e) => [h('pre', { class: 'del' }, clip(e.old_string)), h('pre', { class: 'add' }, clip(e.new_string))])];
    if (t.name === 'Write') return ['Input', [h('pre', { class: 'add' }, clip(i.content))]];
    if (t.name === 'Bash') return ['Command', [h('pre', {}, clip(i.command))]];
    if (Object.keys(i).length) return ['Input', [h('pre', {}, clip(JSON.stringify(i, null, 2)))]];
    return null;
  }

  function inputDetails(t) {
    const body = inputBody(t);
    if (!body) return null;
    return h('details', { open: t.status === 'awaiting_permission' }, h('summary', {}, body[0]), ...body[1]);
  }

  /** Tool rows and turn file lists the user has expanded, by item id, so a re-render of the same item keeps them open. */
  const expandedTools = new Set();
  const STATUS_ICON = { done: '\u2713', error: '\u2717', denied: '\u2298' };
  /** The icon cell of a tool or agent row (a spinner while running), named for screen readers by [TOOL_STATUS]. */
  const statusIcon = (status) => {
    const label = TOOL_STATUS[status] || null;
    const icon = status === 'running' ? h('span', { class: 'spin' }) : (STATUS_ICON[status] || '');
    return h('span', { class: 'tool-icon', role: label ? 'img' : null, 'aria-label': label }, icon);
  };

  /**
   * The disclosure button of an expandable row. The row itself stays a plain element (clicking anywhere on it still
   * toggles) because it can hold links and buttons, which may not sit inside a button.
   */
  const rowToggle = (name) => h('span', { class: 'tool-toggle', role: 'button', tabindex: '0', 'aria-label': 'Details of ' + name });

  /** Shows or hides the details right after [row]: a tool row's .tool-detail, or an agent card's .agent-body. */
  function setRowExpanded(row, open) {
    const toggle = row.querySelector('.tool-toggle');
    if (toggle) { toggle.setAttribute('aria-expanded', open ? 'true' : 'false'); toggle.textContent = open ? '\u25BE' : '\u25B8'; }
    const detail = row.nextElementSibling;
    if (detail && (detail.classList.contains('tool-detail') || detail.classList.contains('agent-body'))) detail.hidden = !open;
  }

  function toggleRow(row) {
    const id = row.dataset.id;
    const toggle = row.querySelector('.tool-toggle');
    const open = !toggle || toggle.getAttribute('aria-expanded') !== 'true';
    if (open) expandedTools.add(id); else expandedTools.delete(id);
    setRowExpanded(row, open);
  }

  function renderToolRow(el, t) {
    const body = inputBody(t);
    const hasDetails = !!body || !!t.resultPreview;
    const path = FILE_TOOLS.has(t.name) ? (t.filePath || (t.input && (t.input.file_path || t.input.notebook_path))) : null;
    const desc = h('span', { class: 'tool-desc' });
    if (typeof path === 'string' && path) desc.append(h('a', { class: 'file', href: '#', 'data-target': path, title: path }, shortPath(path)));
    else { const d = toolDescription(t); desc.textContent = d; if (d) desc.title = d; }
    const side = h('span', { class: 'tool-side' });
    if (t.status === 'error') side.append(h('span', { class: 'tool-failed' }, ' \u00b7 failed'));
    if (t.hasDiff) side.append(h('button', { class: 'link', 'data-action': 'diff', 'data-id': t.id }, 'View diff'));
    if (hasDetails) side.append(rowToggle(t.name));
    const row = h('div', { class: 'tool-row' + (hasDetails ? ' expandable' : ''), 'data-id': t.id },
      statusIcon(t.status), h('span', { class: 'tool-name' }, t.name), desc, side);
    el.append(row);
    if (hasDetails) {
      const detail = h('div', { class: 'tool-detail', hidden: true });
      if (body) detail.append(h('div', { class: 'tool-label' }, body[0]), ...body[1]);
      if (t.resultPreview) detail.append(h('div', { class: 'tool-label' }, 'Result'), h('pre', {}, withLinks(clip(t.resultPreview))));
      el.append(detail);
      setRowExpanded(row, expandedTools.has(t.id));
    }
    const images = Array.isArray(t.images) ? t.images : [];
    if (images.length) el.append(h('div', { class: 'tool-images' }, ...images.map((src, index) => openableImage(src, '[image]', { tool: t.id, index }))));
  }

  /** Page-created permission actions: agent-advertised choices or Claude's existing allow and deny controls. */
  function permissionCard(t) {
    const id = t.permissionRequestId;
    if (Array.isArray(t.choices) && t.choices.length) {
      return h('div', { class: 'perm' }, h('span', {}, t.name),
        ...t.choices.map((choice) => h('button', { 'data-action': 'choose', 'data-req': id, 'data-option': choice.id }, choice.label)));
    }
    return h('div', { class: 'perm' },
      h('span', {}, 'Allow ' + t.name + '?'),
      h('button', { 'data-action': 'allow', 'data-req': id }, 'Allow'),
      h('button', { 'data-action': 'allowSession', 'data-req': id }, 'Allow for session'),
      h('input', { class: 'deny-reason', placeholder: 'Reason (optional)' }),
      h('button', { 'data-action': 'deny', 'data-req': id }, 'Deny'));
  }

  /** A map keyed by model text: a question like "__proto__" stays an ordinary key. */
  const plainMap = () => Object.create(null);

  /**
   * Claude's question (AskUserQuestion). Every string here is model text and goes in through h() children, which are
   * text nodes; the controls are page-created, so only they can send an answer.
   */
  function questionCard(t) {
    const id = t.permissionRequestId;
    const qs = t.question.questions;
    const picks = qs.map(() => ({ labels: new Set(), other: '' }));
    const submitBtn = h('button', { class: 'q-submit', disabled: true }, 'Submit');
    const answered = (i) => picks[i].labels.size > 0 || picks[i].other.trim() !== '';
    const refresh = () => { if (picks.every((_, i) => answered(i))) submitBtn.removeAttribute('disabled'); else submitBtn.setAttribute('disabled', ''); };
    const blocks = qs.map((q, i) => {
      const multi = q.multiSelect === true;
      const preview = h('pre', { class: 'q-preview', hidden: true });
      const showPreview = (o) => { preview.textContent = o.preview ? clip(o.preview) : ''; preview.hidden = !o.preview; };
      const other = h('input', { class: 'q-other', placeholder: 'Other…', maxlength: 2000 });
      const buttons = [];
      const paint = () => buttons.forEach(({ b, label }) => b.setAttribute('aria-checked', picks[i].labels.has(label) ? 'true' : 'false'));
      for (const o of q.options) {
        const b = h('button', { type: 'button', class: 'q-opt', role: multi ? 'checkbox' : 'radio', 'aria-checked': 'false' },
          h('span', { class: 'q-label' }, o.label),
          o.description ? h('span', { class: 'q-desc' }, o.description) : null);
        buttons.push({ b, label: o.label });
        b.addEventListener('focus', () => showPreview(o));
        b.addEventListener('mouseenter', () => showPreview(o));
        b.addEventListener('click', () => {
          const set = picks[i].labels;
          if (multi) { if (set.has(o.label)) set.delete(o.label); else set.add(o.label); }
          else { set.clear(); set.add(o.label); picks[i].other = ''; other.value = ''; }
          showPreview(o);
          paint();
          refresh();
        });
      }
      other.addEventListener('input', () => {
        picks[i].other = other.value;
        if (!multi && other.value.trim() !== '') { picks[i].labels.clear(); paint(); }
        refresh();
      });
      return h('div', { class: 'q-block' },
        q.header ? h('div', { class: 'q-header' }, q.header) : null,
        h('div', { class: 'q-text' }, q.question),
        h('div', { class: 'q-options', role: multi ? 'group' : 'radiogroup' }, ...buttons.map((x) => x.b)),
        other, preview);
    });
    submitBtn.addEventListener('click', () => {
      if (submitBtn.hasAttribute('disabled') || !picks.every((_, i) => answered(i))) return;
      const answers = plainMap();
      qs.forEach((q, i) => {
        const other = picks[i].other.trim();
        answers[q.question] = { labels: q.options.map((o) => o.label).filter((l) => picks[i].labels.has(l)), other: other || null };
      });
      submitBtn.setAttribute('disabled', '');
      send({ type: 'answer', requestId: id, answers });
    });
    return h('div', { class: 'question' }, ...blocks,
      h('div', { class: 'perm' }, submitBtn, h('button', { 'data-action': 'skipQuestion', 'data-req': id }, 'Skip')));
  }

  function answerSummary(q) {
    const answers = q.answers || {};
    return h('div', { class: 'q-answers' }, ...q.questions.map((x) =>
      h('div', {}, (x.header || x.question) + ': ' + (answers[x.question] !== undefined ? answers[x.question] : ''))));
  }

  function renderTool(el, t) {
    el.className = 'msg tool status-' + t.status;
    if (!(t.question && t.question.questions) && t.status !== 'awaiting_permission') { el.classList.add('compact'); renderToolRow(el, t); return; }
    el.classList.remove('compact');
    const head = h('div', { class: 'tool-head' }, h('span', { class: 'tool-name' }, t.name));
    if (t.filePath) head.append(h('a', { class: 'file', href: '#', 'data-target': t.filePath, title: t.filePath }, shortPath(t.filePath)));
    else { const s = toolDescription(t); if (s) head.append(h('span', { class: 'tool-summary' }, s)); }
    head.append(h('span', { class: 'badge' }, TOOL_STATUS[t.status] || t.status));
    if (t.hasDiff) head.append(h('button', { class: 'link', 'data-action': 'diff', 'data-id': t.id }, 'View diff'));
    el.append(head);
    const images = Array.isArray(t.images) ? t.images : [];
    if (images.length) el.append(h('div', { class: 'tool-images' }, ...images.map((src, index) => openableImage(src, '[image]', { tool: t.id, index }))));
    if (t.question && t.question.questions) {
      if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(questionCard(t));
      else if (t.question.answers) el.append(answerSummary(t.question));
      else el.append(h('div', { class: 'q-answers' }, 'Skipped'));
      return;
    }
    const details = inputDetails(t);
    if (details) el.append(details);
    if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(permissionCard(t));
    if (t.resultPreview) el.append(h('details', {}, h('summary', {}, 'Result'), h('pre', {}, withLinks(clip(t.resultPreview)))));
  }

  /** The states an agent card can be in, in the order the agent box counts them. */
  const AGENT_STATES = ['running', 'done', 'failed'];

  /** running | done | failed: from the task events, else (an older CLI) from the call's own status. */
  function agentState(t) {
    const s = t.agent && t.agent.state;
    if (AGENT_STATES.includes(s)) return s;
    if (t.status === 'done') return 'done';
    return t.status === 'running' || t.status === 'awaiting_permission' ? 'running' : 'failed';
  }

  function agentSide(a, state) {
    if (!a) return '';
    const parts = [];
    if (state === 'running' && typeof a.lastTool === 'string' && a.lastTool) parts.push(a.lastTool.slice(0, 40));
    if (Number.isInteger(a.toolUses)) parts.push(a.toolUses + (a.toolUses === 1 ? ' tool' : ' tools'));
    if (Number.isFinite(a.durationMs)) parts.push(elapsed(a.durationMs));
    return parts.join(' \u00b7 ');
  }

  function reportNode(id, text) {
    const cached = reports.get(id);
    if (cached && cached.text === text) return cached.node;
    const node = h('div');
    renderMarkdown(node, text, false);
    reports.set(id, { text, node });
    return node;
  }

  /** The subagent's type (e.g. "general-purpose"), or '' when the CLI hasn't said. */
  const agentType = (a) => (a && typeof a.subagentType === 'string' ? a.subagentType.slice(0, 40) : '');
  /** The agent type a card is labelled with in the task rows, else the call's own name. */
  const agentLabel = (t) => agentType(t.agent) || t.name;
  /** The status icon of an agent in [state]: a failed one shows as an error. */
  const agentIcon = (state) => statusIcon(state === 'failed' ? 'error' : state);

  /** The Stop button of a running task row; [stopping] keeps it disabled across re-renders once pressed. */
  function stopButton(taskId, what) {
    const busy = stopping.has(taskId);
    return h('button', { class: 'link task-stop', 'data-action': 'stopTask', 'data-task': taskId, disabled: busy || null, title: 'Stop ' + what }, busy ? 'Stopping…' : 'Stop');
  }

  /**
   * A subagent card: a head row like a tool row, and a body with the prompt, the container of the subagent's items and
   * its report. An older CLI sends no report; then the call's result is the best there is. A [pinned] card sits in the
   * task rows above the message box rather than in the log: it is labelled with its agent type, a running one can be
   * stopped and a finished one dismissed.
   */
  function renderAgent(el, t, pinned) {
    const a = t.agent && typeof t.agent === 'object' ? t.agent : null;
    const state = agentState(t);
    el.className = 'msg agent agent-' + state;
    const desc = toolDescription(t);
    const name = pinned ? agentLabel(t) : t.name;
    if (state !== 'running' && a && typeof a.taskId === 'string') stopping.delete(a.taskId);
    const side = h('span', { class: 'tool-side' }, agentSide(a, state), ' ');
    if (pinned && state === 'running' && a && typeof a.taskId === 'string' && a.taskId) side.append(stopButton(a.taskId, 'this agent'), ' ');
    if (pinned && state !== 'running') side.append(h('button', { class: 'link task-dismiss', 'data-action': 'dismissTask', 'data-id': t.id, 'aria-label': 'Dismiss', title: 'Dismiss' }, '\u2715'), ' ');
    side.append(rowToggle(name));
    const head = h('div', { class: 'tool-row agent-head expandable', 'data-id': t.id },
      agentIcon(state),
      h('span', { class: 'tool-name' }, name),
      h('span', { class: 'tool-desc', title: desc || null }, desc),
      !pinned && agentType(a) ? h('span', { class: 'agent-tag' }, agentType(a)) : null,
      !pinned && a && a.background === true ? h('span', { class: 'agent-tag' }, 'background') : null,
      side);
    const body = h('div', { class: 'agent-body' });
    if (a && typeof a.prompt === 'string' && a.prompt) body.append(h('details', {}, h('summary', {}, 'Prompt'), h('pre', {}, clip(a.prompt))));
    const box = childBox(t.id);
    body.append(box);
    if (a && typeof a.report === 'string' && a.report) {
      body.append(h('div', { class: 'agent-report' }, h('div', { class: 'tool-label' }, 'Report'), reportNode(t.id, a.report)));
    } else if (!a && t.resultPreview) {
      body.append(h('details', {}, h('summary', {}, 'Result'), h('pre', {}, withLinks(clip(t.resultPreview)))));
    }
    el.append(head, body);
    setRowExpanded(head, expandedTools.has(t.id));
    if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(permissionCard(t));
    const waiting = waitingFor.get(t.id);
    if (waiting) {
      waitingFor.delete(t.id);
      waiting.forEach((c) => { waitingOn.delete(c); box.appendChild(c); });
    }
  }

  /**
   * The log's line for each pinned card, by card id: it marks where the agent started and brings the card back into view.
   * It hides while its card shows above the message box, so the agent isn't listed twice (see [showStubs]).
   */
  const stubs = new Map();
  /** Pinned cards the user dismissed, by id; a running card always shows. */
  const dismissed = new Set();
  /** Task ids the user asked to stop, so their Stop stays disabled until the task goes. */
  const stopping = new Set();
  /** The number of user messages so far. */
  let turn = 0;
  /** The [turn] each pinned card started in, by card id; its keys are the agent box's cards, every agent of the session. */
  const turnOf = new Map();
  /** Whether the agent box shows only its header; a new message folds it when no agent is running. */
  let agentsCollapsed = false;
  /** Finished cards of earlier messages that the user brought back, from the log or with "Show earlier", by id. */
  const revealed = new Set();
  /** The finished agents of earlier messages that the long list currently leaves out, by id. */
  let leftOut = new Set();
  /** A box with more agents than this leaves out the finished ones of earlier messages until asked. */
  const AGENTS_SHOWN = 5;

  /** The agent box header's summary of how many agents ran and how they stand. */
  const agentsSummary = h('span', { class: 'tool-desc' });
  /** The agent box's header: a disclosure triangle labelled with [agentsSummary]. */
  const agentsHead = h('button', { class: 'tool-row agents-head', 'data-action': 'toggleAgents', hidden: true },
    h('span', { class: 'tool-toggle', 'aria-hidden': 'true' }), h('span', { class: 'tool-name' }, 'Agents'), agentsSummary);
  /** The row that brings back the finished agents of earlier messages that a long list leaves out. */
  const earlierAgents = h('button', { class: 'link agents-earlier', 'data-action': 'showEarlierAgents', hidden: true });
  tasksEl.append(agentsHead, earlierAgents);

  /** A new message folds the agent box unless an agent is still running; one the message starts opens it again. */
  function startTurn() {
    turn++;
    revealed.clear();
    if (!tasksEl.querySelector(':scope > .agent-running')) agentsCollapsed = true;
    showTasks();
  }

  /** Puts or updates [t]'s line in the log, where the card would have been. */
  function placeStub(t) {
    let stub = stubs.get(t.id);
    if (!stub) { stub = h('div'); stubs.set(t.id, stub); }
    if (!stub.isConnected) logContent.appendChild(stub);
    const state = agentState(t);
    const desc = toolDescription(t);
    const shown = [state, agentLabel(t), desc].join('\n');
    if (stub.dataset.shown === shown) return;
    stub.dataset.shown = shown;
    stub.className = 'msg tool compact agent-stub status-' + (state === 'failed' ? 'error' : state);
    stub.textContent = '';
    stub.append(h('div', { class: 'tool-row', role: 'button', tabindex: '0', 'data-id': t.id, title: 'Show this agent above the message box' },
      agentIcon(state),
      h('span', { class: 'tool-name' }, agentLabel(t)),
      h('span', { class: 'tool-desc' }, desc),
      h('span', { class: 'tool-side', 'aria-hidden': 'true' }, '\u2193')));
  }

  /** Brings pinned card [id] back if dismissed, opens it and scrolls the task rows to it. */
  function revealAgent(id) {
    const el = items.get(id);
    if (!el || el.parentNode !== tasksEl) return;
    dismissed.delete(id);
    expandAgent(id);
    unfoldAgents(id);
    el.scrollIntoView({ block: 'nearest' });
    const toggle = el.querySelector(':scope > .agent-head .tool-toggle');
    if (toggle) toggle.focus();
  }

  /** Hides pinned card [id] if it has finished. */
  function dismissAgent(id) {
    const el = items.get(id);
    if (!el || el.classList.contains('agent-running')) return;
    dismissed.add(id);
    showTasks();
  }

  /** True while a reset replays the chat: the task rows are shown once at its end rather than after every item. */
  let replaying = false;

  /**
   * Decides which pinned cards show: none while the box is folded, nor those the user dismissed, and in a long list not
   * the finished ones of earlier messages. Then the task rows show while any row is visible (the agent box's header stays
   * once an agent has run), and each pinned card's log line only while the card does not.
   */
  function showTasks() {
    if (replaying) return;
    const cards = [...turnOf.keys()].map((id) => ({ id, el: items.get(id) })).filter((c) => c.el && c.el.parentNode === tasksEl);
    const counts = Object.fromEntries(AGENT_STATES.map((state) => [state, 0]));
    const kept = [];
    const earlier = new Set();
    for (const c of cards) {
      c.state = AGENT_STATES.find((state) => c.el.classList.contains('agent-' + state)) || 'failed';
      counts[c.state]++;
      if (c.state !== 'running' && dismissed.has(c.id)) continue;
      kept.push(c);
      if (c.state !== 'running' && turnOf.get(c.id) < turn && !revealed.has(c.id)) earlier.add(c.id);
    }
    leftOut = kept.length > AGENTS_SHOWN ? earlier : new Set();
    const shown = new Set(kept.filter((c) => !leftOut.has(c.id)).map((c) => c.id));
    for (const c of cards) c.el.hidden = agentsCollapsed || !shown.has(c.id);
    agentsHead.hidden = !cards.length;
    setRowExpanded(agentsHead, !agentsCollapsed);
    agentsHead.setAttribute('aria-expanded', agentsCollapsed ? 'false' : 'true');
    agentsHead.title = agentsCollapsed ? 'Show agents' : 'Hide agents';
    agentsSummary.textContent = AGENT_STATES.filter((state) => counts[state]).map((state) => counts[state] + ' ' + state).join(' \u00b7 ');
    earlierAgents.hidden = agentsCollapsed || !leftOut.size;
    earlierAgents.textContent = 'Show ' + leftOut.size + ' earlier ' + (leftOut.size === 1 ? 'agent' : 'agents');
    tasksEl.hidden = ![...tasksEl.children].some((c) => !c.hidden);
    showStubs();
  }

  /** Unfolds the agent box, bringing back pinned card [id] if it is a finished one of an earlier message. */
  function unfoldAgents(id) {
    if (id) revealed.add(id);
    agentsCollapsed = false;
    showTasks();
  }

  /** Brings back every finished agent of earlier messages that the long list left out. */
  function showEarlierAgents() {
    leftOut.forEach((id) => revealed.add(id));
    showTasks();
  }

  /** Folds or unfolds the agent box. */
  function toggleAgents() {
    agentsCollapsed = !agentsCollapsed;
    showTasks();
  }

  /** Shows the log line of each pinned card that is not in view above the message box, and hides the rest. */
  function showStubs() {
    for (const [id, stub] of stubs) {
      const card = items.get(id);
      stub.hidden = card.parentNode === tasksEl && !card.hidden;
    }
  }

  /** "45s", "12m 5s" or "2h 3m": how long a background process has run. */
  function elapsed(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60);
    return m < 60 ? m + 'm ' + (s % 60) + 's' : Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
  }

  let processTimer = null;
  function tickProcesses() {
    for (const el of tasksEl.querySelectorAll('.process .process-time')) el.textContent = elapsed(Date.now() - Number(el.dataset.started));
  }

  /**
   * The background processes (shell commands, monitors, workflows) as rows below the agent box, each with its running
   * time and a Stop. The IDE sends the whole list on every change. Its text is plain, never markup.
   */
  function setProcesses(list) {
    const processes = (Array.isArray(list) ? list : []).filter((p) => p && typeof p.id === 'string');
    tasksEl.querySelectorAll(':scope > .process').forEach((el) => el.remove());
    const ids = new Set(processes.map((p) => p.id));
    for (const id of [...stopping]) if (!ids.has(id) && !agentTaskRunning(id)) stopping.delete(id);
    const rows = processes.map((p) => {
      const kind = typeof p.kind === 'string' && p.kind ? p.kind : 'Task';
      const desc = typeof p.description === 'string' ? p.description : '';
      const started = Number.isFinite(p.startedAt) ? p.startedAt : Date.now();
      return h('div', { class: 'msg process' },
        h('div', { class: 'tool-row' },
          statusIcon('running'),
          h('span', { class: 'tool-name' }, kind),
          h('span', { class: 'tool-desc', title: desc || null }, desc),
          h('span', { class: 'tool-side' }, h('span', { class: 'process-time', 'data-started': String(started) }), ' ', stopButton(p.id, 'this process'))));
    });
    tasksEl.append(...rows);
    tickProcesses();
    if (rows.length && processTimer === null) processTimer = setInterval(tickProcesses, 1000);
    if (!rows.length && processTimer !== null) { clearInterval(processTimer); processTimer = null; }
    showTasks();
  }

  /** Whether a running pinned card is the subagent of task [taskId]. */
  const agentTaskRunning = (taskId) => !!tasksEl.querySelector(':scope > .agent-running [data-task="' + CSS.escape(taskId) + '"]');

  function expandAgent(id) {
    expandedTools.add(id);
    const el = items.get(id);
    const head = el && el.querySelector(':scope > .agent-head');
    if (head) setRowExpanded(head, true);
  }

  /** A longer list shows this many of its files until "Show all" is clicked. */
  const TURN_FILES_SHOWN = 5;
  const turnFilesLabel = (all, count) => all ? 'Show fewer' : 'Show all ' + count + ' files';

  function renderResult(el, r) {
    el.className = 'msg result' + (r.isError ? ' error' : '');
    const files = Array.isArray(r.files) ? r.files : [];
    const line = h('div', { class: 'result-line' });
    if (r.isError) line.append(withLinks(r.text || 'This turn failed.'));
    else {
      const parts = [];
      if (r.durationMs != null) parts.push((r.durationMs / 1000).toFixed(1) + 's');
      line.textContent = 'Done' + (parts.length ? ' · ' + parts.join(' · ') : '');
    }
    el.append(line);
    if (!files.length) return;
    const sum = (key) => files.reduce((n, f) => n + (Number(f[key]) || 0), 0);
    line.append(' · ', files.length + (files.length === 1 ? ' file' : ' files') + ' changed ',
      lineCounts(sum('added'), sum('removed')), ' · ',
      h('button', { class: 'link', 'data-action': 'turnChanges', 'data-id': r.id }, 'View all changes'));
    const all = files.length <= TURN_FILES_SHOWN || expandedTools.has(r.id);
    const list = h('div', { class: 'tool-detail turn-files' + (all ? '' : ' clipped') });
    files.forEach((f, i) => {
      const path = String(f.path || '');
      const slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
      const dir = slash < 0 ? '' : path.slice(0, slash).split(/[\\/]/).pop();
      list.append(h('div', { class: 'tool-row' + (i >= TURN_FILES_SHOWN ? ' extra' : ''), role: 'button', tabindex: '0', title: path, 'data-action': 'turnDiff', 'data-id': r.id, 'data-index': String(i) },
        h('span', { class: 'tool-desc' }, h('span', { class: 'turn-file-name' }, path.slice(slash + 1)), dir ? ' ' + dir + '/' : '', f.isNew ? h('span', { class: 'turn-file-new' }, ' new') : ''),
        h('span', { class: 'tool-side' }, lineCounts(Number(f.added) || 0, Number(f.removed) || 0))));
    });
    if (files.length > TURN_FILES_SHOWN) {
      list.append(h('button', { class: 'link turn-files-more', 'data-action': 'turnFiles', 'data-id': r.id }, turnFilesLabel(all, files.length)));
    }
    el.append(list);
  }

  function lineCounts(added, removed) {
    return h('span', { class: 'line-counts' },
      added ? h('span', { class: 'added' }, '+' + added) : '', added && removed ? ' ' : '',
      removed ? h('span', { class: 'removed' }, '\u2212' + removed) : '');
  }

  function toggleTurnFiles(button) {
    const list = button.closest('.turn-files');
    const all = !list.classList.toggle('clipped');
    if (all) expandedTools.add(button.dataset.id); else expandedTools.delete(button.dataset.id);
    button.textContent = turnFilesLabel(all, list.querySelectorAll('.tool-row').length);
  }

  function openTurnDiff(target) {
    send({ type: 'openTurnDiff', id: target.dataset.id, index: Number(target.dataset.index) || 0 });
  }

  function setState(msg) {
    statusEl.textContent = STATUS[msg.state] || '';
    if (msg.state === 'DEAD') setActivity({ phase: null });
    restartBtn.hidden = msg.state !== 'DEAD';
    input.disabled = sendBtn.disabled = attachBtn.disabled = msg.state === 'DEAD';
    if (typeof msg.mode === 'string') {
      mode = msg.mode;
      showSelection(modePicker, mode);
    }
    idle = msg.state === 'IDLE';
    if (!idle) setSuggestion('');
  }

  /**
   * The composer's model chip and the picker dialog show what the CLI says runs; the chip, labelled with the host's
   * `modelName` (the model's own name, never an alias's), stays hidden until it says,
   * and the effort row shows only for a model that takes an effort. Every string is CLI text and goes in as text.
   * Claude metadata arriving after an agent change is discarded rather than reopening stale controls.
   */
  function setModel(msg) {
    if (agent !== 'claude') { clearModel(); return; }
    const options = (Array.isArray(msg.options) ? msg.options : []).map((o) => ({
      value: String(o.value), name: String(o.name), description: o.description ? String(o.description) : '',
    }));
    const model = String(msg.model || '');
    const efforts = (Array.isArray(msg.efforts) ? msg.efforts : []).map(String);
    const effort = msg.effort ? String(msg.effort) : '';
    modelLabel.textContent = String(msg.modelName || model || 'Model');
    effortLabel.textContent = effort && efforts.length ? capitalize(effort) : '';
    effortLabel.hidden = !effortLabel.textContent;
    modelBtn.hidden = !options.length;
    modelOptions.replaceChildren(...options.map((o) => h('button',
      { class: 'choice', 'data-action': 'pickModel', 'data-value': o.value, 'aria-pressed': String(o.value === model) },
      h('span', { class: 'choice-name' }, o.name),
      o.description ? h('span', { class: 'choice-desc' }, o.description) : null)));
    effortOptions.replaceChildren(...efforts.map((level) => h('button',
      { class: 'segment', 'data-action': 'pickEffort', 'data-value': level, 'aria-pressed': String(level === effort) },
      capitalize(level))));
    effortSection.hidden = !efforts.length;
    if (!options.length && modelDialog.open) modelDialog.close();
    if (modelDialog.open && !modelDialog.contains(document.activeElement)) focusCurrentChoice();
  }

  /** Opens Claude's native picker only while its model capabilities are available. */
  function openModelDialog() {
    if (modelDialog.open || modelBtn.hidden || agent !== 'claude') return;
    modelDialog.showModal();
    focusCurrentChoice();
  }

  /** Focuses the picker's current model, so the keyboard starts (or, after the buttons are rebuilt, stays) there. */
  function focusCurrentChoice() {
    const current = modelDialog.querySelector('.choice[aria-pressed="true"]');
    if (current) current.focus();
  }

  /** A click on the backdrop lands on the dialog element itself, outside its content box: it closes the dialog. */
  function closeOnBackdropClick(e) {
    const dialog = e.currentTarget;
    if (e.target !== dialog) return;
    const r = dialog.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) dialog.close();
  }

  /**
   * Asks the IDE to open [img] in its editor, as a click on a file link does. Only page-created images (see
   * [openableImage]) are asked for, by what they are: a sent image, a tool's image or a reply's local image path.
   */
  function openImage(img) {
    const d = img.dataset;
    if (d.item) send({ type: 'openImage', item: d.item, index: Number(d.index) });
    else if (d.tool) send({ type: 'openImage', tool: d.tool, index: Number(d.index) });
    else if (d.path) send({ type: 'openImage', path: d.path });
  }

  /** Discards Claude-only capabilities on an identity change and returns a removed dialog's focus to the composer. */
  function clearModel() {
    const heldFocus = modelDialog.contains(document.activeElement);
    modelBtn.hidden = true;
    modelLabel.textContent = effortLabel.textContent = '';
    modelOptions.replaceChildren();
    effortOptions.replaceChildren();
    effortSection.hidden = effortLabel.hidden = true;
    if (modelDialog.open) modelDialog.close();
    if (heldFocus) input.focus({ preventScroll: true });
  }

  /** Rebuilds the upstream radio menu from host-provided ids and plain-text labels. */
  function setChoices(picker, choices, selected) {
    closeMenu(picker, picker.menu.contains(document.activeElement));
    const list = (Array.isArray(choices) ? choices : []).filter((choice) => choice && typeof choice.id === 'string' && typeof choice.label === 'string');
    picker.menu.replaceChildren(...list.map((choice) => h('div',
      { role: 'menuitemradio', 'data-value': choice.id, 'aria-checked': 'false', tabindex: '-1' }, choice.label)));
    showSelection(picker, selected);
  }

  /** Shows only the host's selected choice, never an unconfirmed request or an invented first option. */
  function showSelection(picker, value) {
    const entries = [...picker.menu.children];
    const picked = entries.find((item) => item.dataset.value === value);
    for (const item of entries) item.setAttribute('aria-checked', String(item === picked));
    picker.label.textContent = picked ? picked.textContent : picker.name;
    picker.button.setAttribute('aria-label', picker.name + (picked ? ': ' + picked.textContent : ''));
  }

  /** An empty capability list hides the permission control until the selected agent advertises its modes. */
  function setModes(modes) {
    const heldFocus = modePicker.menu.contains(document.activeElement) || document.activeElement === modePicker.button;
    setChoices(modePicker, modes, mode);
    modePicker.button.hidden = modePicker.button.parentElement.hidden = !modePicker.menu.childElementCount;
    if (modePicker.button.hidden && heldFocus) input.focus({ preventScroll: true });
  }

  /** A started chat keeps its identity; a draft's host-confirmed choice resets capabilities, not its text or images. */
  function setAgent(msg) {
    const changed = agent !== msg.selected;
    agent = typeof msg.selected === 'string' ? msg.selected : '';
    const agents = Array.isArray(msg.agents) ? msg.agents : [];
    const current = agents.find((choice) => choice && choice.id === agent);
    agentName = current && typeof current.label === 'string' ? current.label : 'the agent';
    agentPicker.button.disabled = !!msg.locked || agents.length < 2;
    setChoices(agentPicker, agents, agent);
    agentPicker.button.title = msg.locked ? 'This chat runs ' + agentName + '. Start a new chat for another agent.' : 'Agent for this chat';
    if (changed) { mode = ''; setModes([]); clearModel(); setCommands([]); }
    placeholder = 'Message ' + agentName;
    activityStop.title = 'Stop ' + agentName + ' (Esc)';
    setSuggestion(changed ? '' : suggestion);
  }

  /** Focuses a menu choice without moving the page, then scrolls the menu to keep that choice visible. */
  function focusMenuItem(item) {
    item.focus({ preventScroll: true });
    item.scrollIntoView({ block: 'nearest' });
  }

  /**
   * Opens a picker's menu above or below its trigger, whichever side has more room. The menu stays inside the webview,
   * scrolling when its list is long.
   */
  function openMenu(picker) {
    if (picker.button.disabled || picker.button.hidden || !picker.menu.childElementCount) return;
    for (const other of pickers) closeMenu(other, false);
    const menu = picker.menu;
    menu.hidden = false;
    const anchor = picker.button.getBoundingClientRect();
    const below = anchor.top < innerHeight - anchor.bottom;
    const room = below ? innerHeight - anchor.bottom - 12 : anchor.top - 12;
    menu.style.maxHeight = Math.min(280, Math.max(0, room)) + 'px';
    const bounds = menu.getBoundingClientRect();
    menu.style.left = Math.max(8, Math.min(anchor.left, innerWidth - bounds.width - 8)) + 'px';
    menu.style.top = (below ? anchor.bottom + 4 : Math.max(8, anchor.top - bounds.height - 4)) + 'px';
    picker.button.setAttribute('aria-expanded', 'true');
    focusMenuItem(menu.querySelector('[aria-checked="true"]') || menu.firstElementChild);
  }

  /** Keyboard dismissal and selection return focus; outside clicks and window blur preserve the new focus owner. */
  function closeMenu(picker, refocus) {
    if (picker.menu.hidden) return;
    picker.menu.hidden = true;
    picker.button.setAttribute('aria-expanded', 'false');
    if (refocus) (picker.button.disabled || picker.button.hidden ? input : picker.button).focus({ preventScroll: true });
  }

  /** Requests a host change using the advertised id; the reported selection remains authoritative. */
  function pickChoice(picker, item) {
    if (!trusted.has(item) || !picker.menu.contains(item) || picker.button.disabled) return;
    closeMenu(picker, true);
    const value = item.dataset.value;
    if (picker === agentPicker) { if (value !== agent) send({ type: 'setAgent', agent: value }); }
    else if (value !== mode) send({ type: 'setMode', mode: value });
  }

  /** Arrow keys wrap, Home and End jump, Enter or Space requests a choice, and Escape or Tab dismisses. */
  function onMenuKey(picker, e) {
    if (e.key === 'Tab') return closeMenu(picker, true);
    const entries = [...picker.menu.children];
    const at = entries.indexOf(document.activeElement);
    const moves = { ArrowDown: at + 1, ArrowUp: at - 1, Home: 0, End: entries.length - 1 };
    if (e.key in moves) focusMenuItem(entries[(moves[e.key] + entries.length) % entries.length]);
    else if ((e.key === 'Enter' || e.key === ' ') && at >= 0) pickChoice(picker, entries[at]);
    else if (e.key === 'Escape') closeMenu(picker, true);
    else return;
    e.preventDefault();
    e.stopPropagation();
  }

  /**
   * Grows the box to fit its text (capped by max-height in the CSS) so the log above gives way.
   * All reads come before any write, so each call lays the page out once.
   */
  function fitInput() {
    input.style.height = 'auto';
    const borders = input.offsetHeight - input.clientHeight;
    input.style.height = input.scrollHeight + borders + 'px';
  }
  let resizeFrame = 0;
  window.addEventListener('resize', () => { cancelAnimationFrame(resizeFrame); resizeFrame = requestAnimationFrame(fitInput); });

  /** How long the box must be still before its text is reported, in milliseconds. */
  const UNSENT_DELAY_MS = 400;
  let unsentTimer = 0;

  /** Every programmatic change of the box's text goes through here, so its height follows and the host hears of it. */
  function setInput(text, caret = text.length) {
    sentText = takenBack = rewinding = null;
    input.value = text;
    input.setSelectionRange(caret, caret);
    fitInput();
    saveUnsentTextSoon();
  }

  /** Reports the box's text to the host, which gives it back after a reload or restart. */
  function saveUnsentText() {
    clearTimeout(unsentTimer);
    send({ type: 'unsentText', text: input.value });
  }

  /** Reports the box's text once typing pauses, so a burst of keys sends one message. */
  function saveUnsentTextSoon() {
    clearTimeout(unsentTimer);
    unsentTimer = setTimeout(saveUnsentText, UNSENT_DELAY_MS);
  }

  /** Puts text the host kept back in the box, unless the user has already started typing in this page. */
  function restoreUnsentText(text) {
    if (typeof text !== 'string' || input.value) return;
    setSuggestion('');
    setInput(text);
  }

  let placeholder = input.placeholder;
  /** Only offered into an empty box while idle; typing, sending or a new turn takes it away. */
  function setSuggestion(text) {
    suggestion = idle && !input.value ? text : '';
    input.placeholder = suggestion ? suggestion + '   (Tab to use)' : placeholder;
  }

  function acceptSuggestion() {
    const text = suggestion;
    setSuggestion('');
    setInput(text);
    recall = -1;
  }

  /** The strip above the input: phase and tool name come from the IDE, shown as text only; the seconds are counted here. */
  const PHASE_LABEL = {
    starting: () => 'Starting…',
    thinking: () => 'Thinking…',
    running: (msg) => 'Running ' + (typeof msg.tool === 'string' ? msg.tool.slice(0, 40) : 'tool') + '…',
    writing: () => 'Writing…',
    waiting: () => 'Waiting for your answer',
  };
  let activityPhase = null;
  let activityLabelText = null;
  let activityStart = 0;
  let activityTimer = null;

  function tickActivity() {
    activitySecs.textContent = Math.max(0, Math.floor((Date.now() - activityStart) / 1000)) + 's';
  }

  function stopActivityTimer() {
    if (activityTimer !== null) { clearInterval(activityTimer); activityTimer = null; }
  }

  /**
   * Shows the phase from the IDE, or hides the strip without one; a changed label restarts the clock. Background work
   * alone has no phase: it shows in the task rows above the strip (see [setProcesses] and [renderAgent]).
   */
  function setActivity(msg) {
    const phase = typeof msg.phase === 'string' && Object.prototype.hasOwnProperty.call(PHASE_LABEL, msg.phase) ? msg.phase : null;
    if (!phase) {
      activityPhase = activityLabelText = null;
      stopActivityTimer();
      activityEl.hidden = true;
      return;
    }
    const label = PHASE_LABEL[phase](msg);
    if (phase === activityPhase && label === activityLabelText) return;
    activityPhase = phase;
    activityLabelText = label;
    activityLabel.textContent = label;
    const waiting = phase === 'waiting';
    activityDots.hidden = waiting;
    activitySecs.hidden = waiting;
    activityEl.hidden = false;
    stopActivityTimer();
    if (!waiting) {
      activityStart = Date.now();
      tickActivity();
      activityTimer = setInterval(tickActivity, 1000);
    }
  }

  /** Plain text only: the IDE's checkout name and folder never become markup. */
  function setCheckout(text) {
    checkoutEl.textContent = typeof text === 'string' ? text : '';
    checkoutEl.hidden = !checkoutEl.textContent;
  }

  /**
   * Shows the bar saying the checkout is on another branch than the one this chat worked on, with a button to switch
   * back, or hides it when the message names no branches. Branch names are plain text, never markup. Under the sentence: git's reason the last switch failed
   * (as an error), or else why switching waits.
   */
  function setBranchBar(msg) {
    const shown = typeof msg.home === 'string' && typeof msg.current === 'string';
    branchBar.hidden = !shown;
    if (!shown) return;
    branchBarText.replaceChildren('This chat worked on ', h('span', { class: 'branch' }, msg.home), ', but the checkout is on ', h('span', { class: 'branch' }, msg.current), '.');
    branchSwitchBtn.textContent = msg.switching ? 'Switching…' : `Switch to ${msg.home}`;
    branchSwitchBtn.disabled = !!msg.switching || typeof msg.blocked === 'string';
    branchSwitchBtn.title = `Check out ${msg.home} again`;
    const error = typeof msg.error === 'string' ? msg.error : '';
    const note = error || (typeof msg.blocked === 'string' ? msg.blocked : '');
    branchBarNote.textContent = note;
    branchBarNote.classList.toggle('error', !!error);
    branchBarNote.hidden = !note;
  }

  /** Longest pull request title the tooltip quotes in full; longer ones end in an ellipsis. */
  const PR_TITLE_MAX = 80;

  /** How the tooltip names a pull request in each status the IDE sends; an open one needs no word. */
  const PR_STATUS_WORD = { draft: 'draft ', merged: 'merged ' };

  /**
   * Shows the branch's pull request as `#<number>` beside View changes when the IDE found one (the IDE checks the
   * URL), and hides the button otherwise. The tooltip says what a click does and to which pull request: its title, and
   * whether it is a draft or already merged. Plain text only: the title never becomes markup.
   */
  function setPullRequest(msg) {
    const found = typeof msg.url === 'string' && Number.isInteger(msg.number);
    openPrBtn.hidden = !found;
    if (!found) return;
    openPrBtn.dataset.url = msg.url;
    openPrNumber.textContent = `#${msg.number}`;
    openPrBtn.setAttribute('aria-label', `Pull request #${msg.number}`);
    openPrBtn.title = pullRequestTooltip(msg.title, msg.status);
  }

  /** `Open “<title>” on GitHub`, naming a draft or merged pull request as such; without a title, `the pull request`. */
  function pullRequestTooltip(title, status) {
    const kind = PR_STATUS_WORD[status] ?? '';
    const text = typeof title === 'string' ? title.trim() : '';
    const shown = text.length > PR_TITLE_MAX ? `${text.slice(0, PR_TITLE_MAX - 1).trimEnd()}…` : text;
    return shown ? `Open ${kind}“${shown}” on GitHub` : `Open the ${kind}pull request on GitHub`;
  }

  /** The meter turns amber within this many percent of the window of autocompact (or of the window's end without it). */
  const USAGE_WARN_POINTS = 10;
  function fmtTokens(n) {
    if (n >= 1e6) return +(n / 1e6).toFixed(1) + 'M';
    if (n >= 1000) return +(n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
    return String(n);
  }
  const num = (v) => (typeof v === 'number' && isFinite(v) && v >= 0 ? v : null);

  /** The latest context usage, for the ring's tooltip: { used, max, p, at, categories, state }; null before the first. */
  let usage = null;

  /**
   * Shows the context ring for a `usage` message, or hides it when the message is incomplete. Its tooltip (see
   * [usageTip]) is built from [usage] when shown, and redrawn here if it is up; screen readers get the same facts as
   * the ring's label and description.
   */
  function setUsage(msg) {
    const used = num(msg.used), max = num(msg.max), pct = num(msg.percent);
    if (used === null || !max || pct === null) { usage = null; usageEl.hidden = true; return; }
    const p = Math.min(100, Math.round(pct));
    const at = num(msg.autocompactAt);
    const categories = (Array.isArray(msg.categories) ? msg.categories : [])
      .filter((c) => c && typeof c.name === 'string' && num(c.tokens) !== null)
      .sort((a, b) => b.tokens - a.tokens);
    const state = usageState(used, max, at);
    usage = { used, max, p, at, categories, state };
    usageEl.classList.toggle('full', state === 'full');
    usageEl.classList.toggle('near', state === 'near');
    usageRing.style.setProperty('--p', String(p));
    usagePct.textContent = p + '%';
    usageEl.setAttribute('aria-valuenow', String(p));
    usageEl.setAttribute('aria-label', usageHeadline(usage));
    usageEl.setAttribute('aria-description', [
      ...usageRoom(usage),
      ...categories.map((c) => c.name + ' ' + fmtTokens(c.tokens)),
      usageMeaning(state, at),
    ].filter(Boolean).join('. '));
    usageEl.hidden = false;
    refreshTip(usageEl);
  }

  /**
   * 'full' at or past autocompact (or the end of the window when there is none), 'near' within [USAGE_WARN_POINTS]% of
   * the window below that, else 'ok'. Judged in tokens, not the rounded percent the ring shows, so it never disagrees
   * with [usageRoom].
   */
  function usageState(used, max, at) {
    const limit = compactTokens(max, at) ?? max;
    if (used >= limit) return 'full';
    return used >= limit - max * USAGE_WARN_POINTS / 100 ? 'near' : 'ok';
  }

  /** The tokens at which the CLI autocompacts, or null when it doesn't. */
  const compactTokens = (max, at) => (at !== null ? Math.round(max * at / 100) : null);

  /** The tooltip's first line, also the ring's accessible name: tokens used of the window, and the percent. */
  const usageHeadline = (u) => 'Context: ' + fmtTokens(u.used) + ' of ' + fmtTokens(u.max) + ' tokens (' + u.p + '%)';

  /** How much room is left: before autocompact while it is ahead, and in the whole window. */
  function usageRoom(u) {
    const lines = [];
    const compactAt = compactTokens(u.max, u.at);
    if (compactAt !== null && u.used < compactAt) lines.push('~' + fmtTokens(compactAt - u.used) + ' until autocompact at ' + u.at + '%');
    lines.push(fmtTokens(Math.max(0, u.max - u.used)) + ' left in the window');
    return lines;
  }

  /** What the ring's colour means, or '' when it has none. */
  function usageMeaning(state, at) {
    const compacts = at !== null;
    if (state === 'near') return compacts ? 'Amber: close to autocompact' : 'Amber: the context window is nearly full';
    if (state !== 'full') return '';
    return compacts ? 'Red: Claude Code will summarise the conversation to make room' : 'Red: the context window is full';
  }

  /** A category's share of the window, as text: under 1% shows as "<1%". */
  const fmtShare = (share) => (share > 0 && share < 1 ? '<1%' : Math.round(share) + '%');

  /** The context ring's tooltip: tokens used, room left, the categories largest first with a bar each, and the colour's meaning. */
  function usageTip() {
    if (!usage) return null;
    const u = usage;
    const content = h('div', { class: 'usage-tip' },
      h('div', { class: 'usage-tip-head' }, usageHeadline(u)),
      ...usageRoom(u).map((line) => h('div', { class: 'usage-tip-room' }, line)));
    if (u.categories.length) {
      content.append(h('div', { class: 'usage-cats' }, ...u.categories.map((c) => {
        const share = c.tokens / u.max * 100;
        const bar = h('span', { class: 'usage-cat-bar', 'aria-hidden': 'true' });
        bar.style.setProperty('--w', Math.min(100, share).toFixed(1) + '%');
        return h('div', { class: 'usage-cat' },
          h('span', { class: 'usage-cat-name' }, c.name),
          h('span', { class: 'usage-cat-num' }, fmtTokens(c.tokens)),
          h('span', { class: 'usage-cat-num' }, fmtShare(share)),
          bar);
      })));
    }
    const meaning = usageMeaning(u.state, u.at);
    if (meaning) content.append(h('div', { class: 'usage-tip-state ' + u.state }, meaning));
    return content;
  }

  function addTile(id, name) {
    const tile = h('div', { class: 'tile loading', title: name },
      h('span', { class: 'img-alt' }, '…'),
      h('button', { class: 'tile-x', 'data-action': 'detach', 'data-id': id, title: 'Remove image' }, '×'));
    pending.set(id, { el: tile, ready: false });
    attachEl.append(tile);
    attachEl.hidden = false;
  }

  function removeTile(id) {
    const p = pending.get(id);
    if (!p) return;
    p.el.remove();
    pending.delete(id);
    attachEl.hidden = pending.size === 0;
  }

  /**
   * Outlines the message box while the IDE reports files dragged over the panel: the off-screen browser gets no drag
   * events of its own for them, so the IDE is the one that knows a drop will land here as attachments.
   */
  function showDropTarget(active) {
    document.getElementById('input-box').classList.toggle('drop-target', active);
  }

  /**
   * The IDE validated the image: show its thumbnail (or [image]); or it refused it (a notice in the log says why); or
   * it is still being prepared: a loading tile that blocks Send.
   */
  function setAttachment(msg) {
    if (typeof msg.id !== 'string') return;
    if (msg.failed === true) { removeTile(msg.id); return; }
    if (!pending.has(msg.id)) addTile(msg.id, typeof msg.name === 'string' ? msg.name : '');
    if (msg.loading === true) return;
    const p = pending.get(msg.id);
    p.ready = true;
    p.el.classList.remove('loading');
    p.el.firstChild.replaceWith(imageOrAlt(msg.thumb, '[image]'));
  }

  /**
   * Bytes go to the IDE as base64 in chunks; the IDE checks them (magic bytes, size) before anything else sees them.
   * A file over [MAX_INPUT], one past [MAX_ATTACH] (this tile included) or one whose reported type is not an image
   * sends no bytes: the IDE refuses it and says why (an empty file is "not an image"). A tile removed before its bytes
   * are read sends nothing more.
   */
  function upload(file) {
    const id = 'p' + (++attachSeq);
    const name = String(file.name || 'image').slice(0, 100);
    addTile(id, name);
    send({ type: 'attachStart', id, name, size: file.size });
    if (file.size > MAX_INPUT) return;
    if (pending.size > MAX_ATTACH) return;
    if (file.type && !/^image\//.test(file.type)) { send({ type: 'attachEnd', id }); return; }
    const reader = new FileReader();
    reader.onload = () => {
      if (!pending.has(id)) return;
      const url = String(reader.result);
      const data = url.slice(url.indexOf(',') + 1);
      for (let i = 0; i < data.length; i += CHUNK) send({ type: 'attachChunk', id, data: data.slice(i, i + CHUNK) });
      send({ type: 'attachEnd', id });
    };
    reader.onerror = () => { send({ type: 'detach', id }); removeTile(id); };
    reader.readAsDataURL(file);
  }

  function insertText(text) {
    const alreadyThere = rewinding !== null && text.trim() === rewinding.trim() && input.value === rewinding;
    rewinding = null;
    if (alreadyThere) return;
    recall = -1;
    const start = input.selectionStart, end = input.selectionEnd;
    const before = input.value.slice(0, start), after = input.value.slice(end);
    const sep = before && !/\s$/.test(before) ? ' ' : '';
    setInput(before + sep + text + ' ' + after, (before + sep + text + ' ').length);
    input.focus();
  }

  /** "High" for "high": how an effort level reads on the composer's model chip and in the picker. */
  const capitalize = (word) => word.charAt(0).toUpperCase() + word.slice(1);

  /**
   * The IDE's colours and font as CSS variables. The colour scheme is set too: native parts (a scrolling text box,
   * scrollbars) follow it, not the variables.
   */
  function applyTheme(t) {
    const s = document.documentElement.style;
    s.setProperty('--bg', t.bg); s.setProperty('--fg', t.fg); s.setProperty('--muted', t.muted);
    s.setProperty('--border', t.border); s.setProperty('--accent', t.accent); s.setProperty('--code-bg', t.codeBg);
    s.setProperty('--font', '"' + t.font + '", system-ui, sans-serif'); s.setProperty('--font-size', t.fontSize + 'px');
    s.setProperty('--add', t.dark ? '#2f5a3a' : '#d7f5dd'); s.setProperty('--del', t.dark ? '#5a2f2f' : '#f9d7d7');
    s.setProperty('--ok', t.dark ? '#5fb865' : '#2e7d32');
    s.setProperty('--warn', t.dark ? '#d29922' : '#9a6700'); s.setProperty('--danger', t.danger);
    s.setProperty('color-scheme', t.dark ? 'dark' : 'light');
    fitInput();
  }

  /**
   * Empties the box after a send as an edit, not by setting its value, so Undo straight afterwards brings the text
   * back to fix and send again. The images went with the message and don't come back.
   */
  function clearSentText(queued) {
    const text = input.value;
    input.focus();
    input.select();
    document.execCommand('delete');
    if (input.value) setInput('');
    sentText = queued || !text.trim() ? null : text;
  }

  /**
   * Escape in the box, outside a completion list: a second Escape on the brought-back text asks to revert to before
   * that message, once the turn has stopped and if the message can be reverted; in an empty box right after a send it
   * stops the turn and brings the text back, as Undo does; in any other empty box it stops a working turn. False when
   * it does none of these.
   */
  function onEscape() {
    if (takenBack !== null && input.value === takenBack) return askToRevert();
    if (input.value) return false;
    if (sentText !== null) return takeBack();
    if (!stoppable()) return false;
    send({ type: 'interrupt' });
    return true;
  }

  /**
   * Puts the sent text back in the box, as one undoable edit, and stops what it started: the turn, even one waiting
   * for the user's answer, or the send itself while it still waits for its notes.
   */
  function takeBack() {
    send({ type: 'interrupt' });
    const text = sentText;
    input.focus();
    document.execCommand('insertText', false, text);
    completion.close();
    sentText = null;
    takenBack = text;
    return true;
  }

  /** The "Revert to here" link of the message [text] became, if it is the newest one in the chat and offers one. */
  function revertLinkFor(text) {
    const newest = [...logContent.querySelectorAll(':scope > .msg.user')].pop();
    if (!newest || newest.dataset.text !== text.trim()) return null;
    return newest.querySelector('button.revert');
  }

  /**
   * Opens "Revert to here" for the brought-back message through its own link; the dialog asks what to revert, so
   * nothing changes without the user's choice. Waits while a turn is still stopping (Escape can ask again), and does
   * nothing for a message that can't be reverted.
   */
  function askToRevert() {
    if (activityPhase !== null) return true;
    const link = revertLinkFor(takenBack);
    if (!link) return false;
    rewinding = takenBack;
    link.click();
    return true;
  }

  /** Sends the box's text with the attachments in the tray, once every image is ready. */
  function submit() {
    const text = input.value.trim();
    if (input.disabled) return;
    const tiles = [...pending.entries()];
    if (tiles.some(([, p]) => !p.ready)) return;
    const attachments = tiles.map(([id]) => id);
    if (!text && !attachments.length) return;
    const queued = activityPhase !== null;
    send({ type: 'send', text, attachments });
    attachments.forEach(removeTile);
    clearSentText(queued);
    recall = -1;
    setSuggestion('');
  }

  /** Whether Up steps back through sent messages: from an empty box, or on from a recalled one. */
  const canRecallBack = () => recall !== -1 || !input.value;

  /** Keys that leave recall going: plain Up and Down step it, and a modifier pressed on its own is not a choice yet. */
  const RECALL_KEYS = new Set(['ArrowUp', 'ArrowDown', 'Shift', 'Control', 'Alt', 'Meta']);
  const hasModifier = (e) => e.shiftKey || e.altKey || e.ctrlKey || e.metaKey;

  /** Whether [e] ends recall, leaving the shown message to edit: any key but plain Up and Down, or a lone modifier. */
  const endsRecall = (e) => recall !== -1 && (!RECALL_KEYS.has(e.key) || (e.key.startsWith('Arrow') && hasModifier(e)));

  /**
   * Where recall goes from [current] (-1 for not recalling) in [dir], 'up' or 'down', through [count] sent messages:
   * Up starts at the newest and stops at the oldest, Down past the newest ends recall. Null when the key does nothing.
   */
  function recallStep(current, dir, count) {
    if (dir === 'up') {
      if (!count) return null;
      const from = current === -1 ? count : current;
      return Math.max(from - 1, 0);
    }
    if (current === -1) return null;
    return current + 1 < count ? current + 1 : -1;
  }

  /**
   * Steps recall in [dir] and shows the message as the box's value, never parsed as HTML; past the newest the box is
   * empty again, as recall started from an empty box. False when the key does nothing.
   */
  function recallKey(dir) {
    const next = recallStep(recall, dir, history.length);
    if (next === null) return false;
    recall = next;
    setInput(next === -1 ? '' : history[next]);
    return true;
  }

  input.addEventListener('keydown', (e) => {
    if (window.TextKeys.isComposing(e)) return;
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); return; }
    if (endsRecall(e)) recall = -1;
    if (hasModifier(e)) return;
    if (e.key === 'Escape') { if (onEscape()) e.preventDefault(); return; }
    if (e.key === 'Tab' && suggestion && !input.value) { e.preventDefault(); acceptSuggestion(); return; }
    if (e.key === 'ArrowUp' && canRecallBack() && recallKey('up')) e.preventDefault();
    else if (e.key === 'ArrowDown' && recall !== -1 && recallKey('down')) e.preventDefault();
  });
  input.addEventListener('pointerdown', () => { recall = -1; });
  /** Takes the agent's commands, and mentions `/` in the box's hint only while there are some to offer. */
  function setCommands(items) {
    commands = items;
    document.getElementById('hint-commands').hidden = !commands.length;
  }

  /**
   * Whether Escape in an empty box stops the turn: it is working, not waiting for the user's answer. Right after a
   * send, Escape stops it whatever it is doing, as it takes the message back (see [takeBack]).
   */
  const stoppable = () => activityPhase !== null && activityPhase !== 'waiting';

  /** Completes `/name` at the very start of the box from the agent's commands; accepting leaves room for arguments. */
  const commandCompletion = {
    label: 'Commands',
    empty: 'No matching commands',
    match: (before) => {
      const typed = /^\/(\S*)$/.exec(before);
      return typed && commands.length ? { start: 0, query: typed[1] } : null;
    },
    items: (query, show) => show(window.Completion.rank(commands, query, (c) => c.name)),
    row: (c) => ({ label: '/' + c.name + (c.hint ? ' ' + c.hint : ''), detail: c.description }),
    text: (c) => '/' + c.name + ' ',
  };

  /** How long typing must pause before the IDE is asked for files, in milliseconds. */
  const FILE_QUERY_DELAY_MS = 100;
  let fileQueryTimer = 0;
  /** The file search the list waits on; an older one the IDE dropped for a newer query never answers. */
  let fileRequest = 0;

  /** Stops waiting for the file search in flight, and for one about to be sent. */
  function cancelFileQuery() {
    clearTimeout(fileQueryTimer);
    pendingResolve.delete(fileRequest);
  }

  /**
   * Completes an `@` mention, at the start or after a space, from the project's files as the IDE finds them; an empty
   * query offers the open files. Accepting inserts the mention the IDE builds, as its "Add to Tether" action does.
   */
  const fileCompletion = {
    label: 'Files',
    empty: 'No matching files',
    match: (before) => {
      const typed = /(^|\s)@([^\s@]*)$/.exec(before);
      return typed ? { start: typed.index + typed[1].length, query: typed[2] } : null;
    },
    items: (query, show) => {
      cancelFileQuery();
      fileQueryTimer = setTimeout(() => {
        fileRequest = expectReply(show);
        send({ type: 'findFiles', reqId: fileRequest, query });
      }, FILE_QUERY_DELAY_MS);
    },
    cancel: cancelFileQuery,
    row: (f) => ({ label: f.name, detail: f.folder }),
    text: (f) => f.mention + ' ',
  };

  /** Typing ends recall and Escape's take-back, dismisses the suggestion and reports the text to the host. */
  function onTyped() {
    recall = -1;
    sentText = takenBack = rewinding = null;
    if (suggestion) setSuggestion('');
    fitInput();
    saveUnsentTextSoon();
  }
  input.addEventListener('input', onTyped);
  input.addEventListener('blur', saveUnsentText);
  window.addEventListener('pagehide', saveUnsentText);
  window.TextKeys.install(input);
  const completion = window.Completion.install(input, document.getElementById('completion'),
    document.getElementById('completion-status'), [commandCompletion, fileCompletion]);
  fitInput();
  sendBtn.addEventListener('click', submit);
  activityStop.addEventListener('click', () => send({ type: 'interrupt' }));
  restartBtn.addEventListener('click', () => send({ type: 'restart' }));
  document.getElementById('show-changes').addEventListener('click', () => send({ type: 'showChanges' }));
  openPrBtn.addEventListener('click', () => { if (!openPrBtn.hidden) send({ type: 'openUrl', url: openPrBtn.dataset.url }); });
  branchSwitchBtn.addEventListener('click', () => { if (!branchSwitchBtn.disabled) send({ type: 'switchBranch' }); });
  branchDismissBtn.addEventListener('click', () => send({ type: 'dismissBranchBar' }));
  for (const picker of pickers) {
    picker.button.addEventListener('click', () => (picker.menu.hidden ? openMenu(picker) : closeMenu(picker, true)));
    picker.button.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
      e.preventDefault();
      openMenu(picker);
    });
    picker.menu.addEventListener('keydown', (e) => onMenuKey(picker, e));
    picker.menu.addEventListener('pointermove', (e) => {
      const item = e.target.closest('[role="menuitemradio"]');
      if (item && trusted.has(item)) focusMenuItem(item);
    });
    picker.menu.addEventListener('click', (e) => {
      const item = e.target.closest('[role="menuitemradio"]');
      if (item) pickChoice(picker, item);
    });
    document.addEventListener('pointerdown', (e) => {
      if (!picker.button.parentElement.contains(e.target)) closeMenu(picker, false);
    });
    window.addEventListener('blur', () => closeMenu(picker, false));
    window.addEventListener('resize', () => closeMenu(picker, true));
  }
  modelBtn.addEventListener('click', openModelDialog);
  modelDialog.addEventListener('click', closeOnBackdropClick);
  attachBtn.addEventListener('click', () => send({ type: 'pickImages' }));

  /**
   * The image files on a clipboard (Cmd+V of a screenshot): pasted anywhere on the page, they become attachments,
   * while text pastes are untouched. A clipboard with text too is left to the message box, which pastes it as text:
   * spreadsheets and Office put a picture next to the text.
   */
  const pastedImages = (clipboard) => [...((clipboard && clipboard.items) || [])]
    .filter((i) => i.kind === 'file' && /^image\//.test(i.type)).map((i) => i.getAsFile()).filter(Boolean);
  document.addEventListener('paste', (e) => {
    if (e.target === input && e.clipboardData && e.clipboardData.getData('text/plain')) return;
    const files = pastedImages(e.clipboardData);
    if (!files.length) return;
    e.preventDefault();
    if (!input.disabled) files.forEach(upload);
  });

  /** Whether [form] is a method="dialog" form inside a dialog, whose submit just closes that dialog. */
  const closesItsDialog = (form) => form instanceof HTMLFormElement && form.method === 'dialog' && !!form.closest('dialog');

  /**
   * In the capture phase, so no anchor click or form submit reaches the browser's default navigation. A dialog's own
   * method="dialog" form still submits: it only closes its dialog (Done, Close) and navigates nowhere.
   */
  function blockDefaultNavigation() {
    document.addEventListener('auxclick', (e) => { if (e.target.closest('a')) e.preventDefault(); }, true);
    document.addEventListener('submit', (e) => { if (!closesItsDialog(e.target)) e.preventDefault(); }, true);
  }
  blockDefaultNavigation();

  /**
   * Whether a drag or drop carries files. Dropped files become attachments and, as the dragover and drop listeners
   * run in the capture phase, never reach the browser's default (opening one would navigate the page); other drops are
   * allowed only into the message box, as text.
   */
  const hasFiles = (dt) => !!dt && [...(dt.types || [])].includes('Files');
  document.addEventListener('dragover', (e) => {
    if (hasFiles(e.dataTransfer) || e.target !== input) {
      e.preventDefault();
      if (e.dataTransfer) e.dataTransfer.dropEffect = hasFiles(e.dataTransfer) && !input.disabled ? 'copy' : 'none';
    }
  }, true);
  document.addEventListener('drop', (e) => {
    const files = hasFiles(e.dataTransfer);
    if (!files && e.target === input) return;
    e.preventDefault();
    e.stopPropagation();
    if (files && !input.disabled) [...e.dataTransfer.files].forEach(upload);
  }, true);

  document.addEventListener('click', (e) => {
    const a = e.target.closest('a');
    if (a) {
      e.preventDefault();
      if (a.classList.contains('file')) {
        if (trusted.has(a)) send({ type: 'openFile', target: a.dataset.target, line: a.dataset.line ? Number(a.dataset.line) : null });
      } else if (/^https?:/i.test(a.getAttribute('href') || '')) send({ type: 'openUrl', url: a.href });
      return;
    }
    if (isOpenable(e.target)) { if (!selecting()) openImage(e.target); return; }
    const fileRow = e.target.closest('.turn-files .tool-row');
    if (fileRow) { if (trusted.has(fileRow)) openTurnDiff(fileRow); return; }
    const stubRow = e.target.closest('.agent-stub .tool-row');
    if (stubRow) { if (trusted.has(stubRow) && !selecting()) revealAgent(stubRow.dataset.id); return; }
    const b = e.target.closest('button[data-action]');
    if (!b) {
      const row = e.target.closest('.tool-row');
      if (row && trusted.has(row) && row.classList.contains('expandable') && !selecting()) toggleRow(row);
      return;
    }
    if (!trusted.has(b)) return;
    const action = b.dataset.action;
    if (action === 'diff') send({ type: 'openDiff', toolUseId: b.dataset.id });
    else if (action === 'turnFiles') toggleTurnFiles(b);
    else if (action === 'turnDiff') openTurnDiff(b);
    else if (action === 'turnChanges') send({ type: 'openTurnChanges', id: b.dataset.id });
    else if (action === 'detach') { send({ type: 'detach', id: b.dataset.id }); removeTile(b.dataset.id); }
    else if (action === 'allow' || action === 'allowSession') send({ type: 'permission', requestId: b.dataset.req, decision: action });
    else if (action === 'choose') send({ type: 'permission', requestId: b.dataset.req, optionId: b.dataset.option });
    else if (action === 'queueSendNow' || action === 'queueEdit' || action === 'queueRemove' || action === 'revertTo') send({ type: action, id: b.dataset.id });
    else if (action === 'pickModel' && agent === 'claude') send({ type: 'setModel', model: b.dataset.value });
    else if (action === 'pickEffort' && agent === 'claude') send({ type: 'setEffort', effort: b.dataset.value });
    else if (action === 'skipQuestion') send({ type: 'skipQuestion', requestId: b.dataset.req });
    else if (action === 'stopTask') {
      stopping.add(b.dataset.task);
      b.disabled = true;
      b.textContent = 'Stopping…';
      send({ type: 'stopTask', id: b.dataset.task });
    }
    else if (action === 'dismissTask') dismissAgent(b.dataset.id);
    else if (action === 'toggleAgents') toggleAgents();
    else if (action === 'showEarlierAgents') showEarlierAgents();
    else if (action === 'deny') {
      const reason = b.parentElement.querySelector('.deny-reason').value.trim();
      send({ type: 'permission', requestId: b.dataset.req, decision: 'deny', message: reason || 'The user denied this action.' });
    }
  });

  /** Whether [el] is an image the page made openable in the editor; never one from model markup. */
  const isOpenable = (el) => el instanceof HTMLImageElement && el.classList.contains('openable') && trusted.has(el);
  /** Whether the user has text selected, so a click ends a selection rather than acting. */
  const selecting = () => !!(window.getSelection && window.getSelection().toString());

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    if (isOpenable(e.target)) { e.preventDefault(); openImage(e.target); return; }
    const fileRow = e.target.closest && e.target.closest('.turn-files .tool-row');
    if (fileRow && e.target === fileRow && trusted.has(fileRow)) { e.preventDefault(); openTurnDiff(fileRow); return; }
    const stubRow = e.target.closest && e.target.closest('.agent-stub .tool-row');
    if (stubRow && e.target === stubRow && trusted.has(stubRow)) { e.preventDefault(); revealAgent(stubRow.dataset.id); return; }
    const toggle = e.target.classList && e.target.classList.contains('tool-toggle') && trusted.has(e.target) ? e.target : null;
    const row = toggle && toggle.closest('.tool-row.expandable');
    if (row && trusted.has(row)) { e.preventDefault(); toggleRow(row); }
  });

  /**
   * Tooltips for anything with a title or a rich tip. JCEF doesn't show the browser's own, so one shared #tip shows the text instead,
   * after a short hover or when keyboard focus lands on the element, and moves straight on when the pointer goes to
   * another titled element while one is showing. The title moves to data-tip the first time, so the browser's tooltip
   * never shows as well; the text stays available to screen readers as the element's aria-label when it has no other
   * name, otherwise as its aria-description. While a tooltip is up it follows its element: gone when the element is
   * removed or scrolled, updated when its title is set again. An element in [richTips] (marked data-rich-tip) shows
   * built content instead of a title; that tooltip takes the pointer and waits [RICH_TIP_GRACE_MS] before hiding, so
   * the pointer can cross the gap onto it and it stays up while the pointer is over it.
   */
  const tip = document.getElementById('tip');
  /** Builders of the tooltip content for elements registered with [richTip]; each returns a Node, or null for none. */
  const richTips = new Map();
  const TIP_DELAY_MS = 500;
  const TIP_GAP = 6;
  const RICH_TIP_GRACE_MS = 300;
  let tipHideTimer = 0;
  /** The element whose tooltip is showing or about to show; also the one just pressed, so it stays quiet until left. */
  let tipFor = null;
  let tipTimer = 0;
  const tipWatch = new MutationObserver(() => {
    if (!tipFor || !shown(tipFor)) hideTip();
    else if (tipFor.hasAttribute('title')) showTip(tipFor);
  });

  /** Whether [el] is on the page and laid out: not removed, and neither it nor an ancestor hidden. */
  const shown = (el) => el.isConnected && !el.closest('[hidden]');

  /** [el]'s tooltip text, taking over its title (which code may set again at any time). */
  function tipText(el) {
    const title = el.getAttribute('title');
    if (title !== null) {
      el.removeAttribute('title');
      el.dataset.tip = title;
      const named = el.hasAttribute('aria-label') || el.textContent.trim();
      if (!named) el.setAttribute('aria-label', title);
      else if (!el.hasAttribute('aria-description')) el.setAttribute('aria-description', title);
    }
    return el.dataset.tip || '';
  }

  /** The element with a tooltip (titled or rich) at or around [target], or null; none while its menu or popup is open. */
  function tipTarget(target) {
    const el = target instanceof Element ? target.closest('[title], [data-tip], [data-rich-tip]') : null;
    return el && el.getAttribute('aria-expanded') !== 'true' ? el : null;
  }

  /** Shows [el]'s tooltip centred below it, or above when there's no room below, kept inside the page. */
  function showTip(el) {
    const build = richTips.get(el);
    const content = build ? build() : tipText(el);
    if (!content || !shown(el)) return hideTip();
    tip.replaceChildren(content);
    tip.classList.toggle('rich', !!build);
    tip.style.top = tip.style.left = '0';
    tip.hidden = false;
    tipWatch.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['title', 'hidden'] });
    const at = el.getBoundingClientRect(), size = tip.getBoundingClientRect();
    const below = at.bottom + TIP_GAP + size.height <= window.innerHeight;
    const top = below ? at.bottom + TIP_GAP : Math.max(TIP_GAP, at.top - TIP_GAP - size.height);
    const left = Math.min(Math.max(TIP_GAP, at.left + (at.width - size.width) / 2), window.innerWidth - size.width - TIP_GAP);
    tip.style.top = top + 'px';
    tip.style.left = left + 'px';
  }

  /** Hides the tooltip, cancels one about to show, and stops following its element. */
  function hideTip() {
    clearTimeout(tipTimer);
    keepTip();
    tipWatch.disconnect();
    tipFor = null;
    tip.hidden = true;
  }

  /** Redraws [el]'s tooltip if it is the one showing, after what it shows has changed. */
  function refreshTip(el) {
    if (tipFor === el && !tip.hidden) showTip(el);
  }

  /**
   * Hides a rich tooltip after the grace period, unless the pointer comes back first; any other one at once. One its
   * element has keyboard focus for stays: the pointer wandering off isn't the user leaving it.
   */
  function hideTipSoon() {
    if (!tip.hidden && tipFor?.matches(':focus-visible')) return;
    if (tip.hidden || !tip.classList.contains('rich')) return hideTip();
    if (!tipHideTimer) tipHideTimer = setTimeout(hideTip, RICH_TIP_GRACE_MS);
  }

  /** Keeps the tooltip up: the pointer is back on it or on its element. */
  function keepTip() {
    clearTimeout(tipHideTimer);
    tipHideTimer = 0;
  }

  /** Shows [el]'s tooltip now if one is already up (moving along a toolbar), otherwise after the hover delay. */
  function scheduleTip(el) {
    clearTimeout(tipTimer);
    keepTip();
    tipFor = el;
    if (!tip.hidden) return showTip(el);
    tipTimer = setTimeout(() => showTip(el), TIP_DELAY_MS);
  }

  document.addEventListener('pointerover', (e) => {
    if (tip.contains(e.target)) return keepTip();
    const el = tipTarget(e.target);
    if (el === tipFor) return keepTip();
    if (el) scheduleTip(el);
    else hideTipSoon();
  });
  document.addEventListener('pointerout', (e) => {
    const to = e.relatedTarget;
    if (!to || !(tip.contains(to) || tipTarget(to))) hideTipSoon();
  });
  document.addEventListener('focusin', (e) => {
    const el = tipTarget(e.target);
    if (el && e.target.matches(':focus-visible')) scheduleTip(el);
  });
  document.addEventListener('focusout', hideTip);
  document.addEventListener('pointerdown', (e) => {
    if (tip.contains(e.target)) return;
    hideTip();
    tipFor = tipTarget(e.target);
  }, true);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideTip(); }, true);
  document.addEventListener('scroll', (e) => {
    if (tipFor && e.target.contains(tipFor)) hideTip();
  }, { capture: true, passive: true });
  window.addEventListener('blur', hideTip);

  /** Gives [el] a tooltip built by [build] (a Node, or null for none) instead of one from its title. */
  function richTip(el, build) {
    el.dataset.richTip = '';
    richTips.set(el, build);
  }

  richTip(usageEl, usageTip);

  window.addEventListener('bridge-ready', () => send({ type: 'ready' }));
})();
