const $ = (selector) => document.querySelector(selector);
const html = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[character]));
let state = {clients: [], events: [], deliveries: [], messages: [], endpointNames: {}};
let clientsVersion = '';


let selectedDelivery = null;
let detailVersion = '';
let polling = false;

async function api(path, method = 'GET', data) {
  const response = await fetch(path, {
    method,
    headers: data === undefined ? {} : {'Content-Type': 'application/json'},
    body: data === undefined ? undefined : JSON.stringify(data),
    signal: AbortSignal.timeout(18000)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
  return result;
}

function notice(message, error = false) {
  $('#notice').hidden = false;
  $('#notice').textContent = message;
  $('#notice').className = error ? 'error' : '';
}

async function run(button, task) {
  button.disabled = true;
  try { await task(); }
  catch (error) { notice(error.message, true); }
  finally {
    if (button.isConnected) button.disabled = button.id === 'add' && state.clients.length >= 10;
  }
}

function time(value) {
  return value ? new Date(value).toLocaleTimeString('en-GB') : '-';
}

function statusClass(status) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : 'waiting';
}

function option(value, current, label = value) {
  return `<option value="${html(value)}" ${value === current ? 'selected' : ''}>${html(label)}</option>`;
}

const drafts = new Map();

function renderClients() {
  $('#count').textContent = `${state.clients.length}/10`;
  $('#add').disabled = state.clients.length >= 10;
  const version = JSON.stringify(state.clients);
  if (version !== clientsVersion && !($('#clients').contains(document.activeElement) && document.activeElement.matches('input, textarea'))) {
    document.querySelectorAll('[data-send-form]').forEach((form) => drafts.set(form.dataset.sendForm, {
      type: form.elements.type.value, data: form.elements.data.value, key: form.elements.key.value
    }));
    clientsVersion = version;
    $('#clients').innerHTML = state.clients.length ? state.clients.map((client) => {
      const draft = drafts.get(client.id) || {type: 'order.paid', data: '{"orderId": 123, "amount": 1500}', key: ''};
      return `<section class="client" data-client="${html(client.id)}">
        <div class="client-header"><input data-field="name" value="${html(client.name)}" maxlength="60" aria-label="Client name"><button data-remove="${html(client.id)}" aria-label="Remove ${html(client.name)}">×</button></div>
        <div class="fields"><label>Role<select data-field="role">${['sender', 'receiver', 'both'].map((role) => option(role, client.role)).join('')}</select></label>
        <label>Response<select data-field="mode" ${client.role === 'sender' ? 'disabled' : ''}>${option('success', client.mode, '200')}${option('error', client.mode, '500')}${option('timeout', client.mode, 'Timeout')}</select></label></div>
        <label>Subscriptions<input data-field="subscriptions" value="${html(client.subscriptions.join(', '))}" ${client.role === 'sender' ? 'disabled' : ''}></label>
        ${client.role !== 'receiver' ? `<details class="compose"><summary>Send event</summary><form data-send-form="${html(client.id)}"><label>Type<input name="type" value="${html(draft.type)}" required maxlength="255"></label><label>JSON<textarea name="data" spellcheck="false">${html(draft.data)}</textarea></label><label>Idempotency key<input name="key" value="${html(draft.key)}" maxlength="255"></label><button type="submit">Send</button></form></details>` : ''}
        <div class="logs" data-client-logs="${html(client.id)}" tabindex="0" aria-label="${html(client.name)} logs"></div>
      </section>`;
    }).join('') : '<p class="empty">No clients</p>';
  }
  renderClientLogs();
}

function deliveryLog(delivery) {
  return `<div class="log"><span class="${statusClass(delivery.status)}">${html(delivery.status)}</span> · Event ${delivery.eventId}<br>${html(state.senders?.[delivery.eventId] || '-')} → ${html(state.endpointNames[delivery.endpointId])}<br>HTTP ${delivery.httpStatus ?? '-'} · Attempts ${delivery.attemptCount}/3${delivery.nextAttemptAt ? ` · Retry <span data-retry="${html(delivery.nextAttemptAt)}"></span>` : ''}<br><button data-details="${delivery.id}">Details #${delivery.id}</button></div>`;
}

function updateLog(element, content) {
  if (element.dataset.content === content) return;
  const scroll = element.scrollTop;
  element.innerHTML = content;
  element.dataset.content = content;
  element.scrollTop = scroll;
}

function renderClientLogs() {
  state.clients.forEach((client) => {
    const element = document.querySelector(`[data-client-logs="${client.id}"]`);
    if (!element) return;
    const received = state.messages.filter((message) => message.clientId === client.id).reverse().map((message) => `<div class="log">${time(message.receivedAt)} · Received event ${html(message.body?.id)}<br>Response ${message.mode === 'timeout' ? 'Timeout' : message.responseStatus} · Signature <span class="${message.signatureValid ? 'success' : 'error'}">${message.signatureValid ? 'OK' : 'Invalid'}</span><details><summary>JSON</summary><pre>${html(JSON.stringify(message.body, null, 2))}</pre></details></div>`);
    const sent = state.events.filter((event) => state.senders?.[event.id] === client.name).reverse().map((event) => `<div class="log">${time(event.createdAt)} · Sent event ${event.id}<br>${html(event.type)}</div>`);
    updateLog(element, [...received, ...sent].join('') || '<p class="empty">No logs</p>');
  });
}

function renderDeliveries() {
  const deliveries = [...state.deliveries].filter((delivery) => $('#filter').value === 'all' || delivery.status === $('#filter').value).sort((a, b) => b.id - a.id);
  updateLog($('#deliveries'), deliveries.map(deliveryLog).join('') || '<p class="empty">No deliveries</p>');
  document.querySelectorAll('[data-retry]').forEach((element) => {
    element.textContent = `${Math.max(0, Math.ceil((new Date(element.dataset.retry) - Date.now()) / 1000))}s`;
  });
}

async function refresh() {
  const snapshot = await api('/demo/state');
  state = {...snapshot, deliveries: snapshot.deliveries.filter((delivery) => snapshot.endpointNames[delivery.endpointId])};
  $('#connection').textContent = 'Connected';
  renderClients();
  renderDeliveries();

  if (selectedDelivery !== null && $('#details').open) {
    const current = state.deliveries.find((item) => item.id === selectedDelivery);
    if (current && JSON.stringify(current) !== detailVersion) await showDelivery(selectedDelivery);
  }
}

async function poll() {
  if (!polling) {
    polling = true;
    try { await refresh(); }
    catch { $('#connection').textContent = 'Disconnected'; }
    finally { polling = false; }
  }
  setTimeout(poll, 2000);
}

async function showDelivery(id) {
  selectedDelivery = id;
  const delivery = state.deliveries.find((item) => item.id === id);
  const attempts = await api(`/demo/deliveries/${id}/attempts`);
  if (selectedDelivery !== id) return;
  detailVersion = JSON.stringify(delivery);
  const event = state.events.find((item) => item.id === delivery.eventId);
  $('#detail-title').textContent = `Delivery ${id}`;
  $('#detail-body').innerHTML = `<p>Event ${delivery.eventId} → ${html(state.endpointNames[delivery.endpointId])} · <span class="${statusClass(delivery.status)}">${delivery.status}</span></p>
    <table><thead><tr><th>#</th><th>Status</th><th>HTTP</th><th>Time</th><th>Error</th></tr></thead><tbody>${attempts.length ? attempts.map((attempt) => `<tr><td>${attempt.attemptNumber}</td><td>${attempt.status}</td><td>${attempt.httpStatus ?? '-'}</td><td>${time(attempt.completedAt)}</td><td>${html(attempt.error)}</td></tr>`).join('') : '<tr><td colspan="5">No attempts</td></tr>'}</tbody></table>
    <pre>${html(event ? JSON.stringify(JSON.parse(event.payload), null, 2) : '')}</pre>
    ${delivery.status === 'FAILED' ? '<button id="retry">Retry</button>' : ''}`;
  $('#retry')?.addEventListener('click', (event) => run(event.currentTarget, async () => {
    const ids = new Set(state.deliveries.map((item) => item.id));
    try { await api(`/demo/deliveries/${id}/retry`, 'POST'); }
    finally { await refresh(); }
    const repeated = state.deliveries.find((item) => !ids.has(item.id) && item.eventId === delivery.eventId && item.endpointId === delivery.endpointId);
    if (repeated && $('#details').open) await showDelivery(repeated.id);
  }));
  if (!$('#details').open) $('#details').showModal();
}

$('#add').addEventListener('click', (event) => run(event.currentTarget, async () => {
  let number = state.clients.length + 1;
  while (state.clients.some((client) => client.name === `Client ${number}`)) number++;
  await api('/demo/clients', 'POST', {name: `Client ${number}`, role: 'both', subscriptions: ['order.paid']});
  await refresh();
}));
$('#example').addEventListener('click', (event) => run(event.currentTarget, async () => {
  const clients = [{name: 'Shop', role: 'sender', subscriptions: []}, {name: 'Warehouse', role: 'receiver', subscriptions: ['order.paid']}, {name: 'Notifications', role: 'receiver', subscriptions: ['order.paid']}];
  for (const request of clients) {
    if (state.clients.some((client) => client.name === request.name)) continue;
    const client = await api('/demo/clients', 'POST', request);
    if (client.name === 'Notifications') await api(`/demo/clients/${client.id}`, 'PUT', {...client, mode: 'error'});
  }
  await refresh();
}));
$('#clients').addEventListener('change', async (event) => {
  if (!event.target.matches('[data-field]')) return;
  const row = event.target.closest('[data-client]');
  if (!row) return;
  const client = state.clients.find((item) => item.id === row.dataset.client);
  const field = (name) => row.querySelector(`[data-field="${name}"]`).value;
  try {
    await api(`/demo/clients/${client.id}`, 'PUT', {name: field('name'), role: field('role'), mode: field('mode'), subscriptions: field('role') === 'sender' ? [] : field('subscriptions').split(',').map((type) => type.trim()).filter(Boolean)});
    await refresh();
  } catch (error) { notice(error.message, true); }
});
$('#clients').addEventListener('click', (event) => {
  const button = event.target.closest('[data-remove]');
  if (button) run(button, async () => { await api(`/demo/clients/${button.dataset.remove}`, 'DELETE'); await refresh(); });
});
$('#clients').addEventListener('submit', (event) => {
  const form = event.target.closest('[data-send-form]');
  if (!form) return;
  event.preventDefault();
  run(form.querySelector('button[type="submit"]'), async () => {
    let data;
    try { data = JSON.parse(form.elements.data.value); } catch { throw new Error('Invalid JSON'); }
    const ids = new Set(state.events.map((item) => item.id));
    const result = await api('/demo/events', 'POST', {clientId: form.dataset.sendForm, type: form.elements.type.value, data, idempotencyKey: form.elements.key.value.trim()});
    notice(ids.has(result.id) ? `Event ${result.id}: existing` : `Event ${result.id}: created`);
    await refresh();
  });
});
$('#filter').addEventListener('change', renderDeliveries);
$('.workspace').addEventListener('click', (event) => {
  const button = event.target.closest('[data-details]');
  if (button) run(button, () => showDelivery(Number(button.dataset.details)));
});
$('#close').addEventListener('click', () => { selectedDelivery = null; $('#details').close(); });
poll();
