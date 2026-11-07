using System;
using System.Windows.Forms;
using Ryadom;
class RolePickerPreview {
 [STAThread] static void Main(){Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);using(var picker=new RolePicker("")){var result=picker.ShowDialog();if(result==DialogResult.OK)Console.WriteLine(picker.Hosting?"ROLE=HOST":"ROLE=CLIENT;TARGET="+picker.Target+":"+picker.TargetPort);}}
}
