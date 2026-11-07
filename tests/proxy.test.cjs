'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const net=require('node:net');const {spawn}=require('node:child_process');
const root=path.resolve(__dirname,'..');
async function ready(base){for(let i=0;i<100;i++){try{if((await fetch(base+'/api/health')).ok)return;}catch{}await new Promise(r=>setTimeout(r,50));}throw new Error('Server unavailable: '+base);}
function processNode(env){const p=spawn(process.execPath,[path.join(root,'server.cjs')],{env:{...process.env,RYADOM_NO_OPEN:'1',...env},stdio:['ignore','pipe','pipe']});p.logs='';p.stderr.on('data',b=>p.logs+=b);return p;}
async function stop(p){if(p&&p.exitCode===null){const closed=new Promise(r=>p.once('exit',r));p.kill();await closed;}}
async function api(base,route,method='GET',body,cookie='',extra={}){const r=await fetch(base+route,{method,headers:{Cookie:cookie,'X-Ryadom':'1','Content-Type':'application/json',...extra},body:body===undefined?undefined:JSON.stringify(body)});return {r,body:await r.json()};}
test('Node and Android proxies preserve pairing, SSE, uploads and authenticated media',async t=>{
 const data=fs.mkdtempSync(path.join(os.tmpdir(),'ryadom-proxy-'));const port=21000+Math.floor(Math.random()*1000),base=`http://127.0.0.1:${port}`;const host=processNode({RYADOM_PORT:String(port),RYADOM_DATA:data});const children=[host];
 try{await ready(base);const key=fs.readFileSync(path.join(data,'host-key'),'utf8');const login=await api(base,'/api/host-login','POST',{key});const hostCookie=login.r.headers.get('set-cookie').split(';')[0];const state=(await api(base,'/api/state','GET',undefined,hostCookie)).body;
  const nodeProxy=processNode({RYADOM_ROLE:'client',RYADOM_PORT:String(port+1000),RYADOM_TARGET:base,RYADOM_TUNNEL_PORT:String(port)});children.push(nodeProxy);const routes=[{name:'Windows client proxy',base:`http://127.0.0.1:${port+1000}`}];
  if(process.env.RYADOM_TEST_JAVA){const p=spawn(process.env.RYADOM_TEST_JAVA,['-cp',process.env.RYADOM_TEST_CLASSPATH,'local.ryadom.chat.ProxyHarness',base],{stdio:['ignore','pipe','pipe']});children.push(p);const url=await new Promise((resolve,reject)=>{let text='';p.stdout.on('data',b=>{text+=b;if(text.includes('\n'))resolve(text.trim());});p.once('exit',()=>reject(new Error('Android proxy stopped')));});routes.push({name:'Android native proxy',base:url});}
  for(const proxy of routes)await t.test(proxy.name,async()=>{await ready(proxy.base);assert.equal((await api(proxy.base,'/api/state')).r.status,401);assert.equal((await api(proxy.base,'/api/host-login','POST',{key})).r.status,403);const pair=await api(proxy.base,'/api/pair','POST',{name:proxy.name,code:state.pairing.code});assert.equal(pair.r.status,200);const cookie=pair.r.headers.get('set-cookie').split(';')[0];assert.match(cookie,/^ryadom_sid_[a-f0-9]{16}=/);assert.equal((await api(proxy.base,'/api/state','GET',undefined,hostCookie)).r.status,401);const state2=await api(proxy.base,'/api/state','GET',undefined,cookie);assert.equal(state2.body.me.host,false);
   const abort=new AbortController();const eventResponse=await fetch(proxy.base+'/api/events',{headers:{Cookie:cookie},signal:abort.signal});const reader=eventResponse.body.getReader();await api(proxy.base,'/api/messages','POST',{text:'Через прямое соединение',requestId:crypto.randomUUID()},cookie);let events='';while(!events.includes('Через прямое соединение')){const item=await reader.read();assert(!item.done);events+=new TextDecoder().decode(item.value);}abort.abort();
   const bytes=Buffer.alloc(2*1024*1024,71);const uploaded=await fetch(proxy.base+'/api/files?name='+encodeURIComponent('Передача 2 МБ.bin'),{method:'POST',headers:{Cookie:cookie,'X-Ryadom':'1'},body:bytes});assert.equal(uploaded.status,201);const m=await uploaded.json();assert.equal(m.file.size,bytes.length);const download=await fetch(proxy.base+'/files/'+m.file.id,{headers:{Cookie:cookie,Range:'bytes=100-999'}});assert.equal(download.status,206);assert.deepEqual(Buffer.from(await download.arrayBuffer()),bytes.subarray(100,1000));assert.equal((await api(proxy.base,'/api/messages','POST',{text:'bad'},cookie,{Origin:'http://evil.example'})).r.status,403);
  });
 }finally{await Promise.all(children.reverse().map(stop));fs.rmSync(data,{recursive:true,force:true});}
});
test('Changing the main device is local and does not stop the remote chat',async()=>{
 const data=fs.mkdtempSync(path.join(os.tmpdir(),'ryadom-roles-'));const port=27000+Math.floor(Math.random()*1000),base=`http://127.0.0.1:${port}`,clientBase=`http://127.0.0.1:${port+1000}`;
 const hostSignal=path.join(data,'host-signal'),clientSignal=path.join(data,'client-signal');
 const host=processNode({RYADOM_PORT:String(port),RYADOM_DATA:data,RYADOM_ROLE_SIGNAL:hostSignal});let client;
 try{
  await ready(base);client=processNode({RYADOM_ROLE:'client',RYADOM_PORT:String(port+1000),RYADOM_TARGET:base,RYADOM_TUNNEL_PORT:String(port),RYADOM_ROLE_SIGNAL:clientSignal});await ready(clientBase);
  for(const [url,role,signal] of [[base,'host',hostSignal],[clientBase,'client',clientSignal]]){
   assert.equal((await api(url,'/api/local/status')).body.role,role);
   assert.equal((await fetch(url+'/api/local/choose-role',{method:'POST'})).status,403);
   assert.equal((await api(url,'/api/local/choose-role','POST',{},'',{Origin:'http://evil.example'})).r.status,403);
   assert(!fs.existsSync(signal));
   assert.equal((await api(url,'/api/local/choose-role','POST',{})).r.status,200);assert.equal(fs.readFileSync(signal,'utf8'),'choose');
  }
  await stop(client);client=null;assert.equal((await api(base,'/api/health')).r.status,200);assert(fs.existsSync(path.join(data,'chat.sqlite')));
 }finally{await stop(client);await stop(host);fs.rmSync(data,{recursive:true,force:true});}
});
test('Native bridge authenticates its preface and preserves remote restrictions',async()=>{
 const data=fs.mkdtempSync(path.join(os.tmpdir(),'ryadom-bridge-'));const port=24000+Math.floor(Math.random()*1000);const nativeKey='a'.repeat(64);const state=path.join(data,'lan.json');fs.writeFileSync(state,JSON.stringify({addresses:['192.168.50.1']}));const host=processNode({RYADOM_PORT:String(port),RYADOM_DATA:data,RYADOM_LAN_STATE:state,RYADOM_NATIVE_KEY:nativeKey,RYADOM_BRIDGE_PORT:String(port+1000)});
 const request=(prefix,route,body)=>new Promise((resolve,reject)=>{const socket=net.connect(port+1000,'127.0.0.1');let output='';socket.setTimeout(3000,()=>{socket.destroy();reject(new Error('Bridge timeout'));});socket.on('connect',()=>{const json=body?JSON.stringify(body):'';socket.write(prefix+'\r\n'+(body?'POST':'GET')+' '+route+' HTTP/1.1\r\nHost: 192.168.50.1:'+port+'\r\nConnection: close\r\nX-Ryadom: 1\r\nContent-Length: '+Buffer.byteLength(json)+'\r\n\r\n'+json);});socket.on('data',b=>output+=b);socket.on('end',()=>resolve(output));socket.on('error',reject);});
 try{await ready(`http://127.0.0.1:${port}`);assert.equal(await request('RYADOM/1 invalid 192.168.50.2 192.168.50.1','/api/health'),'');const prefix=`RYADOM/1 ${nativeKey} 192.168.50.2 192.168.50.1`;assert.match(await request(prefix,'/api/health'),/200 OK/);assert.match(await request(prefix,'/api/local/status'),/403 Forbidden/);assert.match(await request(prefix,'/api/host-login',{key:fs.readFileSync(path.join(data,'host-key'),'utf8')}),/403 Forbidden/);assert.equal(await request(`RYADOM/1 ${nativeKey} 192.168.50.2 172.19.0.1`,'/api/health'),'');}
 finally{await stop(host);fs.rmSync(data,{recursive:true,force:true});}
});
