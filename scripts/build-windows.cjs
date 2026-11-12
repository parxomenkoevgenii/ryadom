'use strict';
const fs=require('node:fs');const path=require('node:path');const {execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');process.chdir(root);
fs.mkdirSync('dist',{recursive:true});fs.mkdirSync('.build',{recursive:true});
const assets=Object.fromEntries(['index.html','style.css','app.js','icon.svg'].map(name=>[name,path.join(root,'public',name)]));
const config={main:path.join(root,'server.cjs'),output:path.join(root,'.build','RyadomHost.exe'),disableExperimentalSEAWarning:true,useCodeCache:false,execArgvExtension:'none',assets};
fs.writeFileSync('.build/sea-config.json',JSON.stringify(config,null,2));
execFileSync(process.execPath,['--build-sea','.build/sea-config.json'],{stdio:'inherit'});
fs.writeFileSync('.build/host.sha256',require('node:crypto').createHash('sha256').update(fs.readFileSync('.build/RyadomHost.exe')).digest('hex'));
execFileSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File','scripts/create-icon.ps1'],{stdio:'inherit'});
const staging=path.join(require('node:os').tmpdir(),'RyadomWindowsBuild');fs.mkdirSync(staging,{recursive:true});
for(const source of ['.build/RyadomHost.exe','.build/host.sha256','.build/ryadom.ico','windows/Launcher.cs','windows/AssemblyInfo.cs','windows/LanTransport.cs','windows/RolePicker.cs','windows/Firewall.cs'])fs.copyFileSync(source,path.join(staging,path.basename(source)));
execFileSync('C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe',['/nologo','/codepage:65001','/target:winexe','/optimize+','/out:Ryadom-Windows.exe','/win32icon:ryadom.ico','/reference:System.Windows.Forms.dll','/reference:System.Drawing.dll','/reference:System.Management.dll','/resource:RyadomHost.exe,Host','/resource:host.sha256,HostHash','Launcher.cs','AssemblyInfo.cs','LanTransport.cs','RolePicker.cs','Firewall.cs'],{stdio:'inherit',cwd:staging});
fs.copyFileSync(path.join(staging,'Ryadom-Windows.exe'),'dist/Ryadom-Windows.exe');
if(process.env.RYADOM_SIGN_RELEASE==='1'){execFileSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File','scripts/sign-release.ps1','-DistDir','dist'],{stdio:'inherit'});}
else{console.log('Совет: для подписи релиза выполните scripts/sign-release.ps1 или сборку с RYADOM_SIGN_RELEASE=1.');}
console.log('Готово: dist/Ryadom-Windows.exe');
