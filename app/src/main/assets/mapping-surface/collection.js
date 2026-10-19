'use strict';
// Combine saved regional meshes in their shared georeferenced frame. Bound peak
// memory on field tablets; individual-region viewing remains available above it.
(function(root){
 const LIMIT=32*1024*1024;
 function paths(document){
  if(!Array.isArray(document.regions)||!document.regions.length||document.regions.length>200)throw Error('Invalid surface collection');
  return document.regions.map((r,i)=>{if(r.path!==`regions/${i}/surface.glb`)throw Error('Invalid region path');return r.path;});
 }
 function parse(buffer){
  const d=new DataView(buffer);if(buffer.byteLength<28||d.getUint32(0,true)!==0x46546c67||d.getUint32(4,true)!==2||d.getUint32(8,true)!==buffer.byteLength||d.getUint32(16,true)!==0x4e4f534a)throw Error('Invalid regional model');
  const j=d.getUint32(12,true),m=JSON.parse(new TextDecoder().decode(new Uint8Array(buffer,20,j))),base=28+j,p=m.meshes[0].primitives[0];
  if(d.getUint32(24+j,true)!==0x004e4942)throw Error('Missing regional geometry');
  function attribute(index,Type,n,type){const a=m.accessors[index],v=m.bufferViews[a.bufferView];
   if(a.componentType!==type||v.buffer!==0||v.byteStride||!Number.isSafeInteger(a.count)||a.count<0)throw Error('Unsupported regional geometry');
   return new Type(buffer,base+(v.byteOffset||0)+(a.byteOffset||0),a.count*n);
  }
  const position=attribute(p.attributes.POSITION,Float32Array,3,5126),normal=attribute(p.attributes.NORMAL,Float32Array,3,5126),color=attribute(p.attributes.COLOR_0,Uint8Array,4,5121),index=attribute(p.indices,Uint32Array,1,5125);
  if(normal.length!==position.length||color.length/4!==position.length/3||index.length%3)throw Error('Invalid regional attributes');
  return {m,position,normal,color,index};
 }
 function combine(buffers,metadata=[]){
  if(buffers.reduce((s,b)=>s+b.byteLength,0)>LIMIT)throw Error('Combined view is too large for the tablet memory budget. Choose an individual region.');
  const models=buffers.map(parse);models.forEach((m,i)=>{if(metadata[i])m.m.extras={...m.m.extras,...metadata[i]};});
  const origin=models[0].m.extras,keys=['originMercatorX','originMercatorY','originDjiAltitude','mercatorToGroundScale'];
  if(models.some(m=>keys.some(k=>!Number.isFinite(m.m.extras?.[k]))||m.m.extras.mercatorToGroundScale<=0))throw Error('Missing regional coordinate frame');
  const vertices=models.reduce((s,m)=>s+m.position.length/3,0),count=models.reduce((s,m)=>s+m.index.length,0);
  const positions=new Float32Array(vertices*3),normals=new Float32Array(vertices*3),colors=new Uint8Array(vertices*4),indices=new Uint32Array(count),min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
  let v=0,t=0;
  for(const a of models){const meta=a.m.extras,factor=origin.mercatorToGroundScale/meta.mercatorToGroundScale,dx=(meta.originMercatorX-origin.originMercatorX)*origin.mercatorToGroundScale,dz=-(meta.originMercatorY-origin.originMercatorY)*origin.mercatorToGroundScale,dy=meta.originDjiAltitude-origin.originDjiAltitude;
   for(let i=0;i<a.position.length;i+=3){const p=[a.position[i]*factor+dx,a.position[i+1]+dy,a.position[i+2]*factor+dz];
    if(!p.every(Number.isFinite))throw Error('Invalid regional position');
    const n=[a.normal[i]/factor,a.normal[i+1],a.normal[i+2]/factor],length=Math.hypot(...n)||1;
    for(let k=0;k<3;k++){positions[v*3+i+k]=p[k];normals[v*3+i+k]=n[k]/length;min[k]=Math.min(min[k],p[k]);max[k]=Math.max(max[k],p[k]);}}
   colors.set(a.color,v*4);for(const i of a.index){if(i>=a.position.length/3)throw Error('Invalid regional index');indices[t++]=i+v;}v+=a.position.length/3;
  }
  const arrays=[positions,normals,colors,indices],views=[];let bytes=0;for(const a of arrays){views.push({buffer:0,byteOffset:bytes,byteLength:a.byteLength});bytes+=a.byteLength;}
  const m={asset:{version:'2.0'},scene:0,scenes:[{nodes:[0]}],nodes:[{mesh:0}],meshes:[{primitives:[{attributes:{POSITION:0,NORMAL:1,COLOR_0:2},indices:3}]}],buffers:[{byteLength:bytes}],bufferViews:views,accessors:[{bufferView:0,componentType:5126,count:vertices,type:'VEC3',min,max},{bufferView:1,componentType:5126,count:vertices,type:'VEC3'},{bufferView:2,componentType:5121,count:vertices,type:'VEC4',normalized:true},{bufferView:3,componentType:5125,count,type:'SCALAR'}],extras:{...origin,reconstructionMode:models.some(m=>m.m.extras?.reconstructionMode==='approximate-plane')?'approximate-plane':'measured-surface',regionCount:models.length,vertexCount:vertices,triangleCount:count/3}};
  const json=new TextEncoder().encode(JSON.stringify(m)),jl=(json.length+3)&~3,result=new ArrayBuffer(28+jl+bytes),d=new DataView(result),out=new Uint8Array(result);
  d.setUint32(0,0x46546c67,true);d.setUint32(4,2,true);d.setUint32(8,result.byteLength,true);d.setUint32(12,jl,true);d.setUint32(16,0x4e4f534a,true);out.fill(32,20,20+jl);out.set(json,20);d.setUint32(20+jl,bytes,true);d.setUint32(24+jl,0x004e4942,true);let offset=28+jl;for(const a of arrays){out.set(new Uint8Array(a.buffer),offset);offset+=a.byteLength;}return result;
 }
 async function load(id,progress,selected=''){
  if(selected && selected!=='surface.glb' && !/^regions\/[0-9]{1,3}\/surface\.glb$/.test(selected))throw Error('Invalid selected region');
  const prefix='/model/'+id+'/';
  // Each region has an explicit resource identity. Never infer selection from
  // a failed collection request or a mutable alias cached by the WebView.
  if(selected){const r=await fetch(prefix+selected,{cache:'no-store'});if(!r.ok)throw Error('Selected region unavailable');return r.arrayBuffer();}
  let response;try{response=await fetch(prefix+'collection.json');}catch(_){}
  if(!response?.ok){const r=await fetch(prefix+'surface.glb');if(!r.ok)throw Error('Local model unavailable');return r.arrayBuffer();}
  const names=paths(await response.json()),buffers=[],metadata=[];let size=0;
  for(let i=0;i<names.length;i++){progress(`Loading 3D region ${i+1} of ${names.length}…`);const r=await fetch(prefix+names[i]);if(!r.ok)throw Error(`Region ${i+1} unavailable`);
   const declared=Number(r.headers.get('Content-Length'));if(declared>LIMIT-size)throw Error('Combined view is too large for the tablet memory budget. Choose an individual region.');
   const b=await r.arrayBuffer();size+=b.byteLength;if(size>LIMIT)throw Error('Combined view is too large for the tablet memory budget. Choose an individual region.');buffers.push(b);
   // Earlier Apple meshes stored their vertical datum only in the sidecar.
   if(!Number.isFinite(parse(b).m.extras?.originDjiAltitude)){const side=await fetch(prefix+names[i].replace('surface.glb','surface.json'));if(!side.ok)throw Error('Regional height reference unavailable');metadata[i]=await side.json();}
  }
  progress('Preparing combined 3D view…');return combine(buffers,metadata);
 }
 root.R2CCollection={paths,parse,combine,load,LIMIT};if(typeof module!=='undefined')module.exports=root.R2CCollection;
})(typeof window!=='undefined'?window:globalThis);
