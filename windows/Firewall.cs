using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Management;
using System.Text;
using System.Windows.Forms;

namespace Ryadom {
 internal sealed class FirewallReport {
  public readonly List<string> ThirdParty=new List<string>();
  public bool WindowsEnabled;
  public bool WindowsRules;
  public bool PublicNetwork;
  public bool NeedsAttention {get {return ThirdParty.Count>0||(WindowsEnabled&&!WindowsRules)||PublicNetwork;}}
  public string Names {get {return string.Join(", ",ThirdParty);}}
  public override string ToString() {
   var text=new StringBuilder();
   text.AppendLine("Сторонний брандмауэр: "+(ThirdParty.Count>0?Names:"не обнаружен"));
   text.AppendLine("Брандмауэр Windows: "+(WindowsEnabled?"включён":"выключен"));
   text.AppendLine("Правила Рядом (входящие TCP "+FirewallAdvisor.ChatPort+" / UDP "+FirewallAdvisor.DiscoveryPort+"): "+(WindowsRules?"есть":"нет"));
   text.Append("Домашняя сеть: "+(PublicNetwork?"общедоступная (Public)":"частная"));
   return text.ToString();
  }
 }
 internal static class FirewallAdvisor {
  public const int ChatPort=4280;
  public const int DiscoveryPort=42800;
  private const string RuleTcp="Ryadom chat TCP 4280";
  private const string RuleUdp="Ryadom discovery UDP 42800";
  private static readonly string[] BuiltIn={"windows firewall","windows defender firewall","брандмауэр windows","брандмауэр защитника windows","брандмауэр microsoft defender"};
  // Windows Security Center keeps uninstalled products registered for a long time, so a product with a missing executable is ignored.
  public static FirewallReport Inspect() {
   var report=new FirewallReport();
   try {
    using(var searcher=new ManagementObjectSearcher(@"root\SecurityCenter2","SELECT displayName,pathToSignedProductExe FROM FirewallProduct"))
    using(var rows=searcher.Get())
    foreach(ManagementObject row in rows) {
     string name=(Convert.ToString(row["displayName"])??"").Trim();
     string path=Convert.ToString(row["pathToSignedProductExe"])??"";
     if(name.Length==0||BuiltIn.Any(x=>name.IndexOf(x,StringComparison.OrdinalIgnoreCase)>=0))continue;
     if(path.Length>0&&path.IndexOf("://",StringComparison.Ordinal)<0&&!File.Exists(path))continue;
     if(!report.ThirdParty.Contains(name))report.ThirdParty.Add(name);
    }
   } catch{}
   var profiles=new Dictionary<string,bool>();var categories=new List<uint>();
   try {
    using(var searcher=new ManagementObjectSearcher(@"root\StandardCimv2","SELECT Name,Enabled FROM MSFT_NetFirewallProfile"))
    using(var rows=searcher.Get())
    foreach(ManagementObject row in rows) {
     string profile=Convert.ToString(row["Name"])??"";
     if(profile.Length>0)profiles[profile]=Convert.ToUInt32(row["Enabled"])!=0;
    }
   } catch{}
   try {
    var lan=LanInterfaces.Read().Select(n=>n.Name).ToList();
    using(var searcher=new ManagementObjectSearcher(@"root\StandardCimv2","SELECT InterfaceAlias,NetworkCategory FROM MSFT_NetConnectionProfile"))
    using(var rows=searcher.Get())
    foreach(ManagementObject row in rows) {
     string alias=Convert.ToString(row["InterfaceAlias"])??"";
     if(lan.Contains(alias))categories.Add(Convert.ToUInt32(row["NetworkCategory"]));
    }
   } catch{}
   // Only the profile applied to the home LAN matters: a Domain profile switched on elsewhere does not filter a Public home network.
   if(categories.Count>0) {
    report.PublicNetwork=categories.Contains(0);
    report.WindowsEnabled=categories.Any(c=>{string name=c==2?"Domain":c==1?"Private":"Public";bool enabled;return profiles.TryGetValue(name,out enabled)&&enabled;});
   } else report.WindowsEnabled=profiles.Values.Any(x=>x);
   report.WindowsRules=RuleExists(RuleTcp);
   return report;
  }
  // Called once per application build, so the user is not asked again and again.
  public static void Advise(string dataDir) {
   FirewallReport report;
   try{report=Inspect();}catch{return;}
   string exe=Application.ExecutablePath;
   string marker=Path.Combine(dataDir,"firewall-state.json");
   string fingerprint;
   try{fingerprint=exe+"|"+new FileInfo(exe).Length+"|"+File.GetLastWriteTimeUtc(exe).Ticks;}
   catch{return;}
   try{if(File.Exists(marker)&&File.ReadAllText(marker).Trim()==fingerprint)return;}catch{}
   try {
    if(report.ThirdParty.Count>0) {
     string text="На компьютере работает сторонний брандмауэр: "+report.Names+"."+Environment.NewLine+Environment.NewLine+
      "Если телефон не может подключиться (ошибка «failed to connect … after 4500 ms», хотя поиск и пинг в сети работают), разрешите «Рядом» входящие подключения:"+Environment.NewLine+
      "• TCP "+ChatPort+" — чат"+Environment.NewLine+
      "• UDP "+DiscoveryPort+" — поиск в локальной сети"+Environment.NewLine+
      "в настройках "+report.Names+" (раздел «Сеть»/«Брандмауэр») или пометьте домашнюю сеть как доверенную."+Environment.NewLine+Environment.NewLine+
      "Правила брандмауэра Windows в этом случае не действуют: входящие соединения фильтрует "+report.Names+"."+Environment.NewLine+Environment.NewLine+
      "Приложение: "+exe;
     if(report.PublicNetwork)text+=Environment.NewLine+Environment.NewLine+
      "Сеть сейчас считается общедоступной — брандмауэры по умолчанию блокируют входящие. Параметры Windows → Сеть и Интернет → Ethernet → свойства сети → «Частная».";
     MessageBox.Show(text,"Рядом · брандмауэр",MessageBoxButtons.OK,MessageBoxIcon.Warning);
    } else if(report.WindowsEnabled&&!report.WindowsRules) {
     var answer=MessageBox.Show("Брандмауэр Windows включён и пока не пропускает входящие подключения к чату."+Environment.NewLine+Environment.NewLine+
      "Добавить правила для этого приложения?"+Environment.NewLine+
      "• TCP "+ChatPort+" — чат"+Environment.NewLine+
      "• UDP "+DiscoveryPort+" — поиск в локальной сети"+Environment.NewLine+
      "Только из локальной сети, потребуется подтверждение администратора.",
      "Рядом · брандмауэр",MessageBoxButtons.YesNo,MessageBoxIcon.Question);
     if(answer==DialogResult.Yes&&!InstallRules(exe))MessageBox.Show("Не удалось добавить правила. Разрешите «Рядом» вручную: входящие TCP "+ChatPort+" и UDP "+DiscoveryPort+" в брандмауэре Windows.","Рядом",MessageBoxButtons.OK,MessageBoxIcon.Warning);
    }
   } finally{try{File.WriteAllText(marker,fingerprint,new UTF8Encoding(false));}catch{}}
  }
  public static void ShowReport() {
   FirewallReport report;
   try{report=Inspect();}catch(Exception e){MessageBox.Show("Не удалось проверить брандмауэр: "+e.Message,"Рядом",MessageBoxButtons.OK,MessageBoxIcon.Warning);return;}
   string advice;
   if(report.ThirdParty.Count>0)advice="Входящие подключения фильтрует "+report.Names+": добавьте правила TCP "+ChatPort+" и UDP "+DiscoveryPort+" в его настройках и пометьте домашнюю сеть как доверенную. Правила брандмауэра Windows при этом не действуют.";
   else if(report.WindowsEnabled&&!report.WindowsRules)advice="Правила можно добавить при следующем запуске в режиме главного устройства — приложение предложит это само.";
   else advice="Входящие подключения разрешены: правила TCP "+ChatPort+" и UDP "+DiscoveryPort+" есть.";
   MessageBox.Show(report.ToString()+Environment.NewLine+Environment.NewLine+advice+Environment.NewLine+Environment.NewLine+"Файл приложения: "+Application.ExecutablePath,"Рядом · брандмауэр и сеть",MessageBoxButtons.OK,report.NeedsAttention?MessageBoxIcon.Warning:MessageBoxIcon.Information);
  }
  // Rules are written by an elevated PowerShell instance (one UAC prompt for both rules).
  public static bool InstallRules(string exePath) {
   string quote="'"+exePath.Replace("'","''")+"'";
   string script="netsh advfirewall firewall delete rule name='"+RuleTcp+"' | Out-Null; "+
    "netsh advfirewall firewall delete rule name='"+RuleUdp+"' | Out-Null; "+
    "netsh advfirewall firewall add rule name='"+RuleTcp+"' dir=in action=allow program="+quote+" protocol=TCP localport="+ChatPort+" profile=any remoteip=LocalSubnet enable=yes | Out-Null; "+
    "netsh advfirewall firewall add rule name='"+RuleUdp+"' dir=in action=allow program="+quote+" protocol=UDP localport="+DiscoveryPort+" profile=any remoteip=LocalSubnet enable=yes | Out-Null";
   try {
    var start=new ProcessStartInfo("powershell.exe","-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand "+Convert.ToBase64String(Encoding.Unicode.GetBytes(script))){UseShellExecute=true,Verb="runas",WindowStyle=ProcessWindowStyle.Hidden};
    using(var process=Process.Start(start)){if(process==null)return false;process.WaitForExit(120000);}
   } catch{return false;}
   return RuleExists(RuleTcp);
  }
  private static bool RuleExists(string name) {
   try{int exit;Run("netsh.exe","advfirewall firewall show rule name=\""+name+"\"",out exit);return exit==0;}catch{return false;}
  }
  private static string Run(string file,string arguments,out int exit) {
   exit=-1;
   var start=new ProcessStartInfo(file,arguments){UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true,CreateNoWindow=true};
   using(var process=Process.Start(start)) {
    string output=process.StandardOutput.ReadToEnd()+process.StandardError.ReadToEnd();
    if(!process.WaitForExit(15000)){try{process.Kill();}catch{}return output;}
    exit=process.ExitCode;return output;
   }
  }
 }
}
