'use strict';
const el=id=>document.getElementById(id);
let token=sessionStorage.getItem('r2c-pilot-token')||'',config=null,mqttReady=false,wsReady=false,loaded=false,poll=null;
let attempt=0,startupTimer=null;
function stopStartup(){if(startupTimer!==null)clearInterval(startupTimer);startupTimer=null;}
const callbackPrefix='r2c_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,10);
function stage(text){el('status').textContent=text;}
function bridge(method,...args){
  const dji=window.djiBridge;if(!dji||typeof dji[method]!=='function')throw Error('Pilot 2 does not expose the required '+method+' interface. Open this page through Cloud Services → Open Platforms.');
  const raw=dji[method](...args);let value;
  try{value=typeof raw==='string'?JSON.parse(raw):raw;}catch(_){throw Error('Pilot 2 returned an unreadable response for '+method+'.');}
  if(!value||value.code!==0)throw Error('Pilot 2 rejected '+method+' (code '+(value&&value.code!==undefined?value.code:'unknown')+').');
  // Getters and license verification legitimately return false with a successful bridge code.
  if(value.data===false&&!/(Get|Is|VerifyLicense)/.test(method))throw Error('Pilot 2 did not accept '+method+'.');
  return value.data;
}
async function api(path,body){
  const response=await fetch(path,{method:body===undefined?'GET':'POST',cache:'no-store',headers:{'X-Auth-Token':token,'Content-Type':'application/json','X-R2C-Page-Revision':'2'},body:body===undefined?undefined:JSON.stringify(body)});
  const value=await response.json();if(!response.ok||value.code!==0)throw Error(value.message||'The R2C tablet could not complete this step.');return value.data;
}
function connected(value){if(value===true||value===1)return true;if(typeof value==='string'){try{return connected(JSON.parse(value));}catch(_){return false;}}if(value&&typeof value==='object'){if(value.code===0&&value.data!==undefined)return connected(value.data);return value.connected===true;}return false;}
function load(name,param){bridge('platformLoadComponent',name,JSON.stringify(param));}
async function report(value){await api('/cloud/report',value);}
// Pilot builds can serialize getter data as numbers/booleans or their string
// equivalents. Accept only the documented values: Number(null), Boolean('false')
// and other truthy/coercible responses must never mark photo return ready.
function mediaSettingMatches(value,wanted){
  if(typeof wanted==='number')return value===wanted||value===String(wanted);
  return value===wanted||value===(wanted?1:0)||value===(wanted?'1':'0')||value===String(wanted);
}
function mediaSettingDescription(value){
  if(value===null)return 'null';
  if(value===undefined)return 'missing';
  if(typeof value==='number')return Number.isFinite(value)?'number '+value:'invalid number';
  if(typeof value==='boolean')return 'boolean '+value;
  if(typeof value==='string')return ['0','1','true','false'].includes(value)?'string '+JSON.stringify(value):'unrecognized string';
  return Array.isArray(value)?'array':'object';
}
// The same asset ships on Android and iOS. Reassert return policy if Pilot settings drift.
async function ensureOriginalReturn(currentAttempt){
  const settings=[
    ['mediaGetDownloadOwner','mediaSetDownloadOwner',0,'download owner'],
    ['mediaGetUploadPhotoType','mediaSetUploadPhotoType',0,'original-photo type'],
    ['mediaGetAutoUploadPhoto','mediaSetAutoUploadPhoto',true,'automatic photo upload'],
    ['mediaGetAutoUploadVideo','mediaSetAutoUploadVideo',false,'video upload disabled']
  ];
  // Pilot acknowledges setters before its asynchronous native state is observable.
  // Set each mismatch once, then allow bounded propagation without resetting it repeatedly.
  function readPending(){return settings.map(setting=>({setting,value:bridge(setting[0])})).filter(({setting,value})=>!mediaSettingMatches(value,setting[2]));}
  let pending=readPending();
  for(const {setting:[,set,wanted]} of pending)bridge(set,wanted);
  for(let retry=0;pending.length&&retry<20;retry++){
    await new Promise(resolve=>setTimeout(resolve,250));
    if(currentAttempt!==attempt)throw Error('Connection was replaced.');
    pending=readPending();
  }
  if(pending.length){
    let guidance='Reconnect before starting another mission.';
    if(pending.some(({setting})=>setting[0]==='mediaGetDownloadOwner')){
      // An unlinked controller can report owner=1 too. Ask Pilot for aircraft
      // presence before suggesting a competing download controller. Never expose SNs.
      let aircraft;
      try{aircraft=bridge('platformGetAircraftSN');}catch(_){}
      if(typeof aircraft==='string'&&!aircraft.trim()){
        throw Error('Pilot 2 has not reported a connected aircraft. Power on and link the aircraft to this controller, then reconnect to the R2C tablet. Photo-download ownership cannot be confirmed yet.');
      }
      guidance=typeof aircraft==='string'&&aircraft.trim()
        ?'Check that this is the photo-download controller, then reconnect before starting another mission.'
        :'Check that the aircraft is powered on and linked to this controller. Pilot 2 could not report whether an aircraft is connected. Then check the photo-download controller and reconnect before starting another mission.';
    }
    throw Error('Pilot 2 has not confirmed '+pending.map(({setting,value})=>setting[3]+' (reported '+mediaSettingDescription(value)+')').join(', ')+'. '+guidance);
  }
}

async function finish(){
  if(!mqttReady||!wsReady||loaded||!config)return;loaded=true;
  const currentAttempt=attempt;
  try{
    stage('Enabling original photo return…');
    load('media',{autoUploadPhoto:true,autoUploadPhotoType:0,autoUploadVideo:false});
    bridge('mediaSetDownloadOwner',0);bridge('mediaSetUploadPhotoType',0);bridge('mediaSetAutoUploadPhoto',true);bridge('mediaSetAutoUploadVideo',false);
    await ensureOriginalReturn(currentAttempt);
    if(currentAttempt!==attempt)return;
    load('mission',{});
    await report({mediaLoaded:true,missionLoaded:true,automaticOriginals:true,downloadOwner:true});
    if(currentAttempt!==attempt)return;
    stopStartup();el('connect').disabled=false;stage('Connected · mission library and original photo return enabled');el('mission').hidden=false;el('login').hidden=true;
    let checkingReturn=false;
    poll=setInterval(async()=>{if(currentAttempt!==attempt||checkingReturn)return;checkingReturn=true;try{await ensureOriginalReturn(currentAttempt);const value=await api('/cloud/status');if(currentAttempt!==attempt)return;el('photos').textContent=value.storedUploadCount+' returned files stored on R2C · '+value.photoCount+' mission originals verified'+(value.unmatchedCount?' · '+value.unmatchedCount+' files retained separately (original flag, mission tag or camera mismatch)':'');}catch(error){if(currentAttempt!==attempt)return;clearInterval(poll);el('mission').hidden=true;el('login').hidden=false;el('error').textContent=error.message;stage('Photo return needs attention. Reconnect before starting another mission.');try{await report({automaticOriginals:false,downloadOwner:false});}catch(_){}}finally{checkingReturn=false;}},3000);
  }catch(error){if(currentAttempt!==attempt)return;stopStartup();loaded=false;el('connect').disabled=false;el('error').textContent=error.message;stage('Connection needs attention');}
}
// Old native modules can outlive a page reload. Their callbacks must not change a new session.
window.r2cMqttConnected=()=>{};window.r2cWsConnected=()=>{};
const callbackGenerations={thing:0,ws:0};
function connectionCallback(channel){
  const currentAttempt=attempt,generation=++callbackGenerations[channel];
  const name=callbackPrefix+'_'+channel+'_'+currentAttempt+'_'+generation;
  window[name]=value=>{
    if(currentAttempt!==attempt||generation!==callbackGenerations[channel])return;
    if(channel==='thing'){mqttReady=connected(value);if(!mqttReady)stage('Waiting for local device connection…');}
    else{wsReady=connected(value);if(!wsReady)stage('Waiting for mission-library connection…');}
    finish();
  };
  return name;
}
function connectionCallbacks(){return {mqtt:connectionCallback('thing'),ws:connectionCallback('ws')};}
async function resetNativeModules(names=['mission','media','ws','thing','api']){
  // Dependents first: reloading api alone does not refresh retained native modules.
  for(const name of names){
    if(connected(bridge('platformIsComponentLoaded',name))){
      bridge('platformUnloadComponent',name);
      // Native teardown may finish after the bridge call returns.
      let remaining=40;
      while(connected(bridge('platformIsComponentLoaded',name))){
        if(--remaining===0)throw Error('Pilot 2 could not close its previous connection. Close Open Platforms and reopen it.');
        await new Promise(resolve=>setTimeout(resolve,50));
      }
    }
  }
}
function watchStartup(callbacks){
  const currentAttempt=attempt;let ticks=0,recovering=false;
  stopStartup();
  startupTimer=setInterval(async()=>{
    if(currentAttempt!==attempt||recovering)return;
    try{
      // Poll as well as listening: Pilot may establish a connection without delivering its callback.
      mqttReady=connected(bridge('thingGetConnectState'));wsReady=connected(bridge('wsGetConnectState'));
      if(mqttReady&&wsReady){finish();return;}
      if(++ticks>=30)throw Error('Pilot 2 could not connect to the R2C tablet. Check that both devices are on the same Wi-Fi and the mission connection is still open.');
      // Pilot can recreate its gateway after load returns, losing the initial connection.
      // A repeated thingConnect is accepted but does not restart that replacement gateway.
      if(ticks===12){
        recovering=true;stage('Refreshing the stalled local connection…');
        if(!mqttReady){
          callbacks.mqtt=connectionCallback('thing'); // Retire callbacks before native teardown.
          await resetNativeModules(['thing']);
          if(currentAttempt!==attempt)return;
          load('thing',{...config.thing,connectCallback:callbacks.mqtt});
        }
        if(!wsReady){
          callbacks.ws=connectionCallback('ws');
          await resetNativeModules(['ws']);
          if(currentAttempt!==attempt)return;
          load('ws',{...config.ws,connectCallback:callbacks.ws});
        }
        recovering=false;
      }
    }catch(error){
      if(currentAttempt!==attempt)return;
      stopStartup();attempt++;config=null;loaded=false;el('connect').disabled=false;
      el('error').textContent=error.message;stage('Connection needs attention');
    }
  },1000);
}
async function configure(){
  stage('Verifying DJI developer license…');config=await api('/cloud/bootstrap');
  await report({automaticOriginals:false,downloadOwner:false,mediaLoaded:false,missionLoaded:false});
  let verified;
  try{
    const result=bridge('platformVerifyLicense',config.license.appId,config.license.appKey,config.license.appLicense);
    verified=result!==false&&bridge('platformIsVerified')===true;
  }finally{config.license=null;}
  if(!verified)throw Error('DJI could not verify this Cloud API license. The provisioned app ID, key and basic license need administrator correction.');
  await report({licenseVerified:true});
  stage('Refreshing the controller connection…');await resetNativeModules();
  load('api',config.api);bridge('platformSetWorkspaceId',config.workspaceId);bridge('platformSetInformation','RID2Caltopo',config.workspaceName,config.assignmentName);
  bridge('apiSetToken',token);
  if(bridge('apiGetToken')!==token)throw Error('Pilot 2 retained the previous connection. LogOut of Open Platforms, then reconnect using the current R2C access code.');
  await report({apiLoaded:true});
  el('title').textContent=config.assignmentName;
  el('flightHeight').textContent='Flight height: '+Math.round(config.route.heightMeters/0.3048)+' ft / '+Math.round(config.route.heightMeters)+' m above takeoff';
  el('returnHomeHeight').textContent='Return-home height: '+Math.round(config.route.returnHomeHeightMeters/0.3048)+' ft / '+Math.round(config.route.returnHomeHeightMeters)+' m above takeoff';
  el('resolution').textContent='Target ground detail: '+config.route.groundResolutionCmPerPixel+' cm/px (level-ground estimate)';
  el('estimate').textContent='Approximately '+config.route.estimatedPhotos+' photos · '+Math.ceil(config.route.estimatedFlightSeconds/60)+' minutes of flight';
  stage('Connecting to local mission and device services…');
  const callbacks=connectionCallbacks();
  load('ws',{...config.ws,connectCallback:callbacks.ws});load('thing',{...config.thing,connectCallback:callbacks.mqtt});
  watchStartup(callbacks);
}
el('connect').onclick=async()=>{
  if(el('connect').disabled)return;
  stopStartup();attempt++;el('error').textContent='';el('connect').disabled=true;mqttReady=false;wsReady=false;loaded=false;if(poll)clearInterval(poll);
  try{
    if(!window.djiBridge)throw Error('Open this address inside Pilot 2 → Cloud Services → Open Platforms.');
    const code=el('code').value.trim();if(!(/^\d{6}$/.test(code)))throw Error('Enter the six-digit code on the R2C tablet.');
    const result=await api('/cloud/login',{code});token=result.token;sessionStorage.setItem('r2c-pilot-token',token);
    await configure();
  }catch(error){config=null;el('error').textContent=error.message;stage('Connection needs attention');}
  finally{if(!config)el('connect').disabled=false;}
};
if(!window.djiBridge){el('error').textContent='This page must be opened in Pilot 2 Open Platforms to enable native mission and photo services.';el('connect').disabled=true;}
