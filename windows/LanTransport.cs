using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Management;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Ryadom {
 internal sealed class LanInterface {
  public string Name, Address, Mask;
  public int Index;
  public bool Contains(string ip) {
   IPAddress peer;if(!IPAddress.TryParse(ip,out peer)||peer.AddressFamily!=AddressFamily.InterNetwork)return false;
   byte[] a=IPAddress.Parse(Address).GetAddressBytes(),b=peer.GetAddressBytes(),m=IPAddress.Parse(Mask).GetAddressBytes();
   for(int i=0;i<4;i++)if((a[i]&m[i])!=(b[i]&m[i]))return false;return true;
  }
  public string Broadcast {get {byte[] a=IPAddress.Parse(Address).GetAddressBytes(),m=IPAddress.Parse(Mask).GetAddressBytes();for(int i=0;i<4;i++)a[i]=(byte)(a[i]|~m[i]);return new IPAddress(a).ToString();}}
  public override string ToString(){return Name+" · "+Address;}
 }
 internal static class LanInterfaces {
  public static bool Private(string ip) {IPAddress a;if(!IPAddress.TryParse(ip,out a)||a.AddressFamily!=AddressFamily.InterNetwork)return false;var b=a.GetAddressBytes();return b[0]==10||(b[0]==172&&b[1]>=16&&b[1]<=31)||(b[0]==192&&b[1]==168)||(b[0]==169&&b[1]==254);}
  public static List<LanInterface> Read() {
   // Win32_NetworkAdapter.PhysicalAdapter incorrectly identifies some TUN drivers as physical.
   var hardware=new HashSet<int>();
   using(var query=new ManagementObjectSearcher(@"root\StandardCimv2","SELECT InterfaceIndex FROM MSFT_NetAdapter WHERE HardwareInterface = TRUE"))
   using(var rows=query.Get())foreach(ManagementObject row in rows)hardware.Add(Convert.ToInt32(row["InterfaceIndex"]));
   var result=new List<LanInterface>();
   foreach(var nic in NetworkInterface.GetAllNetworkInterfaces()) {
    if(nic.OperationalStatus!=OperationalStatus.Up)continue;
    if(nic.NetworkInterfaceType!=NetworkInterfaceType.Ethernet&&nic.NetworkInterfaceType!=NetworkInterfaceType.Wireless80211)continue;
    var properties=nic.GetIPProperties();var ipv4=properties.GetIPv4Properties();if(ipv4==null||!hardware.Contains(ipv4.Index))continue;
    foreach(var u in properties.UnicastAddresses)if(u.Address.AddressFamily==AddressFamily.InterNetwork&&Private(u.Address.ToString())&&u.IPv4Mask!=null)
     result.Add(new LanInterface{Name=nic.Name,Address=u.Address.ToString(),Mask=u.IPv4Mask.ToString(),Index=ipv4.Index});
   }
   return result;
  }
  public static void BindOutput(Socket socket,int index) {
   socket.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31,IPAddress.HostToNetworkOrder(index));
  }
  public static TcpClient Connect(LanInterface nic,string target,int port) {
   if(!nic.Contains(target))throw new IOException("Устройство находится в другой подсети. Проверьте домашнюю сеть роутера.");
   var client=new TcpClient(AddressFamily.InterNetwork);
   try{BindOutput(client.Client,nic.Index);client.Client.Bind(new IPEndPoint(IPAddress.Parse(nic.Address),0));var connecting=client.ConnectAsync(target,port);if(!connecting.Wait(5000))throw new IOException("Нет ответа от главного устройства.");client.NoDelay=true;return client;}catch{client.Close();throw;}
  }
 }
 internal sealed class TcpBridge : IDisposable {
  private readonly TcpListener listener;
  private readonly Func<TcpClient,TcpClient> connect;
  private readonly Func<TcpClient,byte[]> preface;
  private readonly Action<TcpClient> configure;
  private readonly HashSet<TcpClient> sockets=new HashSet<TcpClient>();
  private volatile bool disposed;
  public int Port {get{return ((IPEndPoint)listener.LocalEndpoint).Port;}}
  public TcpBridge(string address,int port,Func<TcpClient,TcpClient> factory,Func<TcpClient,byte[]> header,Action<TcpClient> config,Action<Socket> listenerConfig=null) {
   connect=factory;preface=header;configure=config;listener=new TcpListener(IPAddress.Parse(address),port);listener.Server.ExclusiveAddressUse=true;if(listenerConfig!=null)listenerConfig(listener.Server);listener.Start(64);Task.Run((Func<Task>)Accept);
  }
  private async Task Accept(){while(!disposed){TcpClient incoming;try{incoming=await listener.AcceptTcpClientAsync();}catch{return;}lock(sockets){if(sockets.Count>=128){incoming.Close();continue;}sockets.Add(incoming);}Task.Run(()=>Relay(incoming));}}
  private async Task Relay(TcpClient incoming){TcpClient remote=null;try{configure(incoming);incoming.NoDelay=true;remote=connect(incoming);lock(sockets)sockets.Add(remote);byte[] prefix=preface(incoming);if(prefix.Length>0)await remote.GetStream().WriteAsync(prefix,0,prefix.Length);Task a=incoming.GetStream().CopyToAsync(remote.GetStream()),b=remote.GetStream().CopyToAsync(incoming.GetStream());await Task.WhenAny(a,b);}catch{}finally{incoming.Close();if(remote!=null)remote.Close();lock(sockets){sockets.Remove(incoming);if(remote!=null)sockets.Remove(remote);}}}
  public void Dispose(){disposed=true;listener.Stop();lock(sockets){foreach(var s in sockets)s.Close();sockets.Clear();}}
 }
 internal sealed class FoundChat {
  public string Name,Address;public int Port;
  public override string ToString(){return Name+" — "+Address+":"+Port;}
 }
 internal sealed class LanDiscovery : IDisposable {
  public const int Port=42800;
  private UdpClient socket;private readonly List<UdpClient> senders=new List<UdpClient>();private volatile bool stopped;
  private readonly List<LanInterface> interfaces;private readonly string name;
  public LanDiscovery(List<LanInterface> nics,string deviceName) {
   interfaces=nics;name=Convert.ToBase64String(Encoding.UTF8.GetBytes(deviceName));
   socket=new UdpClient(AddressFamily.InterNetwork);socket.Client.SetSocketOption(SocketOptionLevel.Socket,SocketOptionName.ReuseAddress,true);socket.Client.Bind(new IPEndPoint(IPAddress.Any,Port));
   foreach(var nic in nics){try{socket.JoinMulticastGroup(IPAddress.Parse("239.255.42.80"),IPAddress.Parse(nic.Address));}catch{}}
   Task.Run((Action)Listen);
  }
  private void Listen(){while(!stopped){try{IPEndPoint peer=new IPEndPoint(IPAddress.Any,0);byte[] bytes=socket.Receive(ref peer);string request=Encoding.ASCII.GetString(bytes);if(!request.StartsWith("RYADOM_FIND_V1\t")||request.Length>100)continue;string nonce=request.Substring(15);if(nonce.Length!=32||!nonce.All(Uri.IsHexDigit))continue;var nic=interfaces.FirstOrDefault(n=>n.Contains(peer.Address.ToString()));if(nic==null)continue;byte[] answer=Encoding.UTF8.GetBytes("RYADOM_HERE_V1\t"+nonce+"\t"+nic.Address+"\t4280\t"+name);using(var sender=new UdpClient(new IPEndPoint(IPAddress.Parse(nic.Address),0))){LanInterfaces.BindOutput(sender.Client,nic.Index);sender.Send(answer,answer.Length,peer);}}catch{if(stopped)return;}}}
  public static List<FoundChat> Find(List<LanInterface> nics) {
   var found=new Dictionary<string,FoundChat>();var tasks=nics.Select(nic=>Task.Run(()=>{
    try{using(var udp=new UdpClient(new IPEndPoint(IPAddress.Parse(nic.Address),0))){LanInterfaces.BindOutput(udp.Client,nic.Index);udp.EnableBroadcast=true;udp.Client.ReceiveTimeout=400;udp.Client.SetSocketOption(SocketOptionLevel.IP,SocketOptionName.MulticastInterface,IPAddress.HostToNetworkOrder(nic.Index));string nonce=Guid.NewGuid().ToString("N");byte[] request=Encoding.ASCII.GetBytes("RYADOM_FIND_V1\t"+nonce);udp.Send(request,request.Length,new IPEndPoint(IPAddress.Parse(nic.Broadcast),Port));udp.Send(request,request.Length,new IPEndPoint(IPAddress.Parse("239.255.42.80"),Port));DateTime until=DateTime.UtcNow.AddSeconds(2);while(DateTime.UtcNow<until){try{IPEndPoint peer=new IPEndPoint(IPAddress.Any,0);string[] parts=Encoding.UTF8.GetString(udp.Receive(ref peer)).Split('\t');int port;if(parts.Length!=5||parts[0]!="RYADOM_HERE_V1"||parts[1]!=nonce||parts[2]!=peer.Address.ToString()||!nic.Contains(parts[2])||!int.TryParse(parts[3],out port)||port<1||port>65535)continue;var chat=new FoundChat{Name=Encoding.UTF8.GetString(Convert.FromBase64String(parts[4])),Address=parts[2],Port=port};if(chat.Name.Length>80)continue;lock(found)found[chat.Address+":"+port]=chat;}catch(SocketException){}}}}catch{}
   })).ToArray();Task.WaitAll(tasks);return found.Values.ToList();
  }
  public void Dispose(){stopped=true;if(socket!=null)socket.Close();foreach(var sender in senders)sender.Close();}
 }
}
