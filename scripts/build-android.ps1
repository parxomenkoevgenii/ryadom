param(
 [string]$SdkPath = 'C:\Program Files (x86)\Android\android-sdk',
 [string]$JdkPath = 'C:\Program Files\Android\openjdk\jdk-21.0.8',
 [string]$SigningDir = $env:RYADOM_SIGNING_DIR,
 [switch]$CreateSigningKey
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $env:TEMP 'RyadomAndroidBuild'
$BuildTools = Join-Path $SdkPath 'build-tools\36.0.0'
$AndroidJar = Join-Path $SdkPath 'platforms\android-35\android.jar'
$JavaBin = Join-Path $JdkPath 'bin'
$SignDir = if ($SigningDir) { $SigningDir } else { Join-Path $env:LOCALAPPDATA 'RyadomSigning' }
if ($SignDir -match 'OneDrive|Dropbox|Google Drive|iCloud') { Write-Warning "Каталог подписи находится в синхронизируемой папке: $SignDir. Перенесите ключ в защищённое место." }
if (-not (Test-Path -LiteralPath $SignDir)) { New-Item -ItemType Directory -Path $SignDir -Force | Out-Null }
$DistDir = Join-Path $ProjectRoot 'dist'
foreach ($leaf in @('classes','gen','dex','src','res','assets')) {
 $Target = [IO.Path]::GetFullPath((Join-Path $BuildRoot $leaf))
 if (-not $Target.StartsWith([IO.Path]::GetFullPath($BuildRoot) + [IO.Path]::DirectorySeparatorChar)) { throw 'Invalid build cleanup path' }
 if (Test-Path -LiteralPath $Target) { Remove-Item -LiteralPath $Target -Recurse -Force }
}
foreach ($dir in @($BuildRoot, "$BuildRoot\classes", "$BuildRoot\gen", "$BuildRoot\dex", "$BuildRoot\assets", $SignDir, $DistDir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
Copy-Item -LiteralPath "$ProjectRoot\android\res" -Destination "$BuildRoot" -Recurse -Force
Copy-Item -LiteralPath "$ProjectRoot\android\src" -Destination "$BuildRoot" -Recurse -Force
Copy-Item -LiteralPath "$ProjectRoot\android\AndroidManifest.xml" -Destination "$BuildRoot\AndroidManifest.xml" -Force
foreach($asset in @('index.html','app.js','style.css','icon.svg')) { Copy-Item -LiteralPath "$ProjectRoot\public\$asset" -Destination "$BuildRoot\assets\$asset" -Force }
function Invoke-Checked([string]$Exe, [string[]]$Arguments) { & $Exe @Arguments; if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Exe" } }
Invoke-Checked "$BuildTools\aapt2.exe" @('compile','--dir',"$BuildRoot\res",'-o',"$BuildRoot\resources.zip")
Invoke-Checked "$BuildTools\aapt2.exe" @('link','-o',"$BuildRoot\base.apk",'-I',$AndroidJar,'--manifest',"$BuildRoot\AndroidManifest.xml",'--java',"$BuildRoot\gen",'-A',"$BuildRoot\assets",'--auto-add-overlay',"$BuildRoot\resources.zip")
$JavaSources = @(Get-ChildItem "$BuildRoot\src","$BuildRoot\gen" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Invoke-Checked "$JavaBin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-bootclasspath',"$AndroidJar;$BuildTools\core-lambda-stubs.jar",'-d',"$BuildRoot\classes") + $JavaSources)
Invoke-Checked "$JavaBin\jar.exe" @('cf',"$BuildRoot\classes.jar",'-C',"$BuildRoot\classes",'.')
Invoke-Checked "$JavaBin\java.exe" @('-cp',"$BuildTools\lib\d8.jar",'com.android.tools.r8.D8','--release','--min-api','26','--lib',$AndroidJar,'--output',"$BuildRoot\dex", "$BuildRoot\classes.jar")
Copy-Item -LiteralPath "$BuildRoot\base.apk" -Destination "$BuildRoot\unsigned.apk" -Force
Invoke-Checked "$JavaBin\jar.exe" @('uf',"$BuildRoot\unsigned.apk",'-C',"$BuildRoot\dex",'classes.dex')
Invoke-Checked "$BuildTools\zipalign.exe" @('-f','-p','4',"$BuildRoot\unsigned.apk", "$BuildRoot\aligned.apk")
$KeyStore = Join-Path $SignDir 'ryadom.jks'
$PasswordFile = Join-Path $SignDir 'password.txt'
if (-not (Test-Path -LiteralPath $KeyStore)) {
 if (-not $CreateSigningKey) {
  throw "Ключ подписи Android не найден: $KeyStore. Релизная сборка не создаёт новый ключ (иначе APK не сможет обновить установленное приложение). Укажите -SigningDir / RYADOM_SIGNING_DIR с существующим ключом. Для первого локального стенда используйте -CreateSigningKey."
 }
 $Bytes = New-Object byte[] 32
 [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($Bytes)
 $Password = [Convert]::ToBase64String($Bytes)
 [IO.File]::WriteAllText($PasswordFile,$Password)
 $env:RYADOM_SIGN_PASSWORD = $Password
 Invoke-Checked "$JavaBin\keytool.exe" @('-genkeypair','-keystore',$KeyStore,'-alias','ryadom','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Ryadom Personal App, O=Private LAN, C=RU','-storepass:env','RYADOM_SIGN_PASSWORD','-keypass:env','RYADOM_SIGN_PASSWORD')
 Remove-Item Env:\RYADOM_SIGN_PASSWORD
}
$SignPassword = if ($env:RYADOM_SIGN_PASSWORD) { $env:RYADOM_SIGN_PASSWORD } elseif (Test-Path -LiteralPath $PasswordFile) { (Get-Content -LiteralPath $PasswordFile -Raw).Trim() } else { throw "Пароль подписи не найден: $PasswordFile. Задайте RYADOM_SIGN_PASSWORD или положите файл пароля в каталог подписи." }
$KsPassArg = if (Test-Path -LiteralPath $PasswordFile) { "file:$PasswordFile" } else { "pass:$SignPassword" }
Invoke-Checked "$JavaBin\java.exe" @('-jar',"$BuildTools\lib\apksigner.jar",'sign','--ks',$KeyStore,'--ks-key-alias','ryadom','--ks-pass',$KsPassArg,'--out',"$DistDir\Ryadom-Android.apk", "$BuildRoot\aligned.apk")
Invoke-Checked "$JavaBin\java.exe" @('-jar',"$BuildTools\lib\apksigner.jar",'verify','--verbose','--print-certs',"$DistDir\Ryadom-Android.apk")
Write-Output "Ready: $DistDir\Ryadom-Android.apk"
