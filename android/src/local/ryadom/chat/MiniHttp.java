package local.ryadom.chat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Small HTTP/1.1 transport, with bounded headers and streaming bodies. No proxy or default network. */
final class MiniHttp implements AutoCloseable {
 interface Handler { void handle(Request request) throws Exception; }
 static final class Failure extends IOException {final int status;Failure(int s,String m){super(m);status=s;}}
 static final class Request {
  final Socket socket;final BufferedInputStream in;final OutputStream out;final Map<String,String> headers=new LinkedHashMap<>();
  String method,target,path;long length;boolean sent=false;
  Request(Socket s) throws IOException {
   socket=s;in=new BufferedInputStream(s.getInputStream(),65536);out=s.getOutputStream();
   String[] start=line(in,8192).split(" ");if(start.length!=3||!start[2].equals("HTTP/1.1")||!start[1].startsWith("/"))throw new Failure(400,"Bad request");method=start[0];target=start[1];path=target.split("\\?",2)[0];
   int total=0;while(true){String h=line(in,8192);if(h.isEmpty())break;total+=h.length();if(total>32768)throw new Failure(431,"Headers too large");int colon=h.indexOf(':');if(colon<1)throw new Failure(400,"Bad header");String key=h.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.containsKey(key))throw new Failure(400,"Duplicate header");headers.put(key,h.substring(colon+1).trim());}
   if(headers.containsKey("transfer-encoding"))throw new Failure(411,"Content-Length required");
   try{length=Long.parseLong(headers.getOrDefault("content-length","0"));}catch(Exception e){throw new Failure(400,"Invalid length");}if(length<0)throw new Failure(400,"Invalid length");
  }
  boolean loopback(){return socket.getInetAddress().isLoopbackAddress();}
  String header(String key){return headers.getOrDefault(key,"");}
  String parameter(String key){try{String[] p=target.split("\\?",2);if(p.length<2)return "";for(String entry:p[1].split("&")){String[] kv=entry.split("=",2);if(URLDecoder.decode(kv[0],"UTF-8").equals(key))return kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"";}}catch(Exception ignored){}return "";}
  byte[] body(int max) throws IOException {if(length>max)throw new Failure(413,"Слишком большой запрос");ByteArrayOutputStream result=new ByteArrayOutputStream();copy(in,result,length);return result.toByteArray();}
  void head(int status,String mime,long length,Map<String,String> extra) throws IOException {
   StringBuilder h=new StringBuilder("HTTP/1.1 "+status+" "+reason(status)+"\r\nContent-Type: "+mime+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n");
   if(length>=0)h.append("Content-Length: ").append(length).append("\r\n");if(extra!=null)for(Map.Entry<String,String> e:extra.entrySet())h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.UTF_8));out.flush();sent=true;
  }
  void reply(int status,String mime,byte[] body,Map<String,String> headers) throws IOException {head(status,mime,body.length,headers);if(!method.equals("HEAD"))out.write(body);out.flush();}
  void json(int status,String value) throws IOException {reply(status,"application/json; charset=utf-8",value.getBytes(StandardCharsets.UTF_8),null);}
 }
 private final List<ServerSocket> listeners=new CopyOnWriteArrayList<>();private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,64,30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(),r->{Thread t=new Thread(r,"Ryadom HTTP");t.setDaemon(true);return t;});
 volatile boolean closed;
 MiniHttp(List<String> addresses,int port,Handler handler) throws IOException {
  try{for(String address:addresses){ServerSocket listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress(InetAddress.getByName(address),port),64);listeners.add(listener);Thread accept=new Thread(()->{while(!closed){try{Socket socket=listener.accept();socket.setSoTimeout(120000);socket.setTcpNoDelay(true);sockets.add(socket);try{workers.execute(()->serve(socket,handler));}catch(RejectedExecutionException e){sockets.remove(socket);socket.close();}}catch(IOException e){if(closed)return;}}},"Ryadom accept");accept.setDaemon(true);accept.start();}}catch(IOException e){close();throw e;}
 }
 int port(){return listeners.get(0).getLocalPort();}
 private void serve(Socket socket,Handler handler){Request r=null;try{r=new Request(socket);handler.handle(r);}catch(Exception e){if(r!=null&&!r.sent)try{String message=e instanceof Failure?e.getMessage():"Не удалось выполнить действие";r.json(e instanceof Failure?((Failure)e).status:500,"{\"error\":"+org.json.JSONObject.quote(message)+"}");}catch(Exception ignored){}}finally{sockets.remove(socket);try{socket.close();}catch(Exception ignored){}}}
 static String line(InputStream in,int max) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();int previous=-1;while(true){int b=in.read();if(b<0)throw new EOFException();if(previous==13&&b==10){byte[] bytes=out.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.ISO_8859_1);}out.write(b);if(out.size()>max)throw new Failure(431,"Line too large");previous=b;}}
 static void copy(InputStream in,OutputStream out,long count) throws IOException {byte[] buffer=new byte[65536];long left=count;while(left!=0){int n=in.read(buffer,0,(int)(left<0?buffer.length:Math.min(left,buffer.length)));if(n<0){if(left>0)throw new EOFException();break;}out.write(buffer,0,n);if(left>0)left-=n;}}
 static String reason(int s){switch(s){case 200:return "OK";case 201:return "Created";case 206:return "Partial Content";case 401:return "Unauthorized";case 403:return "Forbidden";case 404:return "Not Found";case 416:return "Range Not Satisfiable";case 503:return "Unavailable";default:return "Response";}}
 public void close(){closed=true;for(ServerSocket s:listeners)try{s.close();}catch(Exception ignored){}for(Socket s:sockets)try{s.close();}catch(Exception ignored){}workers.shutdownNow();}
}
