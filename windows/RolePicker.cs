using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Net;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Ryadom {
 internal sealed class RolePicker : Form {
  public bool Hosting;public string Target="";public int TargetPort=4280;public LanInterface Network;
  public List<LanInterface> Networks;
  private readonly TextBox address=new TextBox();private readonly ListBox chats=new ListBox();private readonly Label status=new Label();private readonly Button search=new Button();
  public RolePicker(string previous) {
   Text="Рядом 0.1 · выбор главного устройства";ClientSize=new Size(525,550);MinimumSize=new Size(540,580);StartPosition=FormStartPosition.CenterScreen;BackColor=Color.FromArgb(245,248,242);Font=new Font("Segoe UI",10);FormBorderStyle=FormBorderStyle.FixedDialog;MaximizeBox=false;
   var title=new Label{Text="Кто сегодня главный?",Font=new Font("Segoe UI",23),ForeColor=Color.FromArgb(35,76,49),Bounds=new Rectangle(30,25,465,45)};Controls.Add(title);
   Controls.Add(new Label{Text="Создайте чат на этом устройстве или выберите уже запущенный.\nПровод и Wi-Fi подходят: нужен один домашний роутер.",Bounds=new Rectangle(32,80,465,50),ForeColor=Color.FromArgb(100,121,91)});
   var create=new Button{Text="Создать чат на этом компьютере",Bounds=new Rectangle(32,145,460,48),BackColor=Color.FromArgb(23,107,86),ForeColor=Color.White,FlatStyle=FlatStyle.Flat};Controls.Add(create);
   create.Click+=(s,e)=>{try{Networks=LanInterfaces.Read();Hosting=true;DialogResult=DialogResult.OK;}catch(Exception ex){status.Text="Не удалось определить сетевой адаптер: "+ex.Message;}};
   Controls.Add(new Label{Text="ИЛИ ПОДКЛЮЧИТЬСЯ",Bounds=new Rectangle(32,215,310,25),Font=new Font("Segoe UI",9),ForeColor=Color.Gray});
   search.Text="Найти в сети";search.Bounds=new Rectangle(357,207,135,34);Controls.Add(search);search.Click+=async(s,e)=>await Search();
   chats.Bounds=new Rectangle(32,250,460,100);chats.BorderStyle=BorderStyle.FixedSingle;Controls.Add(chats);chats.SelectedIndexChanged+=(s,e)=>{var found=chats.SelectedItem as FoundChat;if(found!=null)address.Text=found.Address+":"+found.Port;};
   address.Bounds=new Rectangle(32,367,305,30);address.Text=previous;Controls.Add(address);var join=new Button{Text="Подключиться",Bounds=new Rectangle(348,364,144,36)};Controls.Add(join);join.Click+=(s,e)=>Join();
   status.Bounds=new Rectangle(32,412,460,65);status.ForeColor=Color.FromArgb(130,102,78);Controls.Add(status);
   Controls.Add(new Label{Text="История хранится на выбранном главном устройстве.\nСмена главного не переносит историю автоматически.\nHapp может оставаться включённым.",Bounds=new Rectangle(32,478,465,65),ForeColor=Color.Gray,Font=new Font("Segoe UI",9)});
   Shown+=async(s,e)=>await Search();AcceptButton=join;
  }
  private async Task Search(){search.Enabled=false;status.Text="Ищем чаты через Wi-Fi и Ethernet…";try{var found=await Task.Run(()=>{Networks=LanInterfaces.Read();return LanDiscovery.Find(Networks);});if(IsDisposed)return;chats.Items.Clear();foreach(var item in found)chats.Items.Add(item);status.Text=found.Count>0?"Выберите устройство или введите его адрес вручную.":"Чаты не найдены. Создайте чат на другом устройстве или введите его адрес. Проверьте изоляцию клиентов / гостевую сеть роутера.";}catch(Exception e){if(!IsDisposed)status.Text=e.Message;}finally{if(!IsDisposed)search.Enabled=true;}}
  private void Join(){try{Uri uri;string input=address.Text.Trim();if(!input.StartsWith("http://"))input="http://"+input;if(!Uri.TryCreate(input,UriKind.Absolute,out uri)||uri.Scheme!="http"||uri.UserInfo!=""||uri.Query!=""||uri.Fragment!=""||uri.AbsolutePath!="/"||!LanInterfaces.Private(uri.Host))throw new Exception("Введите локальный адрес, например 192.168.1.38:4280.");Networks=LanInterfaces.Read();Network=Networks.FirstOrDefault(n=>n.Contains(uri.Host));if(Network==null)throw new Exception("Адрес не относится к вашей проводной или Wi-Fi подсети. Используйте домашний адрес, не адрес Happ.");Target=uri.Host;TargetPort=uri.IsDefaultPort?4280:uri.Port;Hosting=false;DialogResult=DialogResult.OK;}catch(Exception e){status.Text=e.Message;}}
 }
}
