const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {SHA256}=require('../web/app.js');
for(const length of [0,1,55,56,63,64,65,4096,1000000]){
  const bytes=crypto.randomBytes(length),hash=new SHA256();
  for(let offset=0;offset<bytes.length;offset+=37)hash.update(bytes.subarray(offset,offset+37));
  assert.equal(hash.hex(),crypto.createHash('sha256').update(bytes).digest('hex'));
}
console.log('SHA-256 streaming vectors passed');
