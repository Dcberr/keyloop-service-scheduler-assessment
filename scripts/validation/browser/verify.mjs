import { strict as assert } from 'node:assert';
import { mkdir, writeFile } from 'node:fs/promises';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.argv[2];
if (!base || !/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(base)) throw new Error('Pass an isolated local application URL');
const mode = process.argv[3] || 'flow';
const output = new URL('../../../target/browser-verification/', import.meta.url);
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) });
const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
const external = [], errors = [], posts = [], checks = [];
await context.route('**/*', route => {
  if (new URL(route.request().url()).origin !== base) { external.push(route.request().url()); return route.abort(); }
  return route.continue();
});
const page = await context.newPage();
page.on('pageerror', e => errors.push(e.message));
page.on('request', request => { if (request.method() === 'POST') posts.push(request.postDataJSON()); });
const pass = label => { checks.push(label); console.log('PASS: ' + label); };
const click = id => page.locator('#' + id).click();
async function idle() { await page.waitForFunction(() => !document.getElementById('load').disabled); }
async function load() { await page.goto(base + '/demo/index.html'); await click('load'); await idle(); }
async function find() { await click('find'); await idle(); assert.match(await page.locator('#notice').innerText(), /Shared slot found/); }
async function badge(side, pattern) { assert.match(await page.locator('#badge-' + side).innerText(), pattern); }
try {
  await page.goto(base + '/demo/index.html');
  assert.equal(posts.length, 0);
  await click('load'); await idle();
  if (mode === 'empty') {
    assert.match(await page.locator('#notice').innerText(), /seed.sh/);
    assert.equal(await page.locator('#book-a').isDisabled(), true);
    pass('Empty fresh PostgreSQL gives seed guidance and no POST');
  } else if (mode === 'outage') {
    assert.match(await page.locator('#notice').innerText(), /503/);
    assert.equal(posts.length, 0);
    pass('Real database outage renders controlled 503 without booking');
  } else {
    assert.equal(await page.locator('#find').isEnabled(), true);
    assert.notEqual(await page.locator('#vehicle-a').inputValue(), await page.locator('#vehicle-b').inputValue());
    assert.match(await page.locator('#service-info').innerText(), /minutes.*Asia/);
    pass('Catalog discovery selects two actual vehicles and service metadata');
    await find(); await badge('a', /^Available$/); await badge('b', /^Available$/);
    const time = await page.locator('#start').inputValue();
    assert.equal(posts.length, 0);
    pass('Bounded search finds shared future availability using GET only');
    // Dispatch a real second click while the first POST is pending.
    await page.locator('#book-a').evaluate(button => { button.click(); button.click(); });
    await idle(); await badge('a', /Booked.*201/);
    assert.equal(posts.length, 1);
    const appointment = JSON.parse(await page.locator('#response-a').innerText());
    assert.ok(appointment.serviceBayId && appointment.technicianId && appointment.id);
    assert.equal(await page.locator('#start').inputValue(), time);
    assert.equal(await page.locator('#book-a').isDisabled(), true);
    await click('book-b'); await idle(); await badge('b', /409/);
    assert.equal(posts.length, 2);
    pass('Double click sends one committed A booking; unchanged B receives real 409');
    await click('get-a'); await idle();
    assert.equal(JSON.parse(await page.locator('#response-a').innerText()).id, appointment.id);
    await click('history'); await idle();
    assert.match(await page.locator('#history-response').textContent(), new RegExp(appointment.id));
    await page.screenshot({ path: new URL('demo-booking.png', output).pathname, fullPage: true });
    pass('GET and customer history retrieve persisted allocation');
    await page.locator('#start').fill('invalid');
    await badge('a', /Not checked/);
    assert.equal(await page.locator('#book-a').isDisabled(), true);
    assert.equal(await page.locator('#get-a').isDisabled(), true);
    pass('Changed or invalid input clears stale results and disables booking');
    await find();
    // Commit on the real server, then drop the response to test ambiguous client outcome.
    let droppedId;
    await page.route('**/api/v1/appointments', async route => {
      if (route.request().method() !== 'POST') return route.continue();
      const response = await route.fetch();
      assert.equal(response.status(), 201);
      droppedId = (await response.json()).id;
      await route.abort('connectionfailed');
    }, { times: 1 });
    const before = posts.length;
    await click('book-a'); await idle(); await badge('a', /Outcome unknown/);
    assert.equal(posts.length, before + 1);
    assert.equal(await page.locator('#book-a').isDisabled(), true);
    await click('history'); await idle();
    assert.match(await page.locator('#history-response').textContent(), new RegExp(droppedId));
    assert.equal(posts.length, before + 1);
    pass('Dropped response after real commit gives unknown outcome, no replay, history recovery');
    // Cancellation uses a delayed GET response; this is a browser fault simulation.
    await page.route('**/api/v1/availability?*', async route => {
      await new Promise(resolve => setTimeout(resolve, 500));
      try { await route.continue(); } catch {}
    });
    await click('find'); await click('cancel'); await idle();
    assert.match(await page.locator('#notice').innerText(), /cancelled/);
    await page.unroute('**/api/v1/availability?*');
    pass('Search cancellation returns control without a POST');
    // Prepare a fresh valid body for Swagger's actual Execute operation.
    await find();
    const swaggerRequest = JSON.parse(await page.locator('#request-a').inputValue());
    await page.goto(base + '/swagger-ui/index.html');
    await page.waitForSelector('.opblock');
    assert.equal(await page.locator('.opblock').count(), 8);
    const catalog = page.locator('#operations-Catalog-listDealerships');
    await catalog.locator('.opblock-summary').click();
    await catalog.getByRole('button', { name: 'Try it out' }).click();
    await catalog.getByRole('button', { name: 'Execute', exact: true }).click();
    await catalog.locator('.responses-table.live-responses-table .response-col_status').filter({ hasText: '200' }).waitFor();
    const create = page.locator('#operations-Appointments-createAppointment');
    await create.locator('.opblock-summary').click();
    await create.getByRole('button', { name: 'Try it out' }).click();
    await create.locator('textarea').fill(JSON.stringify(swaggerRequest));
    await create.getByRole('button', { name: 'Execute', exact: true }).click();
    await create.locator('.responses-table.live-responses-table .response-col_status').filter({ hasText: '201' }).waitFor();
    await page.screenshot({ path: new URL('swagger-execute.png', output).pathname, fullPage: true });
    pass('Swagger renders all eight operations and executes real GET200 / POST201 on alternate port');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + '/demo/index.html');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: new URL('demo-mobile.png', output).pathname, fullPage: true });
    pass('Mobile viewport has no horizontal page overflow');
  }
  assert.deepEqual(external, []);
  assert.deepEqual(errors, []);
  pass('No external runtime requests or uncaught page errors');
  await writeFile(new URL(mode + '-report.json', output), JSON.stringify({ status: 'passed', base, mode, browser: browser.version(), checks, posts: posts.length, external, errors }, null, 2));
} finally { await browser.close(); }
