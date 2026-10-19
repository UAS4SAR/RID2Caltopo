'use strict';
const bridge=window.djiBridge;
const capability={bridgeAvailable:!!bridge,licenseVerification:typeof bridge?.platformVerifyLicense==='function',
  workspaceSelection:typeof bridge?.platformSetWorkspaceId==='function',platformInformation:typeof bridge?.platformSetInformation==='function'};
document.getElementById('result').textContent=capability.bridgeAvailable?'Pilot 2 native DJI bridge detected.':'Native DJI bridge not detected. Open this page through Pilot 2 Open Platforms, rather than a separate browser.';
document.getElementById('send').onclick=async()=>{
  const error=document.getElementById('error'),button=document.getElementById('send');error.textContent='';button.disabled=true;
  try{
    const code=document.getElementById('code').value.trim();if(!(/^\d{6}$/.test(code)))throw Error('Enter the six-digit code shown by R2C.');
    const response=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code})});
    const session=await response.json();if(!response.ok)throw Error(session.error);
    const report=await fetch('/api/pilot2-capabilities',{method:'POST',headers:{Authorization:'Bearer '+session.token,'Content-Type':'application/json'},body:JSON.stringify(capability)});
    if(!report.ok)throw Error((await report.json()).error);
    document.getElementById('status').textContent='Result saved on the R2C tablet.';
  }catch(value){error.textContent=value.message;}finally{button.disabled=false;}
};
