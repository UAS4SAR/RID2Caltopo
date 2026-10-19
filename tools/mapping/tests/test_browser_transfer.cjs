// Exercise the shipped page logic with browser-shaped DOM/File/fetch objects.
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const { File } = require('node:buffer');
const assets = path.join(__dirname, '../../../app/src/main/assets/mapping-transfer');
const elements = new Map();
function element(id) {
  if (!elements.has(id)) elements.set(id, { hidden: true, textContent: '', value: '', disabled: false, children: [],
    appendChild(child) { this.children.push(child); }, replaceChildren() { this.children = []; }, click() {}, remove() {} });
  return elements.get(id);
}
const token = 'a'.repeat(64), input = crypto.randomBytes(2 * 1024 * 1024 + 123);
input[0] = 255; input[1] = 216;
const expected = crypto.createHash('sha256').update(input).digest('hex');
let captureProfile;
let offset = 0, completed = false, puts = 0, pauseOnce = true, paired = false;
function response(value) { return { ok: true, json: async () => value }; }
const context = vm.createContext({ console, AbortController, Blob, File, Uint8Array, URL, setTimeout,
  location: { hash: '', pathname: '/' }, history: { replaceState() {} },
  sessionStorage: { getItem() { return null; }, setItem(_, value) { assert.equal(value, token); } },
  document: { getElementById: element, createElement: () => ({ textContent: '' }), body: { appendChild() {} } },
  fetch: async (url, options = {}) => {
    if (url === '/api/pair') { assert.equal(JSON.parse(options.body).code, '123456'); paired = true; return response({ token }); }
    assert.equal(options.headers.Authorization, 'Bearer ' + token);
    if (url === '/api/status') return response({ photoCount: completed ? 1 : 0, storedBytes: completed ? input.length : 0, pendingCount: completed ? 0 : 1,
      expiresAt: Date.now() + 60000, mission: { captureProfile, title: 'Flight test', aircraftModel: 'M4TD', cameraName: 'Thermal', profile: 'thermal', parameters: {
        heightMeters: 60, desiredGroundResolutionCmPerPixel: 6, speedMetersPerSecond: 2, forwardOverlapPercent: 85, sideOverlapPercent: 80 } } });
    if (url === '/api/photos') { assert.equal(JSON.parse(options.body).sha256, expected); return response({ offset, complete: completed }); }
    if (options.method === 'PUT') {
      assert.equal(Number(options.headers['X-Upload-Offset']), offset);
      const data = Buffer.from(await options.body.arrayBuffer());
      assert.deepEqual(data, input.subarray(offset, offset + data.length)); offset += data.length; puts++;
      if (pauseOnce) { pauseOnce = false; element('cancel').onclick(); throw Object.assign(Error('Paused'), { name: 'AbortError' }); }
      return response({ offset, complete: false });
    }
    if (url.endsWith('/commit')) { assert.equal(offset, input.length); completed = true; return response({ complete: true }); }
    if (url === '/api/complete') { assert.deepEqual(JSON.parse(options.body).sha256, [expected]); assert.ok(completed); return response({ photoCount: 1, storedBytes: input.length }); }
    throw Error('Unexpected action: ' + url);
  }
});
vm.runInContext(fs.readFileSync(path.join(assets, 'sha256.js'), 'utf8') + '\n' + fs.readFileSync(path.join(assets, 'transfer.js'), 'utf8'), context);
(async () => {
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(element('pairing').hidden, false);
  element('code').value = '123456'; await element('pair').onclick();
  assert.ok(paired); assert.equal(element('workspace').hidden, false);
  assert.equal(element('settings').children.length, 4);
  assert.match(element('settings').children[3].textContent, /-90° gimbal · 0° grid · 85% forward \/ 80% side/);
  captureProfile = { passes: [
    {gimbalPitchDegrees:-90,directionDegrees:0,forwardOverlapPercent:85,sideOverlapPercent:85},
    {gimbalPitchDegrees:-70,directionDegrees:90,forwardOverlapPercent:85,sideOverlapPercent:85}
  ] };
  await vm.runInContext('connect()', context);
  assert.equal(element('settings').children.length, 5);
  assert.match(element('settings').children[4].textContent, /-70° gimbal · 90° grid/);
  element('photos').files = [new File([input], 'DJI_ORIGINAL_IR.JPG', { type: 'image/jpeg' })];
  await element('photos').onchange();
  assert.equal(offset, 1024 * 1024); assert.equal(completed, false); assert.match(element('transfer').textContent, /paused/);
  await element('photos').onchange(); assert.ok(completed); assert.equal(offset, input.length);
  const previousPuts = puts; await element('photos').onchange(); assert.equal(puts, previousPuts);
  assert.match(element('transfer').textContent, /All 1 selected photos verified/);
  console.log('Browser upload flow: short-code pairing, pause/resume, full checksum, and duplicate skip passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
