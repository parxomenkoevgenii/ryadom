package local.ryadom.chat;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Local host shared by Android and the JVM integration tests. Persistent, streaming, LAN-only. */
final class ChatHost implements AutoCloseable {
 interface Assets {byte[] read(String name) throws IOException;}
 interface Mutation {void apply(JSONObject next) throws Exception;}
 static final long MAX_FILE=512L*1024*1024;
 final File directory,files,stateFile;final Assets assets;final List<String> addresses;final int port;final String hostKey;
 private JSONObject state;private String code;private long expires;private final SecureRandom random=new SecureRandom();
 private final ConcurrentHashMap<MiniHttp.Request,String> streams=new ConcurrentHashMap<>();
 private final Map<String,long[]> attempts=new HashMap<>();private final Set<String> uploadIds=new HashSet<>();
 private final Map<String,Integer> uploads=new HashMap<>();private final Runnable stop;
 private final MiniHttp http;
 ChatHost(File dir,List<String> ips,int port,Assets a,Runnable stopRequest) throws Exception {
  directory=dir;files=new File(dir,"files");files.mkdirs();stateFile=new File(dir,"chat.json");assets=a;addresses=new ArrayList<>(ips);this.port=port;stop=stopRequest;
  state=stateFile.exists()?new JSONObject(new String(Files.readAllBytes(stateFile.toPath()),StandardCharsets.UTF_8)):new JSONObject().put("devices",new JSONArray()).put("messages",new JSONArray()).put("files",new JSONArray()).put("seq",0);
  File keyFile=new File(dir,"host-key");hostKey=keyFile.exists()?new String(Files.readAllBytes(keyFile.toPath()),StandardCharsets.UTF_8).trim():token();if(!keyFile.exists())Files.write(keyFile.toPath(),hostKey.getBytes(StandardCharsets.UTF_8));
  for(File file:Objects.requireNonNull(files.listFiles()))if(file.getName().endsWith(".part"))file.delete();newCode(true);
  List<String> bind=new ArrayList<>();bind.add("127.0.0.1");for(String ip:ips)if(!bind.contains(ip))bind.add(ip);http=new MiniHttp(bind,port,this::handle);
 }
 String localUrl(){return "http://127.0.0.1:"+port+"/#host="+hostKey;}
 private static String now(){return Instant.now().toString();}
 private String token(){byte[] b=new byte[32];random.nextBytes(b);StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format("%02x",n));return s.toString();}
 private static String hash(String text) throws Exception {byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format("%02x",n));return s.toString();}
 private synchronized void change(Mutation mutation) throws Exception {
  JSONObject next=new JSONObject(state.toString());mutation.apply(next);File tmp=new File(directory,"chat.json.tmp");
  try(FileOutputStream out=new FileOutputStream(tmp)){out.write(next.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  try{Files.move(tmp.toPath(),stateFile.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp.toPath(),stateFile.toPath(),StandardCopyOption.REPLACE_EXISTING);}state=next;
 }
 private static JSONObject find(JSONArray values,String key,String value) throws JSONException {for(int i=0;i<values.length();i++){JSONObject x=values.getJSONObject(i);if(x.optString(key).equals(value))return x;}return null;}
 private synchronized JSONObject device(MiniHttp.Request r) throws Exception {String token="";for(String part:r.header("cookie").split(";"))if(part.trim().startsWith("ryadom_sid="))token=part.trim().substring(11);return token.isEmpty()?null:find(state.getJSONArray("devices"),"token",hash(token));}
 private static String name(String value){String result=value.replaceAll("[\\x00-\\x1f\\x7f/\\\\]","").trim();return result.isEmpty()?"Файл":result.substring(0,Math.min(result.length(),180));}
 private JSONObject body(MiniHttp.Request r) throws Exception {try{return new JSONObject(new String(r.body(65536),StandardCharsets.UTF_8));}catch(JSONException e){throw new MiniHttp.Failure(400,"Некорректный запрос");}}
 private synchronized JSONObject pairing() throws Exception{return new JSONObject().put("code",code).put("expires",expires);}
 private synchronized void newCode(boolean enabled){code=String.valueOf(10000000+random.nextInt(90000000));expires=enabled?System.currentTimeMillis()+15*60*1000:0;}
 private synchronized JSONArray publicDevices() throws Exception {JSONArray result=new JSONArray(),all=state.getJSONArray("devices");for(int i=0;i<all.length();i++){JSONObject d=all.getJSONObject(i);result.put(new JSONObject().put("id",d.getString("id")).put("name",d.getString("name")).put("host",d.getBoolean("host")).put("created",d.getString("created")).put("online",streams.containsValue(d.getString("id"))));}return result;}
 private static void event(MiniHttp.Request r,String type,Object value) throws IOException {synchronized(r.out){r.out.write(("event: "+type+"\ndata: "+value+"\n\n").getBytes(StandardCharsets.UTF_8));r.out.flush();}}
 private void broadcast(String type,Object value){for(MiniHttp.Request r:streams.keySet())try{event(r,type,value);}catch(IOException e){streams.remove(r);try{r.socket.close();}catch(Exception ignored){}}}
 private void devicesChanged() throws Exception {broadcast("devices",publicDevices());}
 private void session(MiniHttp.Request r,JSONObject d,String token) throws Exception {Map<String,String> h=new HashMap<>();h.put("Set-Cookie","ryadom_sid="+token+"; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000");String json=new JSONObject().put("ok",true).put("device",new JSONObject().put("id",d.getString("id")).put("name",d.getString("name")).put("host",d.getBoolean("host"))).toString();r.reply(200,"application/json; charset=utf-8",json.getBytes(StandardCharsets.UTF_8),h);}
 private synchronized JSONObject addMessage(JSONObject d,String text,JSONObject file,String requestId) throws Exception {
  JSONObject old=find(state.getJSONArray("messages"),"requestId",requestId);if(old!=null){if(!old.getString("deviceId").equals(d.getString("id")))throw new MiniHttp.Failure(409,"Повторите отправку");return old;}
  JSONObject message=new JSONObject().put("seq",state.getLong("seq")+1).put("id",UUID.randomUUID().toString()).put("deviceId",d.getString("id")).put("deviceName",d.getString("name")).put("text",text).put("file",file==null?JSONObject.NULL:file).put("created",now()).put("requestId",requestId);
  change(next->{next.put("seq",message.getLong("seq"));next.getJSONArray("messages").put(message);if(file!=null)next.getJSONArray("files").put(file);});broadcast("message",message);return message;
 }
 private void handle(MiniHttp.Request r) throws Exception {
  String remote=r.socket.getInetAddress().getHostAddress();if(!r.loopback()&&!LanNetwork.privateAddress(remote))throw new MiniHttp.Failure(403,"Только локальная сеть");
  Set<String> hosts=new HashSet<>();hosts.add("127.0.0.1:"+port);hosts.add("localhost:"+port);for(String ip:addresses)hosts.add(ip+":"+port);
  if(!hosts.contains(r.header("host")))throw new MiniHttp.Failure(403,"Недопустимый адрес");if(!r.header("origin").isEmpty()&&!r.header("origin").equals("http://"+r.header("host")))throw new MiniHttp.Failure(403,"Недопустимый источник");
  if(!r.method.equals("GET")&&!r.method.equals("HEAD")&&!r.header("x-ryadom").equals("1"))throw new MiniHttp.Failure(403,"Недопустимый запрос");String route=r.path;
  if(r.method.equals("GET")&&Arrays.asList("/","/app.js","/style.css","/icon.svg").contains(route)){String asset=route.equals("/")?"index.html":route.substring(1);String mime=asset.endsWith(".html")?"text/html; charset=utf-8":asset.endsWith(".js")?"text/javascript; charset=utf-8":asset.endsWith(".css")?"text/css; charset=utf-8":"image/svg+xml";r.reply(200,mime,assets.read(asset),null);return;}
  if(route.equals("/api/health")){r.json(200,new JSONObject().put("app","ryadom").put("version","0.1.0").put("name","Рядом на Android").toString());return;}
  if(route.equals("/api/host-login")&&r.method.equals("POST")){
   if(!r.loopback())throw new MiniHttp.Failure(403,"Только на главном устройстве");JSONObject b=body(r);if(!hash(b.optString("key")).equals(hash(hostKey)))throw new MiniHttp.Failure(403,"Откройте чат в приложении");
   String token=hash("session:"+hostKey);JSONObject d;synchronized(this){d=find(state.getJSONArray("devices"),"host","true");if(d==null){d=new JSONObject().put("id",UUID.randomUUID().toString()).put("name","Мой Android").put("token",hash(token)).put("host",true).put("created",now());final JSONObject created=d;change(next->next.getJSONArray("devices").put(created));}}session(r,d,token);return;
  }
  if(route.equals("/api/pair")&&r.method.equals("POST")){
   JSONObject b=body(r),d;String token=token();synchronized(this){long[] attempt=attempts.get(remote);if(attempt==null||attempt[1]<System.currentTimeMillis()){attempt=new long[]{0,System.currentTimeMillis()+60000};attempts.put(remote,attempt);}if(++attempt[0]>8)throw new MiniHttp.Failure(429,"Подождите минуту перед новой попыткой");if(expires<System.currentTimeMillis())throw new MiniHttp.Failure(403,"Код истёк. Создайте новый на главном устройстве");if(!b.optString("code").replaceAll("\\s","").equals(code))throw new MiniHttp.Failure(403,"Неверный код");String n=b.optString("name").trim();if(n.isEmpty())throw new MiniHttp.Failure(400,"Укажите имя устройства");if(state.getJSONArray("devices").length()>=50)throw new MiniHttp.Failure(409,"Удалите старые устройства");n=name(n);d=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",n.substring(0,Math.min(n.length(),40))).put("host",false).put("token",hash(token)).put("created",now());change(next->next.getJSONArray("devices").put(d));}session(r,d,token);devicesChanged();return;
  }
  JSONObject d=device(r);if(d==null)throw new MiniHttp.Failure(401,"Подключите устройство по коду");
  if(route.equals("/api/state")&&r.method.equals("GET")){
   JSONObject result;synchronized(this){JSONArray all=state.getJSONArray("messages"),last=new JSONArray();for(int i=Math.max(0,all.length()-100);i<all.length();i++)last.put(all.get(i));JSONArray urls=new JSONArray();for(String ip:addresses)urls.put("http://"+ip+":"+port);result=new JSONObject().put("me",new JSONObject().put("id",d.getString("id")).put("name",d.getString("name")).put("host",d.getBoolean("host"))).put("devices",publicDevices()).put("messages",last).put("maxFile",MAX_FILE).put("total",all.length()).put("server",new JSONObject().put("addresses",urls).put("direct",true).put("localControls",false));if(d.getBoolean("host"))result.put("pairing",pairing());}r.json(200,result.toString());return;
  }
  if(route.equals("/api/messages")&&r.method.equals("GET")){
   JSONArray result=new JSONArray();long cursor;boolean after=!r.parameter("after").isEmpty();try{cursor=Long.parseLong(r.parameter(after?"after":"before"));}catch(Exception e){cursor=after?0:Long.MAX_VALUE;}
   synchronized(this){JSONArray all=state.getJSONArray("messages");List<JSONObject> selected=new ArrayList<>();for(int i=0;i<all.length();i++){JSONObject m=all.getJSONObject(i);if(after?m.getLong("seq")>cursor:m.getLong("seq")<cursor)selected.add(m);}int start=after?0:Math.max(0,selected.size()-100);for(int i=start;i<Math.min(selected.size(),start+100);i++)result.put(selected.get(i));}r.json(200,result.toString());return;
  }
  if(route.equals("/api/messages")&&r.method.equals("POST")){JSONObject b=body(r);String text=b.optString("text").trim();if(text.isEmpty()||text.length()>12000)throw new MiniHttp.Failure(400,"Сообщение должно содержать от 1 до 12 000 символов");String id=b.optString("requestId",UUID.randomUUID().toString());r.json(201,addMessage(d,text,null,id.substring(0,Math.min(80,id.length()))).toString());return;}
  if(route.equals("/api/files")&&r.method.equals("POST")){upload(r,d);return;}
  if(route.startsWith("/files/")&&(r.method.equals("GET")||r.method.equals("HEAD"))){download(r,route.substring(7));return;}
  if(route.equals("/api/events")&&r.method.equals("GET")){
   r.head(200,"text/event-stream",-1,null);r.out.write("retry: 1500\n\n".getBytes(StandardCharsets.UTF_8));r.out.flush();streams.put(r,d.getString("id"));devicesChanged();try{while(!http.closed&&!r.socket.isClosed()){Thread.sleep(15000);synchronized(r.out){r.out.write(": alive\n\n".getBytes(StandardCharsets.UTF_8));r.out.flush();}}}finally{streams.remove(r);devicesChanged();}return;
  }
  if(route.equals("/api/device")&&r.method.equals("PATCH")){String n=body(r).optString("name").trim();if(n.isEmpty())throw new MiniHttp.Failure(400,"Укажите имя");final String newName=name(n).substring(0,Math.min(name(n).length(),40));change(next->find(next.getJSONArray("devices"),"id",d.getString("id")).put("name",newName));devicesChanged();r.json(200,"{\"ok\":true}");return;}
  if(route.equals("/api/logout")&&r.method.equals("POST")){Map<String,String> h=new HashMap<>();h.put("Set-Cookie","ryadom_sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");r.reply(200,"application/json","{\"ok\":true}".getBytes(StandardCharsets.UTF_8),h);return;}
  if(route.startsWith("/api/host/")){
   if(!d.getBoolean("host"))throw new MiniHttp.Failure(403,"Доступно на главном устройстве");
   if(route.equals("/api/host/pairing")&&r.method.equals("POST")){newCode(body(r).optBoolean("enabled",true));r.json(200,pairing().toString());return;}
   if(route.startsWith("/api/host/devices/")&&r.method.equals("DELETE")){String id=route.substring("/api/host/devices/".length());change(next->{JSONArray all=next.getJSONArray("devices");for(int i=all.length()-1;i>=0;i--){JSONObject x=all.getJSONObject(i);if(x.getString("id").equals(id)&&!x.getBoolean("host"))all.remove(i);}});for(Map.Entry<MiniHttp.Request,String> entry:streams.entrySet())if(entry.getValue().equals(id)){try{event(entry.getKey(),"revoked",new JSONObject());entry.getKey().socket.close();}catch(Exception ignored){}streams.remove(entry.getKey());}devicesChanged();r.json(200,"{\"ok\":true}");return;}
   if(route.equals("/api/host/stop")&&r.method.equals("POST")){r.json(200,"{\"ok\":true}");new Thread(()->{try{Thread.sleep(200);}catch(Exception ignored){}stop.run();}).start();return;}
  }
  throw new MiniHttp.Failure(404,"Не найдено");
 }
 private void upload(MiniHttp.Request r,JSONObject d) throws Exception {
  if(r.length<1||r.length>MAX_FILE)throw new MiniHttp.Failure(413,"Файл должен быть от 1 байта до 512 МБ");String request=r.parameter("requestId");if(request.isEmpty())request=UUID.randomUUID().toString();final String requestId=request.substring(0,Math.min(request.length(),80)),deviceId=d.getString("id");
  synchronized(this){JSONObject old=find(state.getJSONArray("messages"),"requestId",requestId);if(old!=null&&old.getString("deviceId").equals(deviceId)){r.json(200,old.toString());return;}if(uploadIds.contains(requestId)||uploads.getOrDefault(deviceId,0)>=2)throw new MiniHttp.Failure(429,"Дождитесь текущей передачи");uploadIds.add(requestId);uploads.put(deviceId,uploads.getOrDefault(deviceId,0)+1);}
  String id=UUID.randomUUID().toString();File tmp=new File(files,id+".part"),file=new File(files,id);boolean committed=false;
  try{try(OutputStream out=new FileOutputStream(tmp)){MiniHttp.copy(r.in,out,r.length);}String mime=sniff(tmp);Files.move(tmp.toPath(),file.toPath());JSONObject f=new JSONObject().put("id",id).put("name",name(r.parameter("name"))).put("mime",mime).put("size",r.length);JSONObject message;synchronized(this){if(find(state.getJSONArray("devices"),"id",deviceId)==null)throw new MiniHttp.Failure(401,"Доступ отозван");message=addMessage(d,"",f,requestId);committed=true;}r.json(201,message.toString());}
  finally{tmp.delete();if(!committed)file.delete();synchronized(this){uploadIds.remove(requestId);uploads.put(deviceId,Math.max(0,uploads.getOrDefault(deviceId,1)-1));}}
 }
 private static String sniff(File file) throws IOException {byte[] b=new byte[32];try(InputStream in=new FileInputStream(file)){in.read(b);}if(b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71)return "image/png";if(b[0]==(byte)255&&b[1]==(byte)216&&b[2]==(byte)255)return "image/jpeg";String start=new String(b,StandardCharsets.ISO_8859_1);if(start.startsWith("GIF87a")||start.startsWith("GIF89a"))return "image/gif";if(start.startsWith("RIFF")&&start.substring(8,12).equals("WEBP"))return "image/webp";if(start.substring(4,8).equals("ftyp"))return start.substring(8,12).matches("(hei.|mif.|avi.)")?"application/octet-stream":"video/mp4";if(b[0]==26&&b[1]==69&&b[2]==(byte)223&&b[3]==(byte)163)return "video/webm";return "application/octet-stream";}
 private void download(MiniHttp.Request r,String id) throws Exception {
  JSONObject f;synchronized(this){f=find(state.getJSONArray("files"),"id",id);}if(f==null)throw new MiniHttp.Failure(404,"Файл не найден");File file=new File(files,f.getString("id"));if(!file.isFile())throw new MiniHttp.Failure(404,"Файл удалён");long size=f.getLong("size"),start=0,end=size-1;int status=200;String range=r.header("range");Map<String,String> h=new HashMap<>();h.put("Accept-Ranges","bytes");
  if(!range.isEmpty()){try{if(!range.matches("bytes=[0-9]*-[0-9]*")||range.equals("bytes=-"))throw new Exception();String[] parts=range.substring(6).split("-",-1);if(parts[0].isEmpty())start=Math.max(0,size-Long.parseLong(parts[1]));else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}if(start>end||start>=size)throw new Exception();status=206;h.put("Content-Range","bytes "+start+"-"+end+"/"+size);}catch(Exception e){h.put("Content-Range","bytes */"+size);r.reply(416,"text/plain",new byte[0],h);return;}}
  String mime=f.getString("mime");boolean inline=!r.target.contains("?download")&&(mime.startsWith("image/")||mime.startsWith("video/"));h.put("Content-Disposition",(inline?"inline":"attachment")+"; filename=\"file\"; filename*=UTF-8''"+URLEncoder.encode(f.getString("name"),"UTF-8").replace("+","%20"));r.head(status,mime,end-start+1,h);if(r.method.equals("HEAD"))return;try(FileInputStream in=new FileInputStream(file)){in.getChannel().position(start);MiniHttp.copy(in,r.out,end-start+1);}
 }
 public void close(){http.close();streams.clear();}
}
