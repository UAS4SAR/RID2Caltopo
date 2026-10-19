const assert=require('node:assert/strict');
const scale=require('../../../app/src/main/assets/mapping-surface/scale.js');
// Independently project a horizontal segment at the orbit target with a 45°
// perspective camera; verify the legend in CSS pixels on wide/tall displays.
for(const [width,height] of [[1200,800],[800,1200],[320,640]])for(const units of ["imperial","metric"])for(const distance of [.1,1,30,1000])for(const dpr of [1,2,3]){
 const f=1/Math.tan(Math.PI/8),s=scale.forView(distance,height,f,width,units);
 const ndcX=(s.meters/distance)*f/(width/height);
 const projectedDevicePixels=ndcX*(width*dpr)/2;
 assert.ok(Math.abs(projectedDevicePixels/dpr-s.pixels)<1e-9);
 assert.ok(s.pixels>0&&s.pixels<=Math.min(160,width*.3)+1e-9);
 assert.ok(Math.abs(s.feet*.3048-s.meters)<1e-10);
 assert.match(s.label,units==="metric"?/ m$/:/ ft$/);
}
assert.equal(scale.forView(20,800,2,1200).metersPerPixel,2*scale.forView(10,800,2,1200).metersPerPixel);
for(const bad of [0,-1,NaN,Infinity])assert.equal(scale.forView(bad,800,2,1200),null);
console.log('3D scale matches perspective projection, zoom, viewport and pixel-density changes');
