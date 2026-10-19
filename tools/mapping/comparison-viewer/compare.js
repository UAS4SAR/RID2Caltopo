'use strict';
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0),norm=a=>{const l=Math.hypot(...a);return a.map(x=>x/l)},sub=(a,b)=>a.map((x,i)=>x-b[i]);
function multiply(a,b){const o=new Float32Array(16);for(let c=0;c<4;c++)for(let r=0;r<4;r++)for(let k=0;k<4;k++)o[c*4+r]+=a[k*4+r]*b[c*4+k];return o;}
window.ready=(async()=>{
 const meta=await(await fetch('renderer.json')).json(),renders=[];
 if(meta.heading)document.getElementById('heading').textContent=meta.heading;
 for(const result of meta.results){const title=document.getElementById(result.label+'-title');if(title)title.textContent=(result.title||result.label)+' · '+result.photoCount+' supported photos';}
 for(const label of ['nadir','mixed']){
  const canvas=document.getElementById(label),gl=canvas.getContext('webgl2',{antialias:true,preserveDrawingBuffer:true});if(!gl)throw Error('No WebGL');
  const program=gl.createProgram();function shader(type,source){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));gl.attachShader(program,s);}
  shader(gl.VERTEX_SHADER,'attribute vec3 position;attribute vec3 normal;attribute vec4 color;uniform mat4 mvp;uniform bool gray;varying vec4 shade;varying vec3 world;void main(){float light=.45+.55*max(0.,dot(normalize(normal),normalize(vec3(-.4,1.,.3))));shade=vec4((gray?vec3(.72,.77,.80):color.rgb)*light,1.);world=position;gl_Position=mvp*vec4(position,1.);}');
  shader(gl.FRAGMENT_SHADER,'precision highp float;varying vec4 shade;varying vec3 world;uniform vec2 polygon[17];uniform int count;void main(){vec2 p=vec2(world.x,-world.z);bool inside=false;for(int i=0;i<16;i++){if(i>=count)break;vec2 a=polygon[i],b=polygon[i+1];if(i==count-1)b=polygon[0];if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)inside=!inside;}if(!inside)discard;gl_FragColor=shade;}');
  gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program));gl.useProgram(program);
  for(const [name,file,n,type] of [['position','positions',3,gl.FLOAT],['normal','normals',3,gl.FLOAT],['color','colors',4,gl.UNSIGNED_BYTE]]){
   const bytes=await(await fetch(label+'-'+file+'.bin')).arrayBuffer(),buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,bytes,gl.STATIC_DRAW);const loc=gl.getAttribLocation(program,name);gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,n,type,name==='color',0,0);
  }
  const index=await(await fetch(label+'-indices.bin')).arrayBuffer(),buffer=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,buffer);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,index,gl.STATIC_DRAW);
  gl.uniform2fv(gl.getUniformLocation(program,'polygon'),new Float32Array(meta.boundary.flat()));gl.uniform1i(gl.getUniformLocation(program,'count'),meta.boundary.length);
  const mvp=gl.getUniformLocation(program,'mvp'),gray=gl.getUniformLocation(program,'gray');
  renders.push(state=>{canvas.width=canvas.clientWidth;canvas.height=canvas.clientHeight;gl.viewport(0,0,canvas.width,canvas.height);gl.useProgram(program);
   const target=state.target||[0,3,0],distance=230,pitch=state.pitch??.8,yaw=state.yaw??.6;
   const eye=[target[0]+distance*Math.cos(pitch)*Math.sin(yaw),target[1]+distance*Math.sin(pitch),target[2]+distance*Math.cos(pitch)*Math.cos(yaw)],z=norm(sub(eye,target)),x=norm(cross([0,1,0],z)),y=cross(z,x);
   const view=new Float32Array([x[0],y[0],z[0],0,x[1],y[1],z[1],0,x[2],y[2],z[2],0,-dot(x,eye),-dot(y,eye),-dot(z,eye),1]);
   const h=state.span||125,w=h*canvas.width/canvas.height,near=.1,far=700;
   const ortho=new Float32Array([2/w,0,0,0,0,2/h,0,0,0,0,-2/(far-near),0,0,0,-(far+near)/(far-near),1]);
   gl.uniformMatrix4fv(mvp,false,multiply(ortho,view));gl.uniform1i(gray,state.gray?1:0);gl.enable(gl.DEPTH_TEST);gl.disable(gl.CULL_FACE);gl.clearColor(.09,.15,.19,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.drawElements(gl.TRIANGLES,index.byteLength/4,gl.UNSIGNED_INT,0);if(gl.getError())throw Error('WebGL render error');
  });
 }
 window.render=state=>{document.getElementById('description').textContent=state.description;renders.forEach(f=>f(state));};
 render({gray:true,description:'Uniform material isolates geometry from capture shadows; identical north/east registration and vertical datum.'});return true;
})();
