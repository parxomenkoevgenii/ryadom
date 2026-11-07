'use strict';
const $ = id => document.getElementById(id);
const icon = name => { const s=document.createElementNS('http://www.w3.org/2000/svg','svg');const u=document.createElementNS(s.namespaceURI,'use');u.setAttribute('href',`#i-${name}`);s.append(u);return s; };
const el = (tag,cls,text) => {const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
let me=null, devices=[], messages=new Map(), pairing=null, addresses=[], maxFile=512*1024*1024, events=null, online=false, filter='all', queue=[], sending=false, canLoadOlder=false, expiryTimer=null, toastTimer=null, syncing=false;
const uuid=()=>{if(crypto.randomUUID)return crypto.randomUUID();const b=crypto.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;return [...b].map((v,i)=>([4,6,8,10].includes(i)?'-':'')+v.toString(16).padStart(2,'0')).join('');};
const sizeText=n=>n<1024?`${n} Б`:n<1024*1024?`${(n/1024).toFixed(0)} КБ`:`${(n/1024/1024).toFixed(n<10*1024*1024?1:0)} МБ`;
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4500);}
async function api(route,method='GET',body){
 const r=await fetch(route,{method,headers:method==='GET'?{}:{'Content-Type':'application/json','X-Ryadom':'1'},body:body===undefined?undefined:JSON.stringify(body)});
 const data=await r.json();if(!r.ok){const e=new Error(data.error||'Не удалось выполнить действие');e.status=r.status;throw e;}return data;
}
async function copy(text){try{if(navigator.clipboard&&window.isSecureContext)await navigator.clipboard.writeText(text);else{const input=el('textarea');input.value=text;input.className='copy-helper';(document.querySelector('dialog[open]')||document.body).append(input);input.select();const ok=document.execCommand('copy');input.remove();if(!ok)throw new Error();}toast('Скопировано');}catch{toast('Не удалось скопировать. Выделите текст вручную.');}}
function showJoin(){events?.close();events=null;me=null;messages.clear();queue=[];$('app').hidden=true;$('join-screen').hidden=false;$('device-name').value=/Android/i.test(navigator.userAgent)?'Мой Android':/iPhone|iPad/i.test(navigator.userAgent)?'Мой iPhone':'Мой ноутбук';if($('settings-dialog').open)$('settings-dialog').close();}
function applyState(data){me=data.me;devices=data.devices;pairing=data.pairing;addresses=data.server.addresses;$('change-role-button').hidden=!data.server.localControls;maxFile=data.maxFile;data.messages.forEach(m=>messages.set(m.id,m));canLoadOlder=data.total>messages.size;$('app').hidden=false;$('join-screen').hidden=true;renderDevices();renderMessages();updateButtons();}
async function sync(){
 if(syncing||!me)return;syncing=true;
 try{
  const last=Math.max(0,...[...messages.values()].map(m=>m.seq));
  if(last){let cursor=last;while(true){const page=await api(`/api/messages?after=${cursor}`);page.forEach(m=>messages.set(m.id,m));if(page.length<100)break;cursor=page[page.length-1].seq;}}
  const data=await api('/api/state');applyState(data);
 }catch(e){if(e.status===401){showJoin();toast('Подключите устройство заново');}}finally{syncing=false;}
}
function connectEvents(){
 events?.close();events=new EventSource('/api/events');
 events.onopen=()=>{online=true;renderConnection();sync();};
 events.onerror=()=>{online=false;renderConnection();sync();};
 events.addEventListener('message',e=>{const m=JSON.parse(e.data);messages.set(m.id,m);renderMessages(m.deviceId===me?.id);});
 events.addEventListener('devices',e=>{devices=JSON.parse(e.data);renderDevices();});
 events.addEventListener('revoked',()=>{showJoin();toast('Доступ устройства отозван на главном устройстве');});
}
function renderConnection(){
 $('connection-dot').className=`status-dot ${online?'online':'offline'}`;
 const n=devices.filter(d=>d.online).length;
 $('connection-text').textContent=online?`${n} ${n===1?'устройство':n<5?'устройства':'устройств'} в сети`:'Нет связи с компьютером';
 $('connection-banner').hidden=online;updateButtons();
}
function deviceIcon(d){if(/android|телефон|iphone|pixel|galaxy/i.test(d.name))return 'phone';return d.host||/компьютер|ноутбук|pc|mac|desktop/i.test(d.name)?'monitor':'phone';}
function renderDevices(){
 if(!me)return;const current=devices.find(d=>d.id===me.id);if(current)me.name=current.name;
 $('device-count').textContent=devices.length;$('settings-count').textContent=devices.length;
 $('profile-name').textContent=me.name;$('profile-initial').textContent=me.name.slice(0,1).toUpperCase();
 const list=$('device-list');const settings=$('settings-devices');list.replaceChildren();settings.replaceChildren();
 for(const d of devices){
  const row=el('div','device-row');row.append(icon(deviceIcon(d)));const detail=el('div','device-detail');detail.append(el('strong','',d.name));const small=el('small');small.append(el('span',`small-dot ${d.online?'online':''}`),document.createTextNode(d.id===me.id?'Это устройство':d.online?'В сети':'Не в сети'));detail.append(small);row.append(detail);list.append(row);
  const item=el('div','settings-device');item.append(icon(deviceIcon(d)));const info=el('div','device-detail');info.append(el('strong','',d.name),el('small','',`${d.host?'Главное устройство':d.online?'В сети':'Не в сети'}${d.id===me.id?' · Вы':''}`));item.append(info);
  if(me.host&&!d.host){const b=el('button','revoke','Отключить');b.type='button';b.onclick=async()=>{if(!confirm(`Отозвать доступ устройства «${d.name}»? Для нового входа понадобится код.`))return;try{await api(`/api/host/devices/${d.id}`,'DELETE');toast('Доступ отозван');await sync();}catch(e){toast(e.message);}};item.append(b);}settings.append(item);
 }
 $('sidebar-connect').hidden=!me.host;$('connect-button').hidden=!me.host;renderConnection();
}
function renderMessages(forceBottom=false){
 const area=$('scroll-area');const atBottom=area.scrollHeight-area.scrollTop-area.clientHeight<100;const all=[...messages.values()].sort((a,b)=>a.seq-b.seq);const query=$('search').value.trim().toLocaleLowerCase('ru');
 const shown=all.filter(m=>(filter!=='media'||m.file&&/^(image|video)\//.test(m.file.mime))&&(!query||`${m.text} ${m.file?.name||''} ${m.deviceName}`.toLocaleLowerCase('ru').includes(query)));
 $('empty').hidden=all.length>0||!!query||filter==='media';$('no-results').hidden=shown.length>0||!$('empty').hidden;$('no-results').textContent=filter==='media'&&!query?'Здесь появятся ваши фото и видео':'Ничего не найдено среди загруженных сообщений';$('load-older').hidden=!canLoadOlder;
 const target=$('messages');const existing=new Map([...target.children].map(n=>[n.dataset.key,n]));const desired=[];let day='';
 for(const m of shown){const date=new Date(m.created);const label=date.toLocaleDateString('ru-RU',{day:'numeric',month:'long',year:date.getFullYear()===new Date().getFullYear()?undefined:'numeric'});if(day!==label){const key=`day-${m.seq}`;const node=existing.get(key)||el('div','day-label',label);node.dataset.key=key;desired.push(node);day=label;}const node=existing.get(m.id)||renderMessage(m);node.dataset.key=m.id;desired.push(node);}
 const keep=new Set(desired);for(const child of [...target.children])if(!keep.has(child))child.remove();desired.forEach((node,i)=>{if(target.children[i]!==node)target.insertBefore(node,target.children[i]||null);});if(atBottom||forceBottom)area.scrollTop=area.scrollHeight;
}
function renderMessage(m){
 const own=m.deviceId===me?.id;const row=el('article',`message-row ${own?'own':''}`);row.dataset.id=m.id;row.append(el('span','message-avatar',m.deviceName.slice(0,1).toUpperCase()));const content=el('div','message-content');content.append(el('div','message-author',own?'Вы':m.deviceName));const bubble=el('div','bubble');
 if(m.text)bubble.append(el('p','message-text',m.text));
 if(m.file){const f=m.file;const url=`/files/${f.id}`;
  if(f.mime.startsWith('image/')){const b=el('button','media-button');b.type='button';b.setAttribute('aria-label',`Открыть ${f.name}`);const img=el('img','media-preview');img.src=url;img.alt=f.name;img.loading='lazy';b.append(img);b.onclick=()=>{$('full-image').src=url;$('full-image').alt=f.name;$('full-image-download').href=`${url}?download`;$('full-image-download').download=f.name;$('image-dialog').showModal();};bubble.append(b);}
  if(f.mime.startsWith('video/')){const video=el('video','media-preview');video.controls=true;video.preload='metadata';video.playsInline=true;video.src=url;video.setAttribute('aria-label',f.name);bubble.append(video);}
  const detail=el('div','file-details');if(f.mime==='application/octet-stream'){const i=el('span','file-icon');i.append(icon('file'));detail.append(i);}const info=el('div','file-info');info.append(el('div','file-name',f.name),el('div','file-size',`${sizeText(f.size)} · Оригинал`));const download=el('a','file-download');download.href=`${url}?download`;download.download=f.name;download.title='Скачать оригинал';download.setAttribute('aria-label',`Скачать ${f.name}`);download.append(icon('download'));detail.append(info,download);bubble.append(detail);
 }
 const footer=el('div','message-footer');if(m.text){const c=el('button','message-copy');c.title='Скопировать текст';c.setAttribute('aria-label','Скопировать текст');c.append(icon('copy'));c.onclick=()=>copy(m.text);footer.append(c);}const time=el('time','',new Date(m.created).toLocaleTimeString('ru-RU',{hour:'2-digit',minute:'2-digit'}));time.dateTime=m.created;footer.append(time);if(own){const check=icon('check');check.setAttribute('aria-label','Сохранено на компьютере');footer.append(check);}bubble.append(footer);content.append(bubble);row.append(content);return row;
}
function addFiles(files){
 for(const f of files){if(queue.length>=20){toast('За один раз можно добавить до 20 файлов');break;}if(f.size>maxFile||!f.size){toast(`«${f.name}»: нужен файл от 1 байта до 512 МБ`);continue;}queue.push({id:uuid(),file:f,progress:0,status:'ready'});}renderQueue();updateButtons();
}
function renderQueue(){const list=$('attachment-list');list.replaceChildren();list.hidden=!queue.length;for(const item of queue){const row=el('div',`attachment-item ${item.status==='error'?'failed':''}`);row.append(icon(item.file.type.startsWith('image/')?'image':item.file.type.startsWith('video/')?'play':'file'));const info=el('div','attachment-info');info.append(el('div','attachment-name',item.file.name),el('div','attachment-status',item.status==='error'?'Не отправлен · нажмите отправить снова':item.status==='uploading'?`Передаём ${item.progress}%`:`${sizeText(item.file.size)} · Готов к отправке`));if(item.status==='uploading'){const progress=el('progress');progress.max=100;progress.value=item.progress;info.append(progress);}row.append(info);if(item.status!=='uploading'){const remove=el('button','icon-button');remove.setAttribute('aria-label',`Убрать ${item.file.name}`);remove.append(icon('close'));remove.disabled=sending;remove.onclick=()=>{queue=queue.filter(x=>x.id!==item.id);renderQueue();updateButtons();};row.append(remove);}list.append(row);}}
function updateButtons(){$('send-button').disabled=sending||(!$('message-input').value.trim()&&!queue.length);$('attach-button').disabled=sending;}
function upload(item){return new Promise((resolve,reject)=>{const xhr=new XMLHttpRequest();xhr.open('POST',`/api/files?name=${encodeURIComponent(item.file.name)}&requestId=${item.id}`);xhr.setRequestHeader('X-Ryadom','1');xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.timeout=30*60*1000;item.status='uploading';renderQueue();xhr.upload.onprogress=e=>{if(e.lengthComputable){item.progress=Math.round(e.loaded/e.total*100);renderQueue();}};xhr.onload=()=>{let data;try{data=JSON.parse(xhr.responseText);}catch{reject(new Error('Не удалось получить подтверждение передачи'));return;}if(xhr.status>=200&&xhr.status<300)resolve(data);else reject(new Error(data.error||'Передача не удалась'));};xhr.onerror=()=>reject(new Error('Связь прервалась. Проверьте домашнюю сеть и повторите отправку'));xhr.ontimeout=()=>reject(new Error('Передача заняла слишком долго. Попробуйте ещё раз'));xhr.send(item.file);});}
let pendingText=null;
$('message-form').onsubmit=async e=>{e.preventDefault();if(sending)return;const input=$('message-input');const text=input.value.trim();if(!text&&!queue.length)return;sending=true;updateButtons();
 try{if(text){if(!pendingText||pendingText.text!==text)pendingText={text,requestId:uuid()};const m=await api('/api/messages','POST',pendingText);messages.set(m.id,m);pendingText=null;if(input.value.trim()===text)input.value='';input.style.height='auto';renderMessages(true);}for(const item of [...queue]){try{const m=await upload(item);messages.set(m.id,m);queue=queue.filter(x=>x.id!==item.id);renderMessages(true);renderQueue();}catch(e){item.status='error';renderQueue();throw e;}}}catch(e){toast(e.message||'Не удалось отправить. Проверьте соединение');}finally{sending=false;renderQueue();updateButtons();input.focus();}
};
$('message-input').oninput=()=>{const t=$('message-input');t.style.height='auto';t.style.height=`${Math.min(t.scrollHeight,140)}px`;updateButtons();};
$('message-input').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing&&!matchMedia('(pointer:coarse)').matches){e.preventDefault();$('message-form').requestSubmit();}};
$('message-input').onpaste=e=>{if(e.clipboardData.files.length){e.preventDefault();addFiles(e.clipboardData.files);}};
$('attach-button').onclick=()=>$('file-input').click();$('file-input').onchange=()=>{addFiles($('file-input').files);$('file-input').value='';};
let dragCount=0;document.addEventListener('dragenter',e=>{if(me&&e.dataTransfer.types.includes('Files')){e.preventDefault();dragCount++;$('drop-overlay').hidden=false;}});document.addEventListener('dragleave',()=>{if(--dragCount<=0){dragCount=0;$('drop-overlay').hidden=true;}});document.addEventListener('dragover',e=>{if(me&&e.dataTransfer.types.includes('Files'))e.preventDefault();});document.addEventListener('drop',e=>{if(!me)return;e.preventDefault();dragCount=0;$('drop-overlay').hidden=true;if(!sending)addFiles(e.dataTransfer.files);});
$('join-form').onsubmit=async e=>{e.preventDefault();$('join-error').textContent='';$('join-button').disabled=true;try{await api('/api/pair','POST',{name:$('device-name').value.trim(),code:$('pair-code').value});const data=await api('/api/state');applyState(data);connectEvents();$('pair-code').value='';}catch(e){$('join-error').textContent=e.message==='Failed to fetch'?'Нет связи. Проверьте домашнюю сеть и главное устройство.':e.message;}finally{$('join-button').disabled=false;}};
$('pair-code').oninput=()=>{const s=$('pair-code').value.replace(/\D/g,'').slice(0,8);$('pair-code').value=s.length>4?`${s.slice(0,4)} ${s.slice(4)}`:s;};
function setFilter(next){filter=next;$('tab-all').classList.toggle('active',next==='all');$('tab-media').classList.toggle('active',next==='media');$('tab-all').setAttribute('aria-pressed',next==='all');$('tab-media').setAttribute('aria-pressed',next==='media');renderMessages(true);}
$('tab-all').onclick=()=>setFilter('all');$('all-room').onclick=()=>setFilter('all');$('tab-media').onclick=()=>setFilter('media');
$('search-toggle').onclick=()=>{$('search-bar').hidden=!$('search-bar').hidden;if(!$('search-bar').hidden)$('search').focus();else{$('search').value='';renderMessages();}};$('search-close').onclick=()=>{$('search-bar').hidden=true;$('search').value='';renderMessages();};$('search').oninput=()=>renderMessages();
$('load-older').onclick=async()=>{const a=$('scroll-area');const before=Math.min(...[...messages.values()].map(m=>m.seq));const previous=a.scrollHeight;$('load-older').disabled=true;try{const page=await api(`/api/messages?before=${before}`);page.forEach(m=>messages.set(m.id,m));canLoadOlder=page.length===100;renderMessages();a.scrollTop+=a.scrollHeight-previous;}catch(e){toast(e.message);}finally{$('load-older').disabled=false;}};
function renderPairing(){if(!pairing)return;const seconds=Math.max(0,Math.ceil((pairing.expires-Date.now())/1000));$('pairing-code').textContent=seconds?`${pairing.code.slice(0,4)} ${pairing.code.slice(4)}`:'•••• ••••';$('pairing-expiry').textContent=seconds?`Действует ещё ${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}`:'Подключение закрыто';$('copy-code').disabled=!seconds;$('disable-pairing').hidden=!seconds;}
async function openSettings(){try{await sync();if(!me)return;$('pairing-section').hidden=!me.host;$('client-info').hidden=me.host;$('logout-button').hidden=me.host;$('stop-button').hidden=!me.host;$('rename-input').value=me.name;$('server-addresses').replaceChildren();$('no-address').hidden=addresses.length>0;for(const address of addresses){const row=el('div','server-address');row.append(el('code','',address));const c=el('button','icon-button');c.append(icon('copy'));c.setAttribute('aria-label','Скопировать адрес');c.onclick=()=>copy(address);row.append(c);$('server-addresses').append(row);}renderPairing();clearInterval(expiryTimer);expiryTimer=setInterval(renderPairing,1000);$('settings-dialog').showModal();}catch(e){toast(e.message);}}
for(const id of ['sidebar-connect','connect-button','profile-button','mobile-settings'])$(id).onclick=openSettings;
$('close-settings').onclick=()=>$('settings-dialog').close();$('settings-dialog').addEventListener('close',()=>clearInterval(expiryTimer));
$('new-code').onclick=async()=>{try{pairing=await api('/api/host/pairing','POST',{enabled:true});renderPairing();toast('Новый код готов');}catch(e){toast(e.message);}};
$('disable-pairing').onclick=async()=>{try{pairing=await api('/api/host/pairing','POST',{enabled:false});renderPairing();}catch(e){toast(e.message);}};
$('copy-code').onclick=()=>copy(pairing.code);
$('rename-form').onsubmit=async e=>{e.preventDefault();try{await api('/api/device','PATCH',{name:$('rename-input').value.trim()});await sync();toast('Имя сохранено');}catch(e){toast(e.message);}};
$('logout-button').onclick=async()=>{if(!confirm('Выйти из чата на этом устройстве? Для входа понадобится новый код.'))return;try{await api('/api/logout','POST',{});showJoin();}catch(e){toast(e.message);}};
$('change-role-button').onclick=async()=>{if(!confirm('Вернуться к выбору главного устройства? Текущий чат на этом устройстве закроется. История останется там, где был создан чат.'))return;try{await api('/api/local/choose-role','POST',{});events?.close();$('settings-dialog').close();toast('Открываем выбор главного устройства…');}catch(e){toast(e.message);}};
$('stop-button').onclick=async()=>{if(!confirm('Выключить чат? Другие устройства потеряют соединение. Переписка и файлы сохранятся.'))return;try{await api('/api/host/stop','POST',{});events?.close();const box=el('main','stopped');box.append(el('h1','','До встречи рядом.'),el('p','','Чат выключен. Переписка сохранена. Чтобы продолжить, запустите Рядом на компьютере.'));document.body.replaceChildren(box);}catch(e){toast(e.message);}};
$('close-image').onclick=()=>$('image-dialog').close();$('image-dialog').addEventListener('close',()=>{$('full-image').removeAttribute('src');});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&me)sync();});
window.addEventListener('online',()=>{if(me){connectEvents();sync();}});
(async()=>{try{const connect=new URLSearchParams(location.search).get('connect')==='1';const hash=new URLSearchParams(location.hash.slice(1));const key=hash.get('host');if(key){history.replaceState(null,'',location.pathname);await api('/api/host-login','POST',{key});}applyState(await api('/api/state'));connectEvents();if(connect)openSettings();}catch(e){showJoin();if(e.status!==401)$('join-error').textContent=e.message==='Failed to fetch'?'Нет связи с главным компьютером.':e.message;}})();
