'use strict';
// Display-only masks. Original GLB colors, geometry and saved artifacts stay intact.
globalThis.R2CBoundary={
 alpha(polygon,x,y,feather=5){
  let inside=false,distance=Infinity;
  for(let i=0,j=polygon.length-1;i<polygon.length;j=i++){
   const a=polygon[i],b=polygon[j];
   if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])inside=!inside;
   const dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy,t=length?Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/length)):0;
   distance=Math.min(distance,Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy));
  }
  if(inside)return 1;const t=Math.min(1,distance/feather);return 1-t*t*(3-2*t);
 },
 prepare(positions,sourceColors,sourceIndices,polygon,feather=5){
  const colors=sourceColors.slice(),opaque=[],edge=[],min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
  for(let i=0;i<colors.length/4;i++){
   colors[i*4+3]=Math.round(sourceColors[i*4+3]*this.alpha(polygon,positions[i*3],-positions[i*3+2],feather));
   if(colors[i*4+3])for(let axis=0;axis<3;axis++){min[axis]=Math.min(min[axis],positions[i*3+axis]);max[axis]=Math.max(max[axis],positions[i*3+axis]);}
  }
  for(let i=0;i<sourceIndices.length;i+=3){const a=sourceIndices[i],b=sourceIndices[i+1],c=sourceIndices[i+2],alpha=[colors[a*4+3],colors[b*4+3],colors[c*4+3]];
   if(alpha.every(v=>v===0))continue;(alpha.every(v=>v===255)?opaque:edge).push(a,b,c);
  }
  return {colors,opaque:new Uint32Array(opaque),edge:new Uint32Array(edge),min,max};
 }
};
