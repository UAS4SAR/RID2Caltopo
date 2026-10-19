'use strict';
const element=id=>document.getElementById(id),chunkBytes=1024*1024;
const fragment=location.hash.slice(1);let token=fragment||sessionStorage.getItem('r2c-transfer-token');
if(fragment){sessionStorage.setItem('r2c-transfer-token',fragment);history.replaceState(null,'',location.pathname);}
let abort=null,paused=false,running=false;
const bytes=value=>(value/(1024*1024)).toFixed(1)+' MiB';
function failure(error){element('error').textContent=error.message||'Transfer failed. Select the same photos to resume.';}
async function api(path,options={}){
  const response=await fetch(path,{...options,cache:'no-store',headers:{...options.headers,Authorization:'Bearer '+token},signal:options.signal});
  if(!response.ok){let message='Transfer connection failed.';try{message=(await response.json()).error||message;}catch(_){}throw Error(message);}
  return response;
}
async function json(path,body,options={}){
  return(await api(path,{...options,method:'POST',headers:{'Content-Type':'application/json',...options.headers},body:JSON.stringify(body)})).json();
}
function showInventory(value){element('inventory').textContent=value.photoCount+' originals verified on the R2C tablet · '+bytes(value.storedBytes)+(value.pendingCount?' · '+value.pendingCount+' incomplete':'');}
async function refresh(){const value=await(await api('/api/status')).json();showInventory(value);return value;}
async function download(path,name){element('error').textContent='';try{
  const blob=await(await api(path)).blob(),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=name;
  document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);
}catch(error){failure(error);}}
element('boundary').onclick=()=>download('/download/mission.kmz','R2C-mission.kmz');
element('package').onclick=()=>download('/download/mission.zip','R2C-mission.zip');
element('cancel').onclick=()=>{paused=true;if(abort)abort.abort();};
element('photos').onchange=async()=>{
  if(running)return;const files=Array.from(element('photos').files);if(!files.length)return;
  element('error').textContent='';
  if(files.some(file=>!(/\.jpe?g$/i.test(file.name))||file.size<3||file.size>128*1024*1024)){failure(Error('Choose original JPEGs, each no larger than 128 MiB.'));element('photos').value='';return;}
  running=true;paused=false;abort=new AbortController();element('photos').disabled=true;element('cancel').hidden=false;element('progress').hidden=false;
  let finishedBytes=0;const totalBytes=files.reduce((sum,file)=>sum+file.size,0),digests=[];
  try{for(let index=0;index<files.length;index++){
    const file=files[index],hash=new R2CSha256();
    element('transfer').textContent='Checking '+(index+1)+'/'+files.length+' · '+file.name;
    for(let offset=0;offset<file.size;offset+=chunkBytes){if(paused)throw Error('Upload paused. Select the same photos to resume.');
      hash.update(new Uint8Array(await file.slice(offset,offset+chunkBytes).arrayBuffer()));}
    const digest=hash.hex();digests.push(digest);
    let state=await json('/api/photos',{name:file.name,sizeBytes:file.size,sha256:digest},{signal:abort.signal});
    if(!state.complete){
      while(state.offset<file.size){if(paused)throw Error('Upload paused. Select the same photos to resume.');
        element('transfer').textContent='Sending '+(index+1)+'/'+files.length+' · '+file.name+' · '+bytes(state.offset)+' / '+bytes(file.size);
        const end=Math.min(state.offset+chunkBytes,file.size);
        try{state=await(await api('/api/photos/'+digest,{method:'PUT',headers:{'Content-Type':'application/octet-stream','X-Upload-Offset':String(state.offset)},body:file.slice(state.offset,end),signal:abort.signal})).json();}
        catch(error){if(paused)throw error;throw Error(error.message+' Select the same files to resume.');}
        element('progress').value=100*(finishedBytes+state.offset)/totalBytes;
      }
      await json('/api/photos/'+digest+'/commit',{}, {signal:abort.signal});
    }
    finishedBytes+=file.size;element('progress').value=100*finishedBytes/totalBytes;await refresh();
  }
  showInventory(await json('/api/complete',{sha256:digests},{signal:abort.signal}));
  element('transfer').textContent='All '+files.length+' selected photos verified. Return to R2C on the tablet.';
  }catch(error){if(paused){element('transfer').textContent='Upload paused. Stored originals are retained. Select the same files to resume.';}else failure(error);}
  finally{running=false;abort=null;element('photos').disabled=false;element('photos').value='';element('cancel').hidden=true;}
};
async function connect(){try{
  if(!token||!(/^[a-f0-9]{64}$/.test(token))){element('pairing').hidden=false;element('connection').textContent='Enter the access code shown on the R2C tablet';return;}
  const value=await refresh(),mission=value.mission,p=mission.parameters;
  element('title').textContent=mission.title;element('incident').textContent=mission.mapName||'Connected incident map';
  element('sensor').textContent=mission.aircraftModel+' · '+mission.cameraName;
  element('settings').replaceChildren();
  [Math.round(p.heightMeters)+' m above takeoff',p.desiredGroundResolutionCmPerPixel+' cm/pixel target',
    p.speedMetersPerSecond+' m/s maximum',...(mission.captureProfile?.passes||[{gimbalPitchDegrees:-90,directionDegrees:p.directionDegrees??0,forwardOverlapPercent:p.forwardOverlapPercent,sideOverlapPercent:p.sideOverlapPercent}]).map(pass=>pass.gimbalPitchDegrees+'° gimbal · '+pass.directionDegrees+'° grid · '+pass.forwardOverlapPercent+'% forward / '+pass.sideOverlapPercent+'% side overlap')].forEach(text=>{const li=document.createElement('li');li.textContent=text;element('settings').appendChild(li);});
  element('mode').textContent=mission.profile==='thermal'?'Select thermal originals only for this mission.':'Select visible originals only for this mission.';
  element('expires').textContent='Session expires at '+new Date(value.expiresAt).toLocaleTimeString();
  element('connection').textContent='Connected directly to the R2C tablet';element('workspace').hidden=false;element('pairing').hidden=true;
}catch(error){failure(error);element('connection').textContent='Not connected';element('pairing').hidden=false;}}
element('pair').onclick=async()=>{element('error').textContent='';element('pair').disabled=true;try{
  const code=element('code').value.trim();if(!(/^\d{6}$/.test(code)))throw Error('Enter the six-digit code shown on the R2C tablet.');
  const response=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code})});
  const value=await response.json();if(!response.ok)throw Error(value.error);
  token=value.token;sessionStorage.setItem('r2c-transfer-token',token);await connect();
}catch(error){failure(error);}finally{element('pair').disabled=false;}};
connect();
