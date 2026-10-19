const assert=require('node:assert/strict');
const C=require('../../../app/src/main/assets/mapping-surface/collection.js');
function fixture(x,altitude){
 const p=new Float32Array([0,0,0,1,0,0,0,0,1]),n=new Float32Array([0,1,0,0,1,0,0,1,0]),c=new Uint8Array(12).fill(255),i=new Uint32Array([0,1,2]);const arrays=[p,n,c,i];let bytes=0;const views=arrays.map(a=>{const v={buffer:0,byteOffset:bytes,byteLength:a.byteLength};bytes+=a.byteLength;return v});
 const m={meshes:[{primitives:[{attributes:{POSITION:0,NORMAL:1,COLOR_0:2},indices:3}]}],bufferViews:views,accessors:[{bufferView:0,componentType:5126,count:3},{bufferView:1,componentType:5126,count:3},{bufferView:2,componentType:5121,count:3},{bufferView:3,componentType:5125,count:3}],extras:{originMercatorX:x,originMercatorY:0,originDjiAltitude:altitude,mercatorToGroundScale:.5}};
 const json=new TextEncoder().encode(JSON.stringify(m)),len=(json.length+3)&~3,b=new ArrayBuffer(28+len+bytes),d=new DataView(b),u=new Uint8Array(b);d.setUint32(0,0x46546c67,true);d.setUint32(4,2,true);d.setUint32(8,b.byteLength,true);d.setUint32(12,len,true);d.setUint32(16,0x4e4f534a,true);u.fill(32,20,20+len);u.set(json,20);d.setUint32(20+len,bytes,true);d.setUint32(24+len,0x004e4942,true);let o=28+len;for(const a of arrays){u.set(new Uint8Array(a.buffer),o);o+=a.byteLength;}return b;
}
const result=C.parse(C.combine([fixture(0,100),fixture(20,105)]));
assert.deepEqual([...result.index],[0,1,2,3,4,5]);assert.deepEqual([...result.position.slice(9,12)],[10,5,0]);assert.equal(result.m.extras.regionCount,2);assert.deepEqual(result.m.accessors[0].max,[11,5,1]);
const legacy=C.combine([fixture(0,undefined),fixture(20,undefined)],[{originDjiAltitude:100},{originDjiAltitude:105}]);assert.deepEqual([...C.parse(legacy).position.slice(9,12)],[10,5,0]);
assert.throws(()=>C.paths({regions:[{path:'../outside.glb'}]}),/Invalid/);
assert.throws(()=>C.combine([new ArrayBuffer(C.LIMIT+1)]),/memory budget/);
const fallbackTest=(async()=>{const original=global.fetch;global.fetch=async path=>{if(path.endsWith('collection.json'))throw Error('scheme: file not found');return{ok:true,arrayBuffer:async()=>fixture(0,100)}};try{assert.equal(C.parse(await C.load('test',()=>{})).position.length,9)}finally{global.fetch=original}console.log('Combined-region coordinates, indices, limits and single-model fallback passed');})().catch(e=>{console.error(e);process.exitCode=1});

// Explicit selections must bypass a cached successful collection response and
// retain distinct resource identities when switching between regions.
(async()=>{await fallbackTest;const original=global.fetch,calls=[];global.fetch=async(path,options)=>{calls.push(path);assert.equal(options.cache,'no-store');assert.match(path,/regions\/[01]\/surface\.glb$/);return{ok:true,arrayBuffer:async()=>fixture(path.includes('/1/')?20:0,100)}};
try{await C.load('selected',()=>{},'regions/0/surface.glb');await C.load('selected',()=>{},'regions/1/surface.glb');assert.deepEqual(calls,['/model/selected/regions/0/surface.glb','/model/selected/regions/1/surface.glb']);await assert.rejects(()=>C.load('selected',()=>{},'../outside.glb'),/Invalid selected/);}finally{global.fetch=original;}console.log('Explicit region selection and resource identity passed');})().catch(e=>{console.error(e);process.exitCode=1});

const mixed=C.parse(C.combine([fixture(0,100),fixture(20,105)],[{}, {reconstructionMode:"approximate-plane"}]));
assert.equal(mixed.m.extras.reconstructionMode,"approximate-plane","Combined viewer must retain the approximate-depth warning from any region");
