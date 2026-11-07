using System;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Ryadom;
class TransportTest {
 static void Main() {
  var nics=LanInterfaces.Read();if(nics.Count==0)throw new Exception("No physical network for testing");
  foreach(var n in nics)Console.WriteLine("Physical: "+n+" index="+n.Index);
  if(nics.Any(n=>n.Name.ToLower().Contains("happ")))throw new Exception("VPN was selected");
  var nic=nics[0];
  using(var destination=new TcpBridge(nic.Address,0,c=>{throw new Exception();},c=>new byte[0],c=>{},s=>LanInterfaces.BindOutput(s,nic.Index))){}
  var listener=new TcpListener(IPAddress.Parse(nic.Address),0);LanInterfaces.BindOutput(listener.Server,nic.Index);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;
  var service=Task.Run(()=>{using(var c=listener.AcceptTcpClient()){LanInterfaces.BindOutput(c.Client,nic.Index);var buffer=new byte[4096];int count=c.GetStream().Read(buffer,0,buffer.Length);c.GetStream().Write(buffer,0,count);}});
  using(var tunnel=new TcpBridge("127.0.0.1",0,c=>LanInterfaces.Connect(nic,nic.Address,port),c=>new byte[0],c=>{}))using(var client=new TcpClient("127.0.0.1",tunnel.Port)){client.ReceiveTimeout=5000;byte[] sent=Encoding.UTF8.GetBytes("Local traffic through physical NIC, even with Happ active");client.GetStream().Write(sent,0,sent.Length);byte[] got=new byte[4096];int n=client.GetStream().Read(got,0,got.Length);if(Encoding.UTF8.GetString(got,0,n)!=Encoding.UTF8.GetString(sent))throw new Exception("Transport mismatch");}
  listener.Stop();service.Wait();Console.WriteLine("Physical-interface binding + TCP transfer: PASS");
  using(var discovery=new LanDiscovery(nics,"Ryadom test")){var found=LanDiscovery.Find(nics);if(!found.Any(c=>c.Name=="Ryadom test"))throw new Exception("Discovery failed");Console.WriteLine("LAN broadcast/multicast discovery: PASS");}
 }
}
