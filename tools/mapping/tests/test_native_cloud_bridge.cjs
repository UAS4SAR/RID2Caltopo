const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../../app/src/main/assets/dji-cloud/native.js'),'utf8');
function setup({rejectLicense=false,licenseFalse=false,verifiedState=true,cached=false,stickyToken=false,delayedUnload=false,connectOnRetry=false,connectAfter=1,refuseReturn=false,delayedReturn=false}={}){
  const elements=new Map(),calls=[],reports=[],saved=[],components=new Map(),timers=new Map(),states={thing:false,ws:false};let timerId=0;
  const mediaSettings={mediaGetDownloadOwner:0,mediaGetUploadPhotoType:0,mediaGetAutoUploadPhoto:true,mediaGetAutoUploadVideo:false};
  if(delayedReturn){mediaSettings.mediaGetDownloadOwner=1;mediaSettings.mediaGetAutoUploadPhoto=false;}
  let apiToken=cached||stickyToken?'previous-api-token':'';
  if(cached)for(const name of ['api','thing','ws','media','mission'])components.set(name,{host:'previous-host',token:'previous-api-token',username:'previous-user',connectCallback:'previousCallback'});
  const el=id=>{if(!elements.has(id))elements.set(id,{textContent:'',value:'123456',disabled:false,hidden:true});return elements.get(id);};
  const config={license:{appId:'123456',appKey:'synthetic-key',appLicense:'synthetic-license'},workspaceId:'workspace',workspaceName:'Incident',assignmentName:'Area',
    api:{host:'https://tablet.test',token:'test-token'},ws:{host:'wss://tablet.test/cloud/ws',token:'test-token',connectCallback:'r2cWsConnected'},
    thing:{host:'wss://tablet.test/cloud/mqtt',username:'test',password:'session-only',connectCallback:'r2cMqttConnected'},route:{estimatedPhotos:30,estimatedFlightSeconds:240,heightMeters:60,returnHomeHeightMeters:60,groundResolutionCmPerPixel:1}};
  const bridge=new Proxy({}, {get:(_,method)=>(...args)=>{
    calls.push({method,args});
    if(method==='platformVerifyLicense'&&rejectLicense)return JSON.stringify({code:42,data:false});
    let data={};
    if(method==='platformVerifyLicense')data=!licenseFalse;
    else if(method==='platformIsVerified')data=verifiedState;
    else if(method==='platformIsComponentLoaded')data=components.has(args[0]);
    else if(method==='platformUnloadComponent'){if(delayedUnload)setImmediate(()=>components.delete(args[0]));else components.delete(args[0]);}
    else if(method==='platformLoadComponent'){
      // Pilot 2 may retain a loaded component across page reloads; load alone need not replace it.
      if(!components.has(args[0])){
        const value=JSON.parse(args[1]);components.set(args[0],value);
        if(args[0]==='api'&&!stickyToken)apiToken=value.token;
        if(connectOnRetry&&['thing','ws'].includes(args[0])&&calls.filter(x=>x.method==='platformLoadComponent'&&x.args[0]===args[0]).length>=2)states[args[0]]=true;
      }
    }else if(method==='apiSetToken'){if(!stickyToken)apiToken=args[0];}
    else if(method==='apiGetToken')data=apiToken;
    else if(method==='thingGetConnectState')data=states.thing;
    else if(method==='wsGetConnectState')data=states.ws;
    else if(method==='thingConnect'&&connectOnRetry&&calls.filter(x=>x.method==='thingConnect').length>=connectAfter)states.thing=true;
    else if(method==='wsConnect'&&connectOnRetry)states.ws=true;
    else if(method in mediaSettings)data=mediaSettings[method];
    else if(method.startsWith('mediaSet')&&!refuseReturn){const getter=method.replace('Set','Get');if(getter in mediaSettings){if(delayedReturn)setImmediate(()=>{mediaSettings[getter]=args[0];});else mediaSettings[getter]=args[0];}}
    return JSON.stringify({code:0,data});
  }});
  const context=vm.createContext({window:{djiBridge:bridge},document:{getElementById:el},sessionStorage:{getItem:()=>null,setItem:(key,value)=>saved.push([key,value])},
    setTimeout:(fn)=>setImmediate(fn),setInterval:(fn,ms)=>{timers.set(++timerId,{fn,ms});return timerId;},clearInterval:id=>timers.delete(id),fetch:async(url,options)=>{
      let data={};if(url==='/cloud/login')data={token:'test-token'};else if(url==='/cloud/bootstrap')data=structuredClone(config);else if(url==='/cloud/report')reports.push(JSON.parse(options.body));
      return {ok:true,json:async()=>({code:0,data})};
    }});
  vm.runInContext(source,context);
  const callback=name=>context.window[components.get(name).connectCallback];
  return{context,calls,reports,saved,el,components,callback,states,mediaSettings,poll:async()=>{for(const {fn,ms} of [...timers.values()])if(ms===3000)await fn();},tick:async()=>{for(const {fn,ms} of [...timers.values()])if(ms===1000)fn();await new Promise(resolve=>setImmediate(resolve));}};
}
const drain=async()=>{for(let i=0;i<30;i++)await new Promise(resolve=>setImmediate(resolve));};
(async()=>{
  const a=setup();await a.el('connect').onclick();
  assert.equal(a.calls[0].method,'platformVerifyLicense');assert.equal(a.calls.filter(x=>x.method==='platformLoadComponent'&&x.args[0]==='media').length,0);
  assert.equal(a.calls.find(x=>x.method==='apiSetToken').args[0],'test-token');
  assert.ok(a.calls.some(x=>x.method==='apiGetToken'));
  a.callback('ws')(true);await drain();
  assert.equal(a.calls.filter(x=>x.method==='platformLoadComponent'&&x.args[0]==='mission').length,0);
  a.callback('thing')(JSON.stringify({code:0,data:true}));await drain();
  const media=a.calls.find(x=>x.method==='platformLoadComponent'&&x.args[0]==='media');
  assert.deepEqual(JSON.parse(media.args[1]),{autoUploadPhoto:true,autoUploadPhotoType:0,autoUploadVideo:false});
  assert.equal(a.calls.find(x=>x.method==='mediaSetDownloadOwner').args[0],0);
  assert.ok(a.calls.some(x=>x.method==='platformLoadComponent'&&x.args[0]==='mission'));
  assert.ok(a.reports.some(x=>x.automaticOriginals===true));
  assert.deepEqual(a.saved,[['r2c-pilot-token','test-token']]);assert.equal(a.el('mission').hidden,false);
  assert.equal(a.el('flightHeight').textContent,'Flight height: 197 ft / 60 m above takeoff');
  assert.equal(a.el('returnHomeHeight').textContent,'Return-home height: 197 ft / 60 m above takeoff');
  assert.equal(a.el('resolution').textContent,'Target ground detail: 1 cm/px (level-ground estimate)');
  assert.ok(a.calls.findIndex(x=>x.method==='platformSetWorkspaceId')<a.calls.indexOf(media));
  assert.ok(!a.calls.some(x=>/flight|takeoff|execute/i.test(x.method)));
  a.mediaSettings.mediaGetAutoUploadPhoto=false;
  a.mediaSettings.mediaGetDownloadOwner=1;
  a.mediaSettings.mediaGetAutoUploadVideo=true;
  await a.poll();
  assert.equal(a.mediaSettings.mediaGetAutoUploadPhoto,true);
  assert.equal(a.mediaSettings.mediaGetDownloadOwner,0);
  assert.equal(a.mediaSettings.mediaGetAutoUploadVideo,false);
  const refused=setup({refuseReturn:true});await refused.el('connect').onclick();
  refused.mediaSettings.mediaGetDownloadOwner=1;
  refused.callback('ws')(true);refused.callback('thing')(true);await drain();
  assert.ok(!refused.reports.some(x=>x.automaticOriginals===true));
  assert.match(refused.el('error').textContent,/has not confirmed/);
  const drift=setup({refuseReturn:true});await drift.el('connect').onclick();
  drift.callback('ws')(true);drift.callback('thing')(true);await drain();
  drift.mediaSettings.mediaGetAutoUploadPhoto=false;await drift.poll();
  assert.equal(drift.el('mission').hidden,true);
  assert.equal(drift.el('login').hidden,false);
  assert.ok(drift.reports.some(x=>x.automaticOriginals===false));
  assert.match(drift.el('status').textContent,/needs attention/);
  const delayedPolicy=setup({delayedReturn:true});await delayedPolicy.el('connect').onclick();
  delayedPolicy.callback('ws')(true);delayedPolicy.callback('thing')(true);await drain();
  assert.match(delayedPolicy.el('status').textContent,/^Connected/);
  assert.equal(delayedPolicy.saved.length,1,'Asynchronous media settings must finish on one Connect');
  assert.equal(delayedPolicy.el('error').textContent,'');
  const b=setup({rejectLicense:true});await b.el('connect').onclick();
  assert.equal(b.calls.length,1);assert.match(b.el('error').textContent,/code 42/);
  assert.ok(!b.el('error').textContent.includes('synthetic-key'));assert.ok(!b.reports.some(x=>x.licenseVerified));
  for(const c of [setup({licenseFalse:true}),setup({verifiedState:false})]){
    await c.el('connect').onclick();assert.match(c.el('error').textContent,/could not verify this Cloud API license/);
    assert.ok(!c.calls.some(x=>x.method==='platformLoadComponent'));assert.ok(!c.reports.some(x=>x.licenseVerified));
    assert.ok(!c.el('error').textContent.includes('synthetic-key'));assert.ok(!c.el('error').textContent.includes('code 0'));
  }
  const retained=setup({cached:true});await retained.el('connect').onclick();
  assert.deepEqual(retained.calls.filter(x=>x.method==='platformUnloadComponent').map(x=>x.args[0]),['mission','media','ws','thing','api']);
  assert.equal(retained.components.get('api').host,'https://tablet.test');
  assert.equal(retained.components.get('ws').token,'test-token');
  assert.equal(retained.components.get('thing').username,'test');
  assert.equal(retained.components.get('thing').password,'session-only');
  retained.callback('ws')(true);retained.callback('thing')(true);await drain();
  assert.match(retained.el('status').textContent,/^Connected/);

  const oldWs=retained.callback('ws'),oldMqtt=retained.callback('thing');
  const oldNames=['ws','thing'].map(name=>retained.components.get(name).connectCallback);
  await retained.el('connect').onclick();
  assert.ok(['ws','thing'].every((name,index)=>retained.components.get(name).connectCallback!==oldNames[index]));
  oldWs(true);oldMqtt(true);await drain();
  assert.ok(!retained.components.has('mission'),'Previous native callbacks must not mark a new session ready');
  retained.context.window.r2cWsConnected(true);retained.context.window.r2cMqttConnected(true);await drain();
  assert.ok(!retained.components.has('mission'),'Legacy callbacks must not mark a new session ready');
  retained.callback('ws')(true);retained.callback('thing')(true);await drain();
  assert.match(retained.el('status').textContent,/^Connected/);
  oldWs(false);oldMqtt(false);await drain();
  assert.match(retained.el('status').textContent,/^Connected/,'Late disconnect callbacks must not overwrite the current state');

  const stale=setup({stickyToken:true});await stale.el('connect').onclick();
  assert.match(stale.el('error').textContent,/retained the previous connection/);
  assert.ok(!stale.components.has('media'));assert.ok(!stale.components.has('thing'));
  for(const value of ['test-token','previous-api-token','synthetic-key','synthetic-license'])assert.ok(!stale.el('error').textContent.includes(value));
  const delayed=setup({cached:true,delayedUnload:true});await delayed.el('connect').onclick();
  assert.equal(delayed.components.get('thing').host,'wss://tablet.test/cloud/mqtt');
  const recovery=setup({connectOnRetry:true});await recovery.el('connect').onclick();
  recovery.states.ws=true;recovery.callback('ws')(true);
  assert.equal(recovery.el('connect').disabled,true);
  await recovery.el('connect').onclick(); // A second click cannot restart an in-progress handshake.
  assert.equal(recovery.saved.length,1);
  const retiredMqtt=recovery.callback('thing');
  for(let i=0;i<14;i++)await recovery.tick();
  assert.match(recovery.el('status').textContent,/^Connected/);
  assert.equal(recovery.calls.filter(x=>x.method==='platformLoadComponent'&&x.args[0]==='thing').length,2);
  retiredMqtt(false);await drain();assert.match(recovery.el('status').textContent,/^Connected/);
  assert.equal(recovery.calls.filter(x=>x.method==='platformLoadComponent'&&x.args[0]==='ws').length,1,'Keep the working library connection');
  assert.equal(recovery.saved.length,1,'Recovery does not repeat authentication');
  const missingCallback=setup();await missingCallback.el('connect').onclick();
  missingCallback.states.thing=true;missingCallback.states.ws=true;await missingCallback.tick();
  assert.match(missingCallback.el('status').textContent,/^Connected/);
  assert.ok(!missingCallback.calls.some(x=>x.method==='thingConnect'));
  const timeout=setup();await timeout.el('connect').onclick();const late=timeout.callback('thing');
  for(let i=0;i<31;i++)await timeout.tick();
  assert.match(timeout.el('error').textContent,/could not connect/);
  assert.equal(timeout.el('connect').disabled,false);late(true);await drain();
  assert.ok(!timeout.components.has('media'));
  assert.equal(timeout.calls.filter(x=>x.method==='platformLoadComponent'&&x.args[0]==='thing').length,2,'Bounded reload');
  console.log('Native H5: license gate, retained-module/token replacement, stale-callback isolation, MQTT/WS prerequisites, original-only return, route heights, no flight control, and credential redaction passed.');
})().catch(error=>{console.error(error);process.exitCode=1;});
