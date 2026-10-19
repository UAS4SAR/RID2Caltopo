const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
(async()=>{
const root=path.resolve(process.argv[2]||'.');
const assets=path.join(__dirname,'comparison-viewer');
const server=http.createServer((req,res)=>{const name=req.url.split('?')[0].slice(1)||'compare.html';if(!/^[a-zA-Z0-9.-]+$/.test(name)){res.writeHead(404);return res.end();}const file=path.join(name==='compare.html'||name==='compare.js'?assets:root,name);if(!fs.existsSync(file)){res.writeHead(404);return res.end();}res.setHeader('Content-Type',name.endsWith('.html')?'text/html':name.endsWith('.js')?'application/javascript':name.endsWith('.json')?'application/json':'application/octet-stream');fs.createReadStream(file).pipe(res)});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try{browser=await chromium.launch({headless:true,executablePath:process.env.R2C_COMPARISON_BROWSER||(process.platform==='darwin'?'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome':undefined),args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});const page=await browser.newPage({viewport:{width:1800,height:920},deviceScaleFactor:1});page.on('pageerror',e=>console.error(e));await page.goto('http://127.0.0.1:'+server.address().port+'/compare.html');await page.evaluate(()=>ready);
for(const [name,state] of [
 ['matched-geometry',{gray:true,yaw:.6,pitch:.8,span:125,description:'Uniform material · same virtual light and camera · full target'}],
 ['matched-color',{gray:false,yaw:.6,pitch:.8,span:125,description:'Original vertex colors · same camera and virtual light · capture shadows remain'}],
 ['matched-top-color',{gray:false,yaw:0,pitch:1.5706,span:115,target:[0,3,0],description:'North up · same orthographic scale · full target'}],
 ['matched-roof-geometry',{gray:true,yaw:.6,pitch:.8,span:50,target:[3,6,-22],description:'Roof and nearby canopy · same camera and scale · uniform material'}],
 ['matched-roof-color',{gray:false,yaw:0,pitch:1.5706,span:50,target:[3,6,-22],description:'Roof and nearby canopy · north up · original colors'}],
 ['matched-south-geometry',{gray:true,yaw:.6,pitch:.8,span:55,target:[-17,2,22],description:'Southern sheds and canopy · same camera and scale · uniform material'}]
]){await page.evaluate(state=>{render(state);window.scrollTo(0,0)},state);await page.screenshot({path:root+'/'+name+'.png'});console.log(name)}
}finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve))}
})().catch(e=>{console.error(e);process.exit(1)});
