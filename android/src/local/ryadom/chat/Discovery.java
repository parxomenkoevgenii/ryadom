package local.ryadom.chat;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;

final class Discovery implements AutoCloseable {
 interface Binder {void bind(DatagramSocket socket) throws IOException;}
 static final int PORT=42800;
 static final class Found {final String name,address;final int port;Found(String n,String a,int p){name=n;address=a;port=p;}public String toString(){return name+" — "+address+":"+port;}}
 final MulticastSocket socket;final List<String> ips;final List<Integer> prefixes;final Binder binder;volatile boolean closed;
 Discovery(List<String> addresses,List<Integer> masks,String name,Binder binding) throws Exception {
  ips=addresses;prefixes=masks;binder=binding;socket=new MulticastSocket(null);socket.setReuseAddress(true);socket.bind(new InetSocketAddress(PORT));
  for(String ip:ips)socket.joinGroup(new InetSocketAddress("239.255.42.80",PORT),NetworkInterface.getByInetAddress(InetAddress.getByName(ip)));
  Thread thread=new Thread(()->listen(name),"Ryadom discovery");thread.setDaemon(true);thread.start();
 }
 private String nic(String remote){for(int i=0;i<ips.size();i++)try{if(LanNetwork.sameSubnet(InetAddress.getByName(ips.get(i)).getAddress(),prefixes.get(i),remote))return ips.get(i);}catch(Exception ignored){}return null;}
 private void listen(String name){while(!closed)try{DatagramPacket packet=new DatagramPacket(new byte[1024],1024);socket.receive(packet);String text=new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8);if(!text.matches("RYADOM_FIND_V1\\t[0-9a-f]{32}"))continue;String address=nic(packet.getAddress().getHostAddress());if(address==null)continue;String response="RYADOM_HERE_V1\t"+text.substring(15)+"\t"+address+"\t4280\t"+Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);try(DatagramSocket sender=new DatagramSocket(new InetSocketAddress(InetAddress.getByName(address),0))){binder.bind(sender);sender.send(new DatagramPacket(bytes,bytes.length,packet.getAddress(),packet.getPort()));}}catch(Exception e){if(closed)return;}}
 static List<Found> find(List<String> ips,List<Integer> prefixes,Binder binder) throws Exception {
  Map<String,Found> found=new LinkedHashMap<>();for(int i=0;i<ips.size();i++){
   String ip=ips.get(i);int prefix=prefixes.get(i);byte[] address=InetAddress.getByName(ip).getAddress(),broadcast=address.clone();for(int j=0;j<4;j++){int bits=Math.min(8,Math.max(0,prefix-j*8));int mask=bits==0?0:(255<<(8-bits))&255;broadcast[j]=(byte)(address[j]|~mask);}
   try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress(InetAddress.getByName(ip),0))){binder.bind(socket);socket.setBroadcast(true);socket.setSoTimeout(300);String nonce=UUID.randomUUID().toString().replace("-","");byte[] request=("RYADOM_FIND_V1\t"+nonce).getBytes(StandardCharsets.UTF_8);socket.send(new DatagramPacket(request,request.length,InetAddress.getByAddress(broadcast),PORT));socket.send(new DatagramPacket(request,request.length,InetAddress.getByName("239.255.42.80"),PORT));long until=System.currentTimeMillis()+2000;while(System.currentTimeMillis()<until){try{DatagramPacket reply=new DatagramPacket(new byte[1024],1024);socket.receive(reply);String[] p=new String(reply.getData(),0,reply.getLength(),StandardCharsets.UTF_8).split("\t");if(p.length!=5||!p[0].equals("RYADOM_HERE_V1")||!p[1].equals(nonce)||!p[2].equals(reply.getAddress().getHostAddress())||!LanNetwork.sameSubnet(address,prefix,p[2]))continue;int port=Integer.parseInt(p[3]);if(port<1||port>65535)continue;String name=new String(Base64.getDecoder().decode(p[4]),StandardCharsets.UTF_8);if(name.length()>80)continue;found.put(p[2]+":"+port,new Found(name,p[2],port));}catch(SocketTimeoutException ignored){}}}
  }return new ArrayList<>(found.values());
 }
 public void close(){closed=true;socket.close();}
}
