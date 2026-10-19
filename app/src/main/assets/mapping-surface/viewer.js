'use strict';
// Small offline glTF 2.0 inspector. Reads only POSITION/NORMAL/COLOR_0 and indices
// from our binary GLB; no URI, extensions, scripts, external textures or plugins.
(async()=>{
 const canvas=document.getElementById('scene'),status=document.getElementById('status');
 try{
  const id=location.hash.slice(1);if(!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id))throw Error('Invalid local model');
  const buffer=await R2CCollection.load(id,message=>{status.textContent=message;},new URLSearchParams(location.search).get('surface')||''),data=new DataView(buffer);
  if(data.getUint32(0,true)!==0x46546c67||data.getUint32(4,true)!==2||data.getUint32(8,true)!==buffer.byteLength||data.getUint32(16,true)!==0x4e4f534a)throw Error('Unsupported model');
  const jsonLength=data.getUint32(12,true),model=JSON.parse(new TextDecoder().decode(new Uint8Array(buffer,20,jsonLength))),binary=28+jsonLength;
  if(data.getUint32(24+jsonLength,true)!==0x004e4942)throw Error('Missing geometry');
  const gl=canvas.getContext('webgl2',{antialias:true})||canvas.getContext('webgl',{antialias:true});if(!gl)throw Error('3D graphics unavailable');
  if(!(typeof WebGL2RenderingContext!=='undefined'&&gl instanceof WebGL2RenderingContext)&&!gl.getExtension('OES_element_index_uint'))throw Error('32-bit geometry unavailable');
  if(model.extras?.title)document.querySelector('h1').textContent=model.extras.title+' · 3D';
  function shader(type,source){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;}
  const program=gl.createProgram();gl.attachShader(program,shader(gl.VERTEX_SHADER,'attribute vec3 position;attribute vec3 normal;attribute vec4 color;uniform mat4 mvp;varying vec4 shade;void main(){float light=.65+.35*max(0.,dot(normalize(normal),normalize(vec3(-.4,1.,.3))));shade=vec4(color.rgb*light,color.a);gl_Position=mvp*vec4(position,1.);}'));
  gl.attachShader(program,shader(gl.FRAGMENT_SHADER,'precision mediump float;varying vec4 shade;void main(){if(shade.a<=0.)discard;gl_FragColor=shade;}'));gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error('Cannot link 3D renderer');gl.useProgram(program);
  const primitive=model.meshes[0].primitives[0];
  let positions,sourceColors,colorBuffer;
  for(const [name,key,components] of [['position','POSITION',3],['normal','NORMAL',3],['color','COLOR_0',4]]){
   const a=model.accessors[primitive.attributes[key]],v=model.bufferViews[a.bufferView];if(v.buffer!==0)throw Error('External buffer rejected');
   const b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Uint8Array(buffer,binary+(v.byteOffset||0),v.byteLength),gl.STATIC_DRAW);
   const attribute=gl.getAttribLocation(program,name);gl.enableVertexAttribArray(attribute);gl.vertexAttribPointer(attribute,components,a.componentType,!!a.normalized,0,a.byteOffset||0);
   if(key==='POSITION')positions=new Float32Array(buffer,binary+(v.byteOffset||0)+(a.byteOffset||0),a.count*3);
   if(key==='COLOR_0'){if(a.componentType!==5121||a.byteOffset)throw Error('Unsupported colors');colorBuffer=b;sourceColors=new Uint8Array(buffer,binary+(v.byteOffset||0),a.count*4);}
  }
  const a=model.accessors[primitive.indices],v=model.bufferViews[a.bufferView];if(a.componentType!==5125)throw Error('Unsupported indices');
  const sourceIndices=new Uint32Array(buffer,binary+(v.byteOffset||0)+(a.byteOffset||0),a.count);
  function indexBuffer(indices){const b=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,b);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,indices,gl.STATIC_DRAW);return b;}
  const fullBuffer=indexBuffer(sourceIndices),pos=model.accessors[primitive.attributes.POSITION];
  let clipped=null,opaqueBuffer,edgeBuffer,soft=false;
  // Legacy local products recover the original mission snapshot through the native route.
  // New shared GLBs also carry that snapshot in their own metadata.
  let boundary;try{const response=await fetch('/model/'+id+'/boundary.json');if(response.ok)boundary=await response.json();}catch(_){}
  if(!boundary&&model.extras?.assignmentBoundary)boundary={coordinates:model.extras.assignmentBoundary,soft:true};
  const meta=model.extras||{},coordinates=boundary?.coordinates;
  if(Array.isArray(coordinates)&&coordinates.length>=3&&coordinates.length<=2000&&coordinates.every(p=>Array.isArray(p)&&p.length===2&&p.every(Number.isFinite)&&Math.abs(p[0])<=180&&Math.abs(p[1])<=85)&&
     [meta.originMercatorX,meta.originMercatorY,meta.mercatorToGroundScale].every(Number.isFinite)&&meta.mercatorToGroundScale>0){
   const polygon=coordinates.map(p=>[(6378137*p[0]*Math.PI/180-meta.originMercatorX)*meta.mercatorToGroundScale,
    (6378137*Math.log(Math.tan(Math.PI/4+p[1]*Math.PI/360))-meta.originMercatorY)*meta.mercatorToGroundScale]);
   clipped=R2CBoundary.prepare(positions,sourceColors,sourceIndices,polygon,5);
   if(!clipped.min.every(Number.isFinite)||clipped.opaque.length+clipped.edge.length===0)clipped=null;
   else{opaqueBuffer=indexBuffer(clipped.opaque);edgeBuffer=indexBuffer(clipped.edge);soft=boundary.soft!==false;}
  }
  let center,extent;
  function fitBounds(){const min=soft?clipped.min:pos.min,max=soft?clipped.max:pos.max;center=min.map((v,i)=>(v+max[i])/2);extent=Math.max(...min.map((v,i)=>max[i]-v),.1);}
  function colors(){gl.bindBuffer(gl.ARRAY_BUFFER,colorBuffer);gl.bufferData(gl.ARRAY_BUFFER,soft?clipped.colors:sourceColors,gl.STATIC_DRAW);}
  fitBounds();colors();
  let yaw=.6,pitch=.9,distance=extent*1.35,target=center.slice();const uniform=gl.getUniformLocation(program,'mvp');
  const sub=(a,b)=>a.map((x,i)=>x-b[i]),dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0),norm=a=>{const l=Math.hypot(...a);return a.map(x=>x/l)},cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  function multiply(a,b){const out=new Float32Array(16);for(let c=0;c<4;c++)for(let r=0;r<4;r++)for(let k=0;k<4;k++)out[c*4+r]+=a[k*4+r]*b[c*4+k];return out;}
  function draw(){const ratio=Math.min(devicePixelRatio||1,2);canvas.width=Math.floor(canvas.clientWidth*ratio);canvas.height=Math.floor(canvas.clientHeight*ratio);gl.viewport(0,0,canvas.width,canvas.height);
   const eye=[target[0]+distance*Math.cos(pitch)*Math.sin(yaw),target[1]+distance*Math.sin(pitch),target[2]+distance*Math.cos(pitch)*Math.cos(yaw)],z=norm(sub(eye,target)),x=norm(cross([0,1,0],z)),y=cross(z,x);
   const view=new Float32Array([x[0],y[0],z[0],0,x[1],y[1],z[1],0,x[2],y[2],z[2],0,-dot(x,eye),-dot(y,eye),-dot(z,eye),1]),f=1/Math.tan(Math.PI/8),near=Math.max(.05,distance/1000),far=distance+extent*5;
   const projection=new Float32Array([f/(canvas.width/canvas.height),0,0,0,0,f,0,0,0,0,(far+near)/(near-far),-1,0,0,2*far*near/(near-far),0]);
   const scale=R2CScale.forView(distance,canvas.clientHeight,f,canvas.clientWidth,new URLSearchParams(location.search).get("units") || "imperial"),legend=document.getElementById('scale');
   legend.hidden=!scale;if(scale){document.getElementById('scale-bar').style.width=scale.pixels+'px';document.getElementById('scale-value').textContent=scale.label;legend.dataset.meters=scale.meters;}
   gl.uniformMatrix4fv(uniform,false,multiply(projection,view));gl.enable(gl.DEPTH_TEST);gl.disable(gl.CULL_FACE);gl.depthMask(true);gl.disable(gl.BLEND);gl.clearColor(.09,.15,.19,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
   gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,soft?opaqueBuffer:fullBuffer);gl.drawElements(gl.TRIANGLES,soft?clipped.opaque.length:a.count,gl.UNSIGNED_INT,0);
   if(soft&&clipped.edge.length){gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);gl.depthMask(false);gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,edgeBuffer);gl.drawElements(gl.TRIANGLES,clipped.edge.length,gl.UNSIGNED_INT,0);gl.depthMask(true);gl.disable(gl.BLEND);}
  }
  let pointers=new Map(),lastPair=null;
  canvas.addEventListener('pointerdown',e=>{canvas.setPointerCapture(e.pointerId);pointers.set(e.pointerId,[e.clientX,e.clientY]);lastPair=null;});
  canvas.addEventListener('pointermove',e=>{const previous=pointers.get(e.pointerId);if(!previous)return;pointers.set(e.pointerId,[e.clientX,e.clientY]);
   if(pointers.size===1){yaw-=(e.clientX-previous[0])*.007;pitch=Math.max(.08,Math.min(1.5,pitch+(e.clientY-previous[1])*.007));}
   else{const p=[...pointers.values()].slice(0,2),pair={x:(p[0][0]+p[1][0])/2,y:(p[0][1]+p[1][1])/2,d:Math.hypot(p[0][0]-p[1][0],p[0][1]-p[1][1])};if(lastPair){distance=Math.max(extent*.05,Math.min(extent*8,distance*lastPair.d/Math.max(1,pair.d)));target[0]-=(pair.x-lastPair.x)*distance/canvas.clientWidth;target[2]+=(pair.y-lastPair.y)*distance/canvas.clientHeight;}lastPair=pair;}draw();});
  for(const event of ['pointerup','pointercancel'])canvas.addEventListener(event,e=>{pointers.delete(e.pointerId);lastPair=null;});
  canvas.addEventListener('wheel',e=>{e.preventDefault();distance=Math.max(extent*.05,Math.min(extent*8,distance*Math.exp(e.deltaY*.001)));draw();},{passive:false});
  function reset(){yaw=.6;pitch=.9;distance=extent*1.35;target=center.slice();draw();}
  const extentButton=document.getElementById('extent');
  function label(){extentButton.textContent=soft?'Show full extent':'Use target region';status.textContent=(model.extras?.reconstructionMode==='approximate-plane'?(model.extras?.regionCount?'Includes approximate reference planes · ':'Approximate reference plane · NO measured depth · '):'')+(soft?(clipped.opaque.length+clipped.edge.length)/3:a.count/3).toLocaleString()+' triangles · '+(model.extras?.regionCount?model.extras.regionCount+' regions together · ':'')+(soft?'target region · 5 m outer fade':'full captured extent');}
  if(clipped){extentButton.hidden=false;extentButton.onclick=()=>{soft=!soft;fitBounds();colors();label();reset();};}
  document.getElementById('reset').onclick=reset;window.addEventListener('resize',draw);
  label();draw();
 }catch(error){status.textContent=error.message;}
})();
