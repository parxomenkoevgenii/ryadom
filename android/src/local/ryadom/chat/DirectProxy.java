package local.ryadom.chat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** All WebView requests (including POST, uploads, SSE, video) pass through bound native sockets. */
final class DirectProxy implements AutoCloseable {
 interface Connector {Socket connect(String host,int port) throws IOException;}
 final MiniHttp http;final String remote,host,cookie;final int port;final Connector connector;
 DirectProxy(String base,Connector factory) throws Exception {
  URL url=new URL(base);host=url.getHost();port=url.getPort()==-1?4280:url.getPort();remote="http://"+host+":"+port;connector=factory;
  byte[] digest=MessageDigest.getInstance("SHA-256").digest(remote.getBytes(StandardCharsets.UTF_8));StringBuilder suffix=new StringBuilder();for(int i=0;i<8;i++)suffix.append(String.format("%02x",digest[i]));cookie="ryadom_sid_"+suffix;
  http=new MiniHttp(Collections.singletonList("127.0.0.1"),0,this::handle);
 }
 String base(){return "http://127.0.0.1:"+http.port();}
 private void handle(MiniHttp.Request r) throws Exception {
  if(!r.loopback()||!r.header("host").equals("127.0.0.1:"+http.port())||(!r.header("origin").isEmpty()&&!r.header("origin").equals(base())))throw new MiniHttp.Failure(403,"Недопустимый источник");
  if(!r.method.equals("GET")&&!r.method.equals("HEAD")&&!r.header("x-ryadom").equals("1"))throw new MiniHttp.Failure(403,"Недопустимый запрос");
  if(r.path.startsWith("/api/local/")||r.path.equals("/api/host-login"))throw new MiniHttp.Failure(403,"Недоступно на клиенте");
  try(Socket upstream=connector.connect(host,port)){
   OutputStream out=upstream.getOutputStream();StringBuilder head=new StringBuilder(r.method+" "+r.target+" HTTP/1.1\r\nHost: "+host+":"+port+"\r\nConnection: close\r\n");
   for(Map.Entry<String,String> h:r.headers.entrySet()){
    String k=h.getKey(),v=h.getValue();if(Arrays.asList("host","connection","proxy-connection","proxy-authorization","x-ryadom-native","forwarded","x-forwarded-for").contains(k))continue;
    if(k.equals("origin"))v=remote;
    if(k.equals("cookie")){String sid="";for(String piece:v.split(";"))if(piece.trim().startsWith(cookie+"="))sid="ryadom_sid="+piece.trim().substring(cookie.length()+1);v=sid;}
    head.append(k).append(": ").append(v).append("\r\n");
   }
   head.append("\r\n");out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));MiniHttp.copy(r.in,out,r.length);out.flush();
   InputStream in=new BufferedInputStream(upstream.getInputStream(),65536);String status=MiniHttp.line(in,8192);StringBuilder response=new StringBuilder(status+"\r\n");int total=0;
   while(true){String line=MiniHttp.line(in,8192);if(line.isEmpty())break;total+=line.length();if(total>32768)throw new IOException("Headers too large");if(line.toLowerCase(Locale.ROOT).startsWith("set-cookie: ryadom_sid="))line="Set-Cookie: "+cookie+"="+line.substring("set-cookie: ryadom_sid=".length());response.append(line).append("\r\n");}
   response.append("\r\n");r.out.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));r.out.flush();r.sent=true;MiniHttp.copy(in,r.out,-1);
  }catch(IOException e){if(r.sent)throw e;throw new MiniHttp.Failure(503,"Нет прямой связи с главным устройством. Проверьте Wi-Fi, изоляцию роутера и исключение «Рядом» в Happ.");}
 }
 public void close(){http.close();}
}
