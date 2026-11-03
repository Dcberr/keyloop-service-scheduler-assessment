'use strict';

const el = (id) => document.getElementById(id);
const sides = ['a', 'b'];
const state = { busy: false, searching: false, cancel: null, customers: [], dealers: [], services: [], vehicles: [], saved: {}, blocked: new Set(), page: 0, total: 0 };
const controls = ['load', 'customer', 'dealer', 'service', 'start', 'vehicle-a', 'vehicle-b', 'find', 'check', 'book-a', 'book-b', 'get-a', 'get-b', 'copy-a', 'copy-b', 'history', 'previous', 'next'];

function notice(message, error = false) {
  el('notice').textContent = message;
  el('notice').className = error ? 'notice error' : 'notice';
}
function request(side) {
  return { customerId: el('customer').value, vehicleId: el('vehicle-' + side).value, dealershipId: el('dealer').value, serviceTypeId: el('service').value, startsAt: el('start').value.trim() };
}
function prepared() { return el('customer').value && el('dealer').value && el('service').value && el('vehicle-a').value && el('vehicle-b').value && el('vehicle-a').value !== el('vehicle-b').value; }
function validStart() {
  const value = el('start').value.trim();
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
}
function fingerprint(side) { return JSON.stringify(request(side)); }
function sync() {
  controls.forEach((id) => { el(id).disabled = state.busy; });
  el('cancel').hidden = !state.searching;
  if (state.busy) return;
  el('customer').disabled = !state.customers.length;
  el('dealer').disabled = !state.dealers.length;
  el('service').disabled = !state.services.length;
  el('start').disabled = !state.services.length;
  sides.forEach((side) => {
    el('vehicle-' + side).disabled = !state.vehicles.length;
    el('book-' + side).disabled = !prepared() || !validStart() || state.blocked.has(fingerprint(side));
    el('get-' + side).disabled = !state.saved[side];
    el('copy-' + side).disabled = !prepared() || !validStart();
  });
  el('find').disabled = !prepared();
  el('check').disabled = !prepared() || !validStart();
  el('history').disabled = !el('customer').value;
  el('previous').disabled = !el('customer').value || state.page === 0;
  el('next').disabled = !el('customer').value || (state.page + 1) * 10 >= state.total;
}
function updateRequests() {
  sides.forEach((side) => { el('request-' + side).value = JSON.stringify(request(side), null, 2); });
  const dealer = state.dealers.find((item) => item.id === el('dealer').value);
  const service = state.services.find((item) => item.id === el('service').value);
  el('service-info').textContent = service && dealer ? service.durationMinutes + ' minutes · ' + dealer.timeZone + ' · UTC or explicit-offset input; your browser time zone is not used.' : 'Select a dealership and service.';
  sync();
}
function resetResults() {
  state.saved = {};
  sides.forEach((side) => {
    badge(side, 'Not checked');
    el('availability-' + side).textContent = 'Inputs changed. Check availability for this request.';
    el('response-meta-' + side).textContent = 'No response for the current inputs.';
    el('response-' + side).textContent = '{}';
    el('result-' + side).open = false;
  });
  updateRequests();
}
function resetHistory() {
  state.page = 0; state.total = 0;
  el('history-rows').replaceChildren();
  el('history-summary').textContent = 'No history loaded for this customer.';
  el('history-response').textContent = '{}'; el('history-meta').textContent = '';
  el('page-label').textContent = 'Page 1';
}
function badge(side, message, kind = '') {
  el('badge-' + side).textContent = message;
  el('badge-' + side).className = 'badge ' + kind;
}
function fill(id, items, label, preferred) {
  const select = el(id); select.replaceChildren();
  if (!items.length) select.add(new Option('No data available', ''));
  items.forEach((item) => select.add(new Option(label(item), item.id)));
  if (preferred && items.some((item) => item.id === preferred)) select.value = preferred;
}
async function api(path, { method = 'GET', body, signal } = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) controller.abort();
  const timeout = setTimeout(abort, 15000);
  try {
    const response = await fetch('/api/v1' + path, { method, headers: { Accept: 'application/json', 'X-Request-ID': 'demo-' + crypto.randomUUID(), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined, signal: controller.signal, redirect: 'error' });
    const parsed = await response.json();
    return { status: response.status, body: parsed, requestId: response.headers.get('X-Request-ID'), location: response.headers.get('Location') };
  } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); }
}
function assertOk(result) {
  if (result.status >= 400) throw new Error('HTTP ' + result.status + ' · ' + (result.body.code || 'Request failed') + ': ' + (result.body.detail || 'Inspect the response.'));
  return result.body;
}
function meta(result) { return 'HTTP ' + result.status + ' · Request ID: ' + (result.requestId || 'not provided') + (result.location ? ' · Location: ' + result.location : ''); }
function show(side, result) {
  el('response-meta-' + side).textContent = meta(result);
  el('response-' + side).textContent = JSON.stringify(result.body, null, 2);
  el('result-' + side).open = true;
}
async function all(path) {
  const items = [];
  for (let page = 0; ; page++) {
    const data = assertOk(await api(path + '?size=100&page=' + page));
    items.push(...data.items);
    if (items.length >= data.totalElements || !data.items.length) return items;
  }
}
async function run(action) {
  if (state.busy) return;
  state.busy = true; sync();
  try { await action(); }
  catch (error) { notice(state.cancel?.signal.aborted ? 'Search cancelled. No booking was submitted.' : error.message || 'The request could not be completed. Check application readiness.', true); }
  finally { state.busy = false; state.searching = false; state.cancel = null; updateRequests(); }
}
async function vehicles() {
  state.vehicles = []; fill('vehicle-a', [], () => ''); fill('vehicle-b', [], () => '');
  if (el('customer').value) state.vehicles = await all('/customers/' + el('customer').value + '/vehicles');
  const label = (v) => v.make + ' ' + v.model + ' · ' + v.id;
  fill('vehicle-a', state.vehicles, label);
  fill('vehicle-b', state.vehicles, label, state.vehicles[1]?.id);
}
async function services() {
  state.services = []; fill('service', [], () => '');
  if (el('dealer').value) state.services = await all('/dealerships/' + el('dealer').value + '/services');
  fill('service', state.services, (s) => s.name + ' · ' + s.durationMinutes + ' min', state.services.find((s) => s.name === 'Oil Change')?.id);
}
el('load').addEventListener('click', () => run(async () => {
  notice('Loading customers, dealerships and available services…');
  state.customers = []; state.dealers = []; state.services = []; state.vehicles = [];
  ['customer', 'dealer', 'service', 'vehicle-a', 'vehicle-b'].forEach((id) => fill(id, [], () => ''));
  resetResults(); resetHistory();
  state.customers = await all('/customers');
  state.dealers = await all('/dealerships');
  fill('customer', state.customers, (c) => c.displayName + ' · ' + c.id);
  fill('dealer', state.dealers, (d) => d.name + ' · ' + d.id);
  await vehicles(); await services();
  notice(!state.customers.length || !state.dealers.length || !state.services.length || state.vehicles.length < 2 ? 'This scenario needs a customer with two vehicles and an offered service. Run ./scripts/demo/seed.sh after startup, or choose another customer/dealership.' : 'Data loaded. Find a shared slot, check both vehicles, then book A followed by B.');
}));
el('customer').addEventListener('change', () => run(async () => { resetResults(); resetHistory(); await vehicles(); notice('Customer changed. Select two different vehicles and check a future time.'); }));
el('dealer').addEventListener('change', () => run(async () => { resetResults(); await services(); notice('Dealership changed. Check availability again.'); }));
['service', 'vehicle-a', 'vehicle-b', 'start'].forEach((id) => el(id).addEventListener(id === 'start' ? 'input' : 'change', () => { resetResults(); notice('Inputs updated. Use whole-minute timestamps ending in :00Z or :00±HH:MM.'); }));

async function availability(side, signal) {
  badge(side, 'Checking…');
  el('availability-' + side).textContent = 'Waiting for a current availability response.';
  const result = await api('/availability?' + new URLSearchParams(request(side)), { signal });
  show(side, result);
  if (result.status >= 400) {
    badge(side, 'HTTP ' + result.status, 'bad');
    el('availability-' + side).textContent = 'Availability could not be established. Inspect the response.';
  }
  const value = assertOk(result);
  badge(side, value.available ? 'Available' : 'Unavailable', value.available ? 'good' : 'bad');
  el('availability-' + side).textContent = 'Last observation: ' + (value.available ? 'Available' : value.reason) + ' · ends ' + value.endsAt + ' · checked ' + value.checkedAt;
  return value.available;
}
el('check').addEventListener('click', () => run(async () => {
  notice('Checking both vehicles. This does not reserve capacity…');
  const a = await availability('a'); const b = await availability('b');
  notice(a && b ? 'Both vehicles observed availability. Book A, then book B without changing the time.' : 'At least one request is unavailable. Inspect the reasons or find another time.');
}));
el('find').addEventListener('click', () => run(async () => {
  state.searching = true; state.cancel = new AbortController(); sync();
  resetResults();
  const first = Math.ceil((Date.now() + 60000) / 3600000) * 3600000;
  for (let i = 0; i < 24; i++) {
    if (state.cancel.signal.aborted) throw new Error('Search cancelled.');
    el('start').value = new Date(first + i * 4 * 3600000).toISOString().replace('.000Z', 'Z');
    sides.forEach((side) => { badge(side, 'Not checked'); el('availability-' + side).textContent = 'No observation for this candidate yet.'; });
    updateRequests(); notice('Searching candidate ' + (i + 1) + ' of 24 · ' + el('start').value);
    const a = await availability('a', state.cancel.signal);
    const b = a && await availability('b', state.cancel.signal);
    if (a && b) { notice('Shared slot found. Both vehicles observed availability. Book A, then B to demonstrate the recheck.'); return; }
  }
  notice('No shared slot found among these 24 candidates. Try a manual future time or extend the demo calendar. This was not a search of every possible slot.', true);
}));
el('cancel').addEventListener('click', () => state.cancel?.abort());

sides.forEach((side) => {
  el('copy-' + side).addEventListener('click', () => run(async () => { await navigator.clipboard.writeText(JSON.stringify(request(side), null, 2)); notice('Request ' + side.toUpperCase() + ' copied. Paste it into Swagger UI to execute the same request.'); }));
  el('book-' + side).addEventListener('click', () => run(async () => {
    const body = request(side); const key = fingerprint(side);
    if (state.blocked.has(key)) return;
    notice('Submitting vehicle ' + side.toUpperCase() + ' once. Waiting for the transaction outcome…');
    let result;
    try { result = await api('/appointments', { method: 'POST', body }); }
    catch (error) {
      state.blocked.add(key); badge(side, 'Outcome unknown', 'bad');
      el('response-meta-' + side).textContent = 'No confirmed HTTP response. Do not replay this request.';
      el('response-' + side).textContent = JSON.stringify({ message: 'The connection ended before a usable response arrived. Inspect customer history before deciding whether the appointment exists.', request: body }, null, 2);
      el('result-' + side).open = true;
      notice('The booking outcome is unknown. This request is blocked from resubmission in this session. Load customer history; do not blindly retry or refresh to retry.', true);
      return;
    }
    show(side, result);
    if (result.status === 201) {
      state.blocked.add(key); state.saved[side] = result.body.id; badge(side, 'Booked · 201', 'good');
      notice('Vehicle ' + side.toUpperCase() + ' booked successfully. Appointment ' + result.body.id + '. Retrieve it or submit the other prepared request.');
    } else { badge(side, 'HTTP ' + result.status, 'bad'); notice('Vehicle ' + side.toUpperCase() + ': ' + (result.body.code || 'Unexpected response') + '. ' + (result.body.detail || 'Inspect the response.'), true); }
  }));
  el('get-' + side).addEventListener('click', () => run(async () => {
    const result = await api('/appointments/' + state.saved[side]); show(side, result); assertOk(result);
    notice('Retrieved the persisted appointment for vehicle ' + side.toUpperCase() + '. Compare its allocation with the booking response.');
  }));
});
async function history(page) {
  const result = await api('/appointments?' + new URLSearchParams({ customerId: el('customer').value, page, size: 10 }));
  el('history-meta').textContent = meta(result); el('history-response').textContent = JSON.stringify(result.body, null, 2);
  const data = assertOk(result); state.page = data.page; state.total = data.totalElements;
  el('history-rows').replaceChildren();
  data.items.forEach((row) => {
    const tr = document.createElement('tr');
    [row.id, row.vehicleId, row.startsAt, row.status].forEach((value) => { const td = document.createElement('td'); td.textContent = value; tr.append(td); });
    el('history-rows').append(tr);
  });
  el('page-label').textContent = 'Page ' + (state.page + 1);
  el('history-summary').textContent = state.total ? data.items.length + ' appointments on this page · ' + state.total + ' total for this customer.' : 'No appointments found for this customer.';
  notice('History loaded. Compare vehicle, service, dealership and start time in the response when investigating an uncertain booking.');
}
el('history').addEventListener('click', () => run(() => history(0)));
el('previous').addEventListener('click', () => run(() => history(state.page - 1)));
el('next').addEventListener('click', () => run(() => history(state.page + 1)));
sync();
