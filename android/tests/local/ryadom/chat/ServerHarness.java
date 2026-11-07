package local.ryadom.chat;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
public final class ServerHarness {
 public static void main(String[] args) throws Exception {
  int port=Integer.parseInt(System.getenv("RYADOM_PORT"));File data=new File(System.getenv("RYADOM_DATA"));
  ChatHost host=new ChatHost(data,Collections.emptyList(),port,name->Files.readAllBytes(Paths.get(args[0],name)),()->System.exit(0));
  Runtime.getRuntime().addShutdownHook(new Thread(host::close));System.out.println("Ready");Thread.currentThread().join();
 }
}
