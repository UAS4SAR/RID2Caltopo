'use strict';
// GLB coordinates are metres. The camera-right axis is horizontal; its projection
// at the orbit target defines this reference scale, not distances elsewhere in view.
globalThis.R2CScale={
 forView(distance,height,focal,width,units="imperial"){
  if(![distance,height,focal,width].every(v=>Number.isFinite(v)&&v>0))return null;
  const metersPerPixel=2*distance/(height*focal),limit=Math.min(160,width*.3),metersPerUnit=units==="metric"?1:.3048,maxUnits=limit*metersPerPixel/metersPerUnit;
  const power=10**Math.floor(Math.log10(maxUnits)),fraction=maxUnits/power;
  const value=(fraction>=5?5:fraction>=2?2:1)*power,meters=value*metersPerUnit,feet=meters/.3048,pixels=meters/metersPerPixel;
  const format=v=>v.toLocaleString(undefined,{maximumFractionDigits:v<1?3:1});
  return{feet,meters,pixels,metersPerPixel,label:format(value)+(units==='metric'?' m':' ft')};
 }
};
if(typeof module!=='undefined')module.exports=globalThis.R2CScale;
