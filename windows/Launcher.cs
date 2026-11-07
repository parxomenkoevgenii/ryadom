using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace Ryadom {
 internal sealed class ChatApplication : ApplicationContext {
  private static readonly string Data=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Ryadom");
  private static bool chooseAgain;
  private readonly NotifyIcon tray;
  private readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer();
  private readonly List<TcpBridge> bridges=new List<TcpBridge>();
  private readonly string bridgeKey=Guid.NewGuid().ToString("N")+Guid.NewGuid().ToString("N");
  private readonly RolePicker choice;
  private LanDiscovery discovery;
  private Process host;
  private int tries;
  private bool stopping,ready,firewallChecked;
  private string networkStamp=null;
  private const string BaseUrl="http://127.0.0.1:4280/";
  [STAThread] private static void Main(string[] args) {
   Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Directory.CreateDirectory(Data);
   if(args.Length>0&&args[0]=="--firewall-check") {
    string text;try{text=FirewallAdvisor.Inspect().ToString();}catch(Exception e){text="Не удалось проверить брандмауэр: "+e.Message;}text+=Environment.NewLine+"Приложение: "+Application.ExecutablePath;
    if(args.Length>1)File.WriteAllText(args[1],text,new UTF8Encoding(false));else MessageBox.Show(text,"Рядом · брандмауэр и сеть");
    return;
   }
   bool first;
   using(var mutex=new Mutex(true,"Local\\Ryadom.PersonalChat",out first)) {
    if(!first){OpenChat(false);return;}
    do {chooseAgain=false;try{using(var picker=new RolePicker(File.Exists(Path.Combine(Data,"last-target"))?File.ReadAllText(Path.Combine(Data,"last-target")):"")){if(picker.ShowDialog()!=DialogResult.OK)return;using(var app=new ChatApplication(picker))Application.Run(app);}}catch(Exception e){MessageBox.Show("Не удалось запустить Рядом.\n\n"+e.Message,"Рядом",MessageBoxButtons.OK,MessageBoxIcon.Error);}}while(chooseAgain);
   }
  }
  private ChatApplication(RolePicker picker) {
   choice=picker;File.WriteAllText(Path.Combine(Data,"desktop-role"),choice.Hosting?"host":"client");if(!choice.Hosting)File.WriteAllText(Path.Combine(Data,"last-target"),choice.Target+":"+choice.TargetPort);
   File.Delete(Path.Combine(Data,"choose-role"));
   tray=new NotifyIcon{Icon=Icon.ExtractAssociatedIcon(Application.ExecutablePath),Text=choice.Hosting?"Рядом · главное устройство":"Рядом · подключён к чату"};
   var menu=new ContextMenuStrip();menu.Items.Add("Открыть чат",null,(s,e)=>OpenChat(false));if(choice.Hosting){menu.Items.Add("Подключить устройство",null,(s,e)=>OpenChat(true));menu.Items.Add("Проверить брандмауэр и сеть",null,(s,e)=>FirewallAdvisor.ShowReport());}
   menu.Items.Add("Выбрать другое главное устройство",null,(s,e)=>{if(!choice.Hosting||MessageBox.Show("Остановить этот чат и выбрать главное устройство заново? История останется на этом компьютере.","Рядом",MessageBoxButtons.OKCancel)==DialogResult.OK)Stop(true);});
   menu.Items.Add(new ToolStripSeparator());menu.Items.Add("Папка с данными",null,(s,e)=>Process.Start("explorer.exe",Data));menu.Items.Add("Выключить Рядом",null,(s,e)=>Stop(false));tray.ContextMenuStrip=menu;tray.DoubleClick+=(s,e)=>OpenChat(false);tray.Visible=true;
   string bin=Path.Combine(Data,"bin");Directory.CreateDirectory(bin);string digest;using(var reader=new StreamReader(Assembly.GetExecutingAssembly().GetManifestResourceStream("HostHash")))digest=reader.ReadToEnd().Trim();
   string executable=Path.Combine(bin,"RyadomHost-"+digest.Substring(0,12)+".exe");
   if(!File.Exists(executable)){string temp=executable+".tmp";using(var input=Assembly.GetExecutingAssembly().GetManifestResourceStream("Host"))using(var output=File.Create(temp))input.CopyTo(output);File.Move(temp,executable);}
   var start=new ProcessStartInfo(executable){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Data};
   start.EnvironmentVariables["RYADOM_DATA"]=Data;start.EnvironmentVariables["RYADOM_PORT"]="4280";start.EnvironmentVariables["RYADOM_NO_OPEN"]="1";start.EnvironmentVariables["RYADOM_PARENT_PID"]=Process.GetCurrentProcess().Id.ToString();start.EnvironmentVariables["RYADOM_NATIVE_KEY"]=bridgeKey;start.EnvironmentVariables["RYADOM_LAN_STATE"]=Path.Combine(Data,"lan-state.json");start.EnvironmentVariables["RYADOM_ROLE_SIGNAL"]=Path.Combine(Data,"choose-role");
   if(choice.Hosting){start.EnvironmentVariables["RYADOM_BRIDGE_PORT"]="4282";RefreshHostNetworks(choice.Networks);}
   else {
    var tunnel=new TcpBridge("127.0.0.1",0,c=>{var live=LanInterfaces.Read().FirstOrDefault(n=>n.Contains(choice.Target));if(live==null)throw new IOException("Нет локальной сети");return LanInterfaces.Connect(live,choice.Target,choice.TargetPort);},c=>new byte[0],c=>{});bridges.Add(tunnel);
    start.EnvironmentVariables["RYADOM_ROLE"]="client";start.EnvironmentVariables["RYADOM_TARGET"]="http://"+choice.Target+":"+choice.TargetPort;start.EnvironmentVariables["RYADOM_TUNNEL_PORT"]=tunnel.Port.ToString();
   }
   host=Process.Start(start);timer.Interval=400;timer.Tick+=Tick;timer.Start();
  }
  private void RefreshHostNetworks(List<LanInterface> nics) {
   string stamp=string.Join(";",nics.Select(n=>n.Index+":"+n.Address+":"+n.Mask));if(networkStamp==stamp&&File.Exists(Path.Combine(Data,"lan-state.json")))return;
   foreach(var b in bridges)b.Dispose();bridges.Clear();if(discovery!=null){discovery.Dispose();discovery=null;}
   var active=new List<string>();
   foreach(var nic in nics){try{var local=nic;bridges.Add(new TcpBridge(local.Address,4280,c=>new TcpClient("127.0.0.1",4282),c=>Encoding.ASCII.GetBytes("RYADOM/1 "+bridgeKey+" "+((IPEndPoint)c.Client.RemoteEndPoint).Address+" "+local.Address+"\r\n"),c=>{string peer=((IPEndPoint)c.Client.RemoteEndPoint).Address.ToString();if(!LanInterfaces.Private(peer)||!local.Contains(peer))throw new IOException("Другая подсеть");LanInterfaces.BindOutput(c.Client,local.Index);},socket=>LanInterfaces.BindOutput(socket,local.Index)));active.Add(local.Address);}catch(Exception e){File.AppendAllText(Path.Combine(Data,"network.log"),DateTime.Now+" "+nic.Address+" "+e.Message+Environment.NewLine);}}
   File.WriteAllText(Path.Combine(Data,"lan-state.json"),"{\"addresses\":["+string.Join(",",active.Select(a=>"\""+a+"\""))+"],\"mode\":\"physical-interface\"}",new UTF8Encoding(false));
   if(active.Count>0)try{discovery=new LanDiscovery(nics.Where(n=>active.Contains(n.Address)).ToList(),Environment.MachineName);}catch{}
   networkStamp=stamp;
  }
  private void Tick(object sender,EventArgs args) {
   if(stopping)return;if(File.Exists(Path.Combine(Data,"choose-role"))){File.Delete(Path.Combine(Data,"choose-role"));Stop(true);return;}
   if(ready){if(host.HasExited){Stop(false);return;}if(choice.Hosting){if(!firewallChecked){firewallChecked=true;FirewallAdvisor.Advise(Data);}try{RefreshHostNetworks(LanInterfaces.Read());}catch{}}return;}
   tries++;try{var request=(HttpWebRequest)WebRequest.Create(BaseUrl+"api/local/status");request.Proxy=null;request.Timeout=300;using(var response=request.GetResponse()){}ready=true;timer.Interval=4000;OpenChat(false);}catch{if(tries>=35||host.HasExited){MessageBox.Show("Не удалось запустить Рядом: порт 4280 занят или локальный доступ заблокирован. Повторите выбор главного устройства.","Рядом");Stop(true);}}
  }
  private static void OpenChat(bool connect) {
   bool hosting=File.Exists(Path.Combine(Data,"desktop-role"))&&File.ReadAllText(Path.Combine(Data,"desktop-role"))=="host";string url=BaseUrl+(connect?"?connect=1":"");string keyFile=Path.Combine(Data,"host-key");if(hosting&&File.Exists(keyFile))url+="#host="+File.ReadAllText(keyFile).Trim();
   string edge=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),"Microsoft","Edge","Application","msedge.exe"),chrome=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"Google","Chrome","Application","chrome.exe");string browser=File.Exists(edge)?edge:File.Exists(chrome)?chrome:null;
   try{if(browser!=null)Process.Start(new ProcessStartInfo(browser,"--user-data-dir=\""+Path.Combine(Data,"browser")+"\" --no-proxy-server --disable-background-networking --no-first-run --app=\""+url+"\" --window-size=1180,820"){UseShellExecute=true});else Process.Start(url);}catch(Exception e){MessageBox.Show(e.Message,"Рядом");}
  }
  private void Stop(bool changeRole) {
   if(stopping)return;stopping=true;chooseAgain=changeRole;timer.Stop();
   try{var req=(HttpWebRequest)WebRequest.Create(BaseUrl+"api/local/quit");req.Proxy=null;req.Timeout=1500;req.Method="POST";req.Headers["X-Ryadom"]="1";req.Headers["X-Ryadom-Native"]=bridgeKey;req.ContentLength=0;using(var response=req.GetResponse()){}if(host!=null&&!host.HasExited&&!host.WaitForExit(2500))host.Kill();}catch{try{if(host!=null&&!host.HasExited)host.Kill();}catch{}}
   foreach(var b in bridges)b.Dispose();bridges.Clear();if(discovery!=null)discovery.Dispose();tray.Visible=false;ExitThread();
  }
  protected override void Dispose(bool disposing){if(disposing){if(!stopping)Stop(false);timer.Dispose();tray.Dispose();if(host!=null)host.Dispose();}base.Dispose(disposing);}
 }
}
