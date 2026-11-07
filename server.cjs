'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { pipeline } = require('node:stream/promises');
const { Transform } = require('node:stream');
const { DatabaseSync } = require('node:sqlite');
const sea = require('node:sea');
const MAX_FILE = 512 * 1024 * 1024;
const PORT = Number(process.env.RYADOM_PORT || 4280);
const DATA = process.env.RYADOM_DATA || path.join(process.env.LOCALAPPDATA || os.homedir(), 'Ryadom');
const NATIVE_KEY=process.env.RYADOM_NATIVE_KEY||'';
if(process.env.RYADOM_ROLE==='client'){startClientProxy();return;}
const uploads = path.join(DATA, 'files');
fs.mkdirSync(uploads, { recursive: true });
const db = new DatabaseSync(path.join(DATA, 'chat.sqlite'));
db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
 CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token TEXT UNIQUE NOT NULL, host INTEGER NOT NULL DEFAULT 0, created TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, deviceId TEXT NOT NULL, deviceName TEXT NOT NULL, text TEXT NOT NULL, file TEXT, created TEXT NOT NULL, requestId TEXT UNIQUE NOT NULL);
 CREATE TABLE IF NOT EXISTS files (id TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL);`);
const hostKeyFile = path.join(DATA, 'host-key');
const hostKey = fs.existsSync(hostKeyFile) ? fs.readFileSync(hostKeyFile, 'utf8').trim() : crypto.randomBytes(32).toString('hex');
if (!fs.existsSync(hostKeyFile)) fs.writeFileSync(hostKeyFile, hostKey, { mode: 0o600 });
let pairing = { code: crypto.randomInt(10000000, 100000000).toString(), expires: Date.now() + 15 * 60 * 1000 };
const clients = new Map();
const attempts = new Map();
const activeUploads = new Map();
const uploadJobs = new Set();
const sha = x => crypto.createHash('sha256').update(x).digest('hex');
const now = () => new Date().toISOString();
const address = x => String(x || '').replace(/^::ffff:/, '');
function isPrivate(ip) {
 ip = address(ip);
 if (ip === '::1' || ip === '127.0.0.1') return true;
 const p = ip.split('.').map(Number);
 return p.length === 4 && p.every(n => Number.isInteger(n) && n >= 0 && n <= 255) && (p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168) || (p[0] === 169 && p[1] === 254));
}
function lanAddresses() {
 if(process.env.RYADOM_LAN_STATE){try{const value=JSON.parse(fs.readFileSync(process.env.RYADOM_LAN_STATE,'utf8'));return value.addresses.filter(isPrivate);}catch{return [];}}
 const entries=Object.entries(os.networkInterfaces()).flatMap(([name,items])=>items.map(item=>({...item,name}))).filter(x=>x.family==='IPv4'&&!x.internal&&isPrivate(x.address));
 return [...new Set(entries.filter(a=>!/virtual|vethernet|wsl|docker|vpn|tailscale|tap|loopback|happ|xray|tun/i.test(a.name)).map(x=>x.address))];
}
function error(status, message) { return Object.assign(new Error(message), { status }); }
function send(res, status, data, headers = {}) {
 res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
 res.end(JSON.stringify(data));
}
async function json(req) {
 let size = 0; const chunks = [];
 for await (const chunk of req) { size += chunk.length; if (size > 65536) throw error(413, 'Слишком длинное сообщение'); chunks.push(chunk); }
 try { return JSON.parse(Buffer.concat(chunks).toString()); } catch { throw error(400, 'Некорректный запрос'); }
}
function device(req) {
 const value = (req.headers.cookie || '').split(';').map(s => s.trim()).find(s => s.startsWith('ryadom_sid='))?.slice(11);
 return value ? db.prepare('SELECT * FROM devices WHERE token=?').get(sha(value)) : null;
}
function session(res, row, token) {
 send(res, 200, { ok: true, device: { id: row.id, name: row.name, host: !!row.host } }, { 'Set-Cookie': `ryadom_sid=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000` });
}
function publicDevices() {
 return db.prepare('SELECT id,name,host,created FROM devices ORDER BY created').all().map(d => ({ ...d, host: !!d.host, online: [...clients.values()].some(c => c.deviceId === d.id) }));
}
function broadcast(type, value) {
 const data = `event: ${type}\ndata: ${JSON.stringify(value)}\n\n`;
 for (const res of clients.keys()) if (!res.write(data) || res.writableLength > 1024 * 1024) res.destroy();
}
function message(row) { return { ...row, file: row.file ? JSON.parse(row.file) : null }; }
function addMessage(d, text, file, requestId, announce = true) {
 const old = db.prepare('SELECT * FROM messages WHERE requestId=?').get(requestId);
 if (old) { if (old.deviceId !== d.id) throw error(409, 'Повторите отправку'); return message(old); }
 const id = crypto.randomUUID();
 db.prepare('INSERT INTO messages (id,deviceId,deviceName,text,file,created,requestId) VALUES (?,?,?,?,?,?,?)').run(id, d.id, d.name, text, file ? JSON.stringify(file) : null, now(), requestId);
 const result = message(db.prepare('SELECT * FROM messages WHERE id=?').get(id));
 if (announce) broadcast('message', result); return result;
}
function cleanName(value) { return String(value || '').replace(/[\x00-\x1f\x7f/\\]/g, '').trim().slice(0, 180) || 'Файл'; }
function sniff(file) {
 const fd = fs.openSync(file, 'r'); const b = Buffer.alloc(32); fs.readSync(fd, b, 0, 32, 0); fs.closeSync(fd);
 if (b.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) return 'image/png';
 if (b[0] === 255 && b[1] === 216 && b[2] === 255) return 'image/jpeg';
 if (/^GIF8[79]a/.test(b.toString('ascii', 0, 6))) return 'image/gif';
 if (b.toString('ascii', 0, 4) === 'RIFF' && b.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
 if (b.toString('ascii', 4, 8) === 'ftyp') return /^hei|^mif|^avi/.test(b.toString('ascii',8,12)) ? 'application/octet-stream' : 'video/mp4';
 if (b.subarray(0,4).equals(Buffer.from([26,69,223,163]))) return 'video/webm';
 return 'application/octet-stream';
}
function getAsset(name) { return sea.isSea() ? Buffer.from(sea.getAsset(name)) : fs.readFileSync(path.join(__dirname, 'public', name)); }
const assets = new Map(['index.html', 'app.js', 'style.css', 'icon.svg'].map(x => [x, getAsset(x)]));
const server = http.createServer(async (req, res) => {
 res.setHeader('X-Content-Type-Options', 'nosniff');
 res.setHeader('Referrer-Policy', 'no-referrer');
 res.setHeader('X-Frame-Options', 'DENY');
 res.setHeader('Cache-Control', 'no-store');
 res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
 try {
  const peer=req.socket.ryadomPeer||req.socket.remoteAddress;
  if (!isPrivate(peer)) throw error(403, 'Доступ только из локальной сети');
  const host = req.headers.host || '';
  if (![...lanAddresses(), '127.0.0.1', 'localhost'].some(ip => host === `${ip}:${PORT}`)) throw error(403, 'Недопустимый адрес');
  if (req.headers.origin && req.headers.origin !== `http://${host}`) throw error(403, 'Недопустимый источник');
  if (!['GET','HEAD'].includes(req.method) && req.headers['x-ryadom'] !== '1') throw error(403, 'Недопустимый запрос');
  const url = new URL(req.url, `http://${host}`); const route = url.pathname;
  if(route.startsWith('/api/local/')) {
   if(req.socket.ryadomPeer||address(req.socket.remoteAddress)!=='127.0.0.1')throw error(403,'Только на этом устройстве');
   if(route==='/api/local/status'&&req.method==='GET')return send(res,200,{app:'ryadom',version:'0.1.0',role:'host'});
   if(route==='/api/local/choose-role'&&req.method==='POST'&&process.env.RYADOM_ROLE_SIGNAL){fs.writeFileSync(process.env.RYADOM_ROLE_SIGNAL,'choose');return send(res,200,{ok:true});}
   if(route==='/api/local/quit'&&req.method==='POST'&&NATIVE_KEY&&req.headers['x-ryadom-native']===NATIVE_KEY){send(res,200,{ok:true});setTimeout(shutdown,200);return;}
   throw error(404,'Не найдено');
  }
  if (req.method === 'GET' && ['/', '/app.js', '/style.css', '/icon.svg'].includes(route)) {
   const name = route === '/' ? 'index.html' : route.slice(1);
   res.writeHead(200, { 'Content-Type': { html:'text/html; charset=utf-8', js:'text/javascript; charset=utf-8', css:'text/css; charset=utf-8', svg:'image/svg+xml' }[name.split('.').pop()] }); return res.end(assets.get(name));
  }
  if (route === '/api/health' && req.method === 'GET') return send(res, 200, { app:'ryadom', version:'0.1.0',name:os.hostname() });
  if (route === '/api/host-login' && req.method === 'POST') {
   if (!['127.0.0.1','::1'].includes(address(peer))||req.socket.ryadomPeer) throw error(403, 'Откройте чат на главном устройстве');
   const body = await json(req);
   if (typeof body.key !== 'string' || sha(body.key) !== sha(hostKey)) throw error(403, 'Запустите приложение Рядом на компьютере');
   let d = db.prepare('SELECT * FROM devices WHERE host=1').get();
   const token = crypto.createHmac('sha256', hostKey).update('host-session').digest('hex');
   if (!d) { db.prepare('INSERT INTO devices VALUES (?,?,?,?,?)').run(crypto.randomUUID(), 'Мой компьютер', sha(token), 1, now()); d = db.prepare('SELECT * FROM devices WHERE host=1').get(); }
   return session(res, d, token);
  }
  if (route === '/api/pair' && req.method === 'POST') {
   const ip = address(peer); const entry = attempts.get(ip) || { count:0, until:Date.now()+60000 };
   if (Date.now() > entry.until) { entry.count = 0; entry.until = Date.now()+60000; }
   attempts.set(ip, entry); if (++entry.count > 8) throw error(429, 'Слишком много попыток. Подождите минуту');
   const body = await json(req);
   if (Date.now() > pairing.expires) throw error(403, 'Код истёк. Создайте новый на компьютере');
   if (String(body.code || '').replace(/\s/g,'') !== pairing.code) throw error(403, 'Неверный код подключения');
   const name = cleanName(body.name).slice(0,40); if (!body.name?.trim()) throw error(400, 'Укажите имя устройства');
   if (db.prepare('SELECT count(*) AS n FROM devices').get().n >= 50) throw error(409, 'Удалите неиспользуемые устройства на компьютере');
   const token = crypto.randomBytes(32).toString('hex');
   const d = { id:crypto.randomUUID(), name, host:0 };
   db.prepare('INSERT INTO devices VALUES (?,?,?,?,?)').run(d.id, name, sha(token), 0, now());
   session(res,d,token); broadcast('devices', publicDevices()); return;
  }
  const d = device(req); if (!d) throw error(401, 'Подключите это устройство по коду');
  if (route === '/api/state' && req.method === 'GET') {
   const messages = db.prepare('SELECT * FROM messages ORDER BY seq DESC LIMIT 100').all().reverse().map(message);
   return send(res, 200, { me:{id:d.id,name:d.name,host:!!d.host}, devices:publicDevices(), messages, maxFile:MAX_FILE, total:db.prepare('SELECT count(*) AS n FROM messages').get().n, server:{addresses:lanAddresses().map(ip=>`http://${ip}:${PORT}`),direct:!!NATIVE_KEY,localControls:!req.socket.ryadomPeer&&!!process.env.RYADOM_ROLE_SIGNAL}, pairing:d.host ? pairing : undefined });
  }
  if (route === '/api/messages' && req.method === 'GET') {
   if (url.searchParams.has('after')) {
    const after = Math.max(0, Number(url.searchParams.get('after')) || 0);
    return send(res,200,db.prepare('SELECT * FROM messages WHERE seq>? ORDER BY seq LIMIT 100').all(after).map(message));
   }
   const before = Number(url.searchParams.get('before')) || Number.MAX_SAFE_INTEGER;
   return send(res,200,db.prepare('SELECT * FROM messages WHERE seq<? ORDER BY seq DESC LIMIT 100').all(before).reverse().map(message));
  }
  if (route === '/api/messages' && req.method === 'POST') {
   const body = await json(req); const text = String(body.text || '').trim();
   if (!text || text.length > 12000) throw error(400, 'Сообщение должно содержать от 1 до 12 000 символов');
   const requestId = String(body.requestId || crypto.randomUUID()).slice(0,80);
   return send(res, 201, addMessage(d,text,null,requestId));
  }
  if (route === '/api/files' && req.method === 'POST') {
   const size = Number(req.headers['content-length']);
   if (!Number.isSafeInteger(size) || size < 1 || size > MAX_FILE) throw error(413, 'Файл должен быть от 1 байта до 512 МБ');
   const requestId = String(url.searchParams.get('requestId') || crypto.randomUUID()).slice(0,80);
   const old = db.prepare('SELECT * FROM messages WHERE requestId=? AND deviceId=?').get(requestId,d.id);
   if (old) { req.resume(); return send(res,200,message(old)); }
   if (uploadJobs.has(requestId) || (activeUploads.get(d.id) || 0) >= 2) throw error(429, 'Дождитесь завершения текущей передачи');
   uploadJobs.add(requestId); activeUploads.set(d.id,(activeUploads.get(d.id)||0)+1);
   const id = crypto.randomUUID(); const tmp = path.join(uploads,`${id}.part`); const final = path.join(uploads,id); let committed = false;
   try {
    let count = 0;
    const limit = new Transform({ transform(chunk, enc, cb) { count += chunk.length; cb(count > MAX_FILE ? error(413,'Файл больше 512 МБ') : null,chunk); } });
    await pipeline(req,limit,fs.createWriteStream(tmp,{flags:'wx'}));
    if (count !== size) throw error(400,'Передача прервалась. Отправьте файл ещё раз');
    if (!db.prepare('SELECT id FROM devices WHERE id=?').get(d.id)) throw error(401,'Доступ устройства отозван');
    const f = {id,name:cleanName(url.searchParams.get('name')),mime:sniff(tmp),size:count};
    fs.renameSync(tmp,final);
    db.exec('BEGIN');
    let result;
    try { db.prepare('INSERT INTO files VALUES (?,?,?,?)').run(id,f.name,f.mime,count); result = addMessage(d,'',f,requestId,false); db.exec('COMMIT'); committed = true; }
    catch(e) { db.exec('ROLLBACK'); throw e; }
    broadcast('message',result);
    return send(res,201,result);
   } finally { uploadJobs.delete(requestId); activeUploads.set(d.id,Math.max(0,(activeUploads.get(d.id)||1)-1)); fs.rmSync(tmp,{force:true}); if (!committed) fs.rmSync(final,{force:true}); }
  }
  if (route.startsWith('/files/') && ['GET','HEAD'].includes(req.method)) {
   const f = db.prepare('SELECT * FROM files WHERE id=?').get(route.slice(7)); if (!f) throw error(404,'Файл не найден');
   const disk = path.join(uploads,f.id); if (!fs.existsSync(disk)) throw error(404,'Файл удалён с компьютера');
   let start=0,end=f.size-1,status=200;
   if (req.headers.range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
    if (!m || (!m[1]&&!m[2])) { res.writeHead(416,{'Content-Range':`bytes */${f.size}`}); return res.end(); }
    if (m[1]) { start=Number(m[1]); if(m[2]) end=Math.min(Number(m[2]),end); }
    else start=Math.max(0,f.size-Number(m[2]));
    if(start>end||start>=f.size) { res.writeHead(416,{'Content-Range':`bytes */${f.size}`}); return res.end(); } status=206;
   }
   const inline = !url.searchParams.has('download') && /^(image|video)\//.test(f.mime);
   const headers = { 'Content-Type':f.mime,'Content-Length':end-start+1,'Accept-Ranges':'bytes','Content-Disposition':`${inline?'inline':'attachment'}; filename="file"; filename*=UTF-8''${encodeURIComponent(f.name)}` };
   if(status===206) headers['Content-Range']=`bytes ${start}-${end}/${f.size}`;
   res.writeHead(status,headers); if(req.method==='HEAD') return res.end();
   await pipeline(fs.createReadStream(disk,{start,end}),res); return;
  }
  if (route === '/api/events' && req.method === 'GET') {
   res.writeHead(200,{'Content-Type':'text/event-stream','Connection':'keep-alive','X-Accel-Buffering':'no'}); res.write('retry: 1500\n\n');
   clients.set(res,{deviceId:d.id}); broadcast('devices',publicDevices());
   const keepalive=setInterval(()=>res.write(': alive\n\n'),20000);
   req.on('close',()=>{ clearInterval(keepalive); clients.delete(res); broadcast('devices',publicDevices()); }); return;
  }
  if (route === '/api/device' && req.method === 'PATCH') {
   const body=await json(req); const name=cleanName(body.name).slice(0,40); if(!body.name?.trim()) throw error(400,'Укажите имя');
   db.prepare('UPDATE devices SET name=? WHERE id=?').run(name,d.id); broadcast('devices',publicDevices()); return send(res,200,{ok:true});
  }
  if(route==='/api/logout'&&req.method==='POST') { return send(res,200,{ok:true},{'Set-Cookie':'ryadom_sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'}); }
  if(route.startsWith('/api/host/')) {
   if(!d.host) throw error(403,'Это действие доступно на главном компьютере');
   if(route==='/api/host/pairing'&&req.method==='POST') { const body=await json(req); pairing={code:crypto.randomInt(10000000,100000000).toString(),expires:body.enabled===false?0:Date.now()+15*60*1000}; return send(res,200,pairing); }
   if(route.startsWith('/api/host/devices/')&&req.method==='DELETE') {
    const id=route.slice('/api/host/devices/'.length); db.prepare('DELETE FROM devices WHERE id=? AND host=0').run(id);
    for(const [r,c] of clients) if(c.deviceId===id) { r.write('event: revoked\ndata: {}\n\n'); r.end(); clients.delete(r); }
    broadcast('devices',publicDevices()); return send(res,200,{ok:true});
   }
   if(route==='/api/host/stop'&&req.method==='POST') { send(res,200,{ok:true}); setTimeout(shutdown,250); return; }
  }
  throw error(404,'Не найдено');
 } catch(e) { if(!res.headersSent&&!res.destroyed) send(res,e.status||500,{error:e.status?e.message:'Не удалось выполнить действие. Проверьте свободное место на компьютере'}); if(!e.status&&e.code!=='ERR_STREAM_PREMATURE_CLOSE'&&e.code!=='ECONNRESET') console.error(e); }
});
server.requestTimeout = 30*60*1000;
server.headersTimeout = 30000;
function openWindow() {
 if(process.env.RYADOM_NO_OPEN==='1') return;
 const url=`http://127.0.0.1:${PORT}/#host=${hostKey}`;
 const { spawn }=require('node:child_process');
 if(process.platform==='win32') {
  const edge=path.join(process.env['ProgramFiles(x86)']||'C:\\Program Files (x86)','Microsoft','Edge','Application','msedge.exe');
  const chrome=path.join(process.env.ProgramFiles||'C:\\Program Files','Google','Chrome','Application','chrome.exe');
  const browser=[edge,chrome].find(fs.existsSync);
  if(browser) { const p=spawn(browser,[`--app=${url}`,'--window-size=1180,820'],{detached:true,stdio:'ignore'}); p.unref(); }
  else { const p=spawn('rundll32.exe',['url.dll,FileProtocolHandler',url],{detached:true,stdio:'ignore'}); p.unref(); }
 } else { const p=spawn(process.platform==='darwin'?'open':'xdg-open',[url],{stdio:'ignore',detached:true}); p.on('error',()=>{}); p.unref(); }
}
function shutdown() { bridgeServer?.close();for(const res of clients.keys()) res.end(); server.close(()=>{db.close();process.exit(0);}); setTimeout(()=>process.exit(0),2000).unref(); }
process.on('SIGINT',shutdown); process.on('SIGTERM',shutdown);
if(process.env.RYADOM_PARENT_PID) {
 const parent=Number(process.env.RYADOM_PARENT_PID);
 if(Number.isSafeInteger(parent)&&parent>0)setInterval(()=>{try{process.kill(parent,0);}catch{shutdown();}},3000).unref();
}
server.on('error',e=>{ if(e.code==='EADDRINUSE') { console.log('Рядом уже запущен. Открываем чат.'); openWindow(); db.close(); process.exit(0); } else { console.error(e); process.exit(1); } });
let bridgeServer=null;
if(NATIVE_KEY&&process.env.RYADOM_BRIDGE_PORT){
 bridgeServer=require('node:net').createServer(socket=>{
  let prefix=Buffer.alloc(0);socket.setTimeout(5000,()=>socket.destroy());
  const receive=chunk=>{prefix=Buffer.concat([prefix,chunk]);const end=prefix.indexOf('\r\n');if(end<0){if(prefix.length>256)socket.destroy();return;}
   socket.pause();socket.removeListener('data',receive);const p=prefix.subarray(0,end).toString('ascii').split(' ');
   if(p.length!==4||p[0]!=='RYADOM/1'||p[1]!==NATIVE_KEY||!isPrivate(p[2])||!lanAddresses().includes(p[3])){socket.destroy();return;}
   socket.ryadomPeer=p[2];socket.ryadomLocal=p[3];socket.setTimeout(0);if(prefix.length>end+2)socket.unshift(prefix.subarray(end+2));server.emit('connection',socket);socket.resume();
  };socket.on('data',receive);socket.on('error',()=>{});
 });bridgeServer.on('error',e=>{console.error(e);process.exit(1);});bridgeServer.listen(Number(process.env.RYADOM_BRIDGE_PORT),'127.0.0.1');
}
server.listen(PORT,process.env.RYADOM_LAN_STATE?'127.0.0.1':'0.0.0.0',()=>{
 // Only the listening process may clean up interrupted uploads.
 for (const file of fs.readdirSync(uploads)) if (file.endsWith('.part')) fs.rmSync(path.join(uploads, file), { force: true });
 process.stdout.write('\x1b]0;Рядом — личный чат\x07');
 console.log(`\n  РЯДОМ · личный чат\n\n  Чат работает. Это окно можно свернуть.\n  Закрытие окна выключит чат для всех устройств.\n\n  Адреса для телефона:\n  ${lanAddresses().map(ip=>`http://${ip}:${PORT}`).join('\n  ')||'Подключитесь к Wi-Fi и перезапустите приложение.'}\n\n  Данные: ${DATA}\n`);
 openWindow();
});

// The browser uses loopback. The native tunnel binds each outbound socket to a physical NIC.
function startClientProxy(){
 const target=new URL(process.env.RYADOM_TARGET);const tunnel=Number(process.env.RYADOM_TUNNEL_PORT);const own=`127.0.0.1:${PORT}`;
 const cookieName='ryadom_sid_'+crypto.createHash('sha256').update(target.origin).digest('hex').slice(0,16);
 const proxy=http.createServer((req,res)=>{
  const fail=(status,message)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify({error:message}));};
  if(req.socket.remoteAddress!=='127.0.0.1'||req.headers.host!==own||req.headers.origin&&req.headers.origin!==`http://${own}`)return fail(403,'Недопустимый источник');
  if(!['GET','HEAD'].includes(req.method)&&req.headers['x-ryadom']!=='1')return fail(403,'Недопустимый запрос');
  if(req.url==='/api/local/status'){res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({app:'ryadom',role:'client',version:'0.1.0',target:target.origin}));}
  if(req.url==='/api/local/choose-role'&&req.method==='POST'&&process.env.RYADOM_ROLE_SIGNAL){fs.writeFileSync(process.env.RYADOM_ROLE_SIGNAL,'choose');res.end('{"ok":true}');return;}
  if(req.url==='/api/local/quit'&&req.method==='POST'&&NATIVE_KEY&&req.headers['x-ryadom-native']===NATIVE_KEY){res.end('{"ok":true}');setTimeout(()=>process.exit(0),200);return;}
  if(req.url.startsWith('/api/local/')||req.url.startsWith('/api/host-login'))return fail(403,'Недоступно при подключении к другому устройству');
  const headers={...req.headers,host:target.host};delete headers['x-ryadom-native'];delete headers['proxy-authorization'];delete headers['proxy-connection'];delete headers.forwarded;delete headers['x-forwarded-for'];
  if(headers.origin)headers.origin=target.origin;
  const sid=(headers.cookie||'').split(';').map(x=>x.trim()).find(x=>x.startsWith(cookieName+'='));headers.cookie=sid?'ryadom_sid='+sid.slice(cookieName.length+1):'';
  const upstream=http.request({hostname:'127.0.0.1',port:tunnel,path:req.url,method:req.method,headers,agent:false},reply=>{
   const responseHeaders={...reply.headers,'Cache-Control':'no-store'};if(responseHeaders['set-cookie'])responseHeaders['set-cookie']=responseHeaders['set-cookie'].map(c=>c.replace(/^ryadom_sid=/,cookieName+'='));
   if(req.url==='/api/state'&&reply.statusCode===200){const chunks=[];reply.on('data',b=>chunks.push(b));reply.on('end',()=>{try{const data=JSON.parse(Buffer.concat(chunks));data.server.localControls=true;data.server.direct=true;const body=JSON.stringify(data);delete responseHeaders['transfer-encoding'];responseHeaders['content-length']=Buffer.byteLength(body);res.writeHead(200,responseHeaders);res.end(body);}catch{fail(502,'Нет ответа от главного устройства');}});}
   else {res.writeHead(reply.statusCode,responseHeaders);reply.pipe(res);}res.on('close',()=>reply.destroy());
  });
  upstream.on('error',()=>{if(res.headersSent){res.destroy();return;}if(req.url==='/'&&req.method==='GET'){res.writeHead(503,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'"});res.end('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Рядом</title><body style="font:18px Segoe UI,sans-serif;padding:10%;background:#f5f6f3;color:#36533b"><h1>Главное устройство не отвечает</h1><p>Проверьте, запущен ли чат, адрес и домашнюю сеть. Провод и Wi-Fi должны быть в одной подсети без изоляции клиентов.</p><p>Сменить главное устройство можно через значок Рядом рядом с часами.</p><a href="/">Повторить</a>');}else fail(502,'Главное устройство недоступно. Проверьте локальную сеть.');});req.on('aborted',()=>upstream.destroy());res.on('close',()=>upstream.destroy());req.pipe(upstream);
 });proxy.requestTimeout=30*60*1000;proxy.listen(PORT,'127.0.0.1');
 if(process.env.RYADOM_PARENT_PID)setInterval(()=>{try{process.kill(Number(process.env.RYADOM_PARENT_PID),0);}catch{process.exit(0);}},3000).unref();
}
