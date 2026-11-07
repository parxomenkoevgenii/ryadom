package local.ryadom.chat;
import java.net.*;
public final class ProxyHarness {
 public static void main(String[] args) throws Exception {
  DirectProxy proxy=new DirectProxy(args[0],(host,port)->{Socket socket=new Socket();socket.bind(new InetSocketAddress("127.0.0.1",0));socket.connect(new InetSocketAddress(host,port),3000);return socket;});
  Runtime.getRuntime().addShutdownHook(new Thread(proxy::close));System.out.println(proxy.base());Thread.currentThread().join();
 }
}
