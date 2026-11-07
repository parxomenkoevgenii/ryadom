package local.ryadom.chat;
public class AddressTest {
 public static void main(String[] args) throws Exception {
  String[][] valid={{"192.168.1.38:4280","http://192.168.1.38:4280"},{"10.0.0.4","http://10.0.0.4:4280"},{"http://172.16.2.9:5000/","http://172.16.2.9:5000"},{"169.254.1.2","http://169.254.1.2:4280"}};
  for(String[] v:valid)if(!MainActivity.validateServer(v[0]).equals(v[1]))throw new AssertionError(v[0]);
  String[] invalid={"https://192.168.1.2","8.8.8.8","example.com","127.0.0.1","192.168.1.500","192.168.1.2:70000","192.168.1.2:0","user@192.168.1.2","192.168.1.2/path","192.168.1.2?key=test","192.168.1.2#test","192.168.001.2","172.32.1.2"};
  for(String value:invalid){boolean rejected=false;try{MainActivity.validateServer(value);}catch(Exception e){rejected=true;}if(!rejected)throw new AssertionError("Accepted: "+value);}
  System.out.println("17 Android LAN address validation checks passed");
 }
}
