const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../app/src/main/assets/mapping-transfer/sha256.js'), 'utf8');
const context = vm.createContext({});
vm.runInContext(source + '\nglobalThis.Hash = R2CSha256;', context);
for (const length of [0, 1, 55, 56, 63, 64, 65, 1000, 1024 * 1024 + 31, 5 * 1024 * 1024]) {
  const input = crypto.randomBytes(length), expected = crypto.createHash('sha256').update(input).digest('hex');
  const hash = new context.Hash();
  for (let offset = 0; offset < input.length; offset += 7919) hash.update(input.subarray(offset, offset + 7919));
  assert.equal(hash.hex(), expected, `Streaming checksum for ${length} bytes`);
}
console.log('Browser SHA-256: 10 vectors and fragmented reads passed.');
