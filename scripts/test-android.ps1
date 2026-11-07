param([string]$SdkPath='C:\Program Files (x86)\Android\android-sdk',[string]$JdkPath='C:\Program Files\Android\openjdk\jdk-21.0.8')
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path -Parent $PSScriptRoot
$BuildRoot=Join-Path $env:TEMP 'RyadomAndroidBuild'
$AndroidJar=Join-Path $SdkPath 'platforms\android-35\android.jar'
$TestClasses=Join-Path $BuildRoot 'test-classes'
New-Item -ItemType Directory -Path $TestClasses -Force | Out-Null
$JsonJar=Join-Path $ProjectRoot '.tools\java\json.jar'
if(-not (Test-Path -LiteralPath "$BuildRoot\classes\local\ryadom\chat\ChatHost.class")){throw 'Build Android first: scripts/build-android.ps1'}
if(-not (Test-Path -LiteralPath $JsonJar)){
 New-Item -ItemType Directory -Path (Split-Path -Parent $JsonJar) -Force | Out-Null
 Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $JsonJar
}
if((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JVM test JSON dependency checksum'}
$Classpath="$TestClasses;$BuildRoot\classes;$ProjectRoot\.tools\java\json.jar;$AndroidJar"
$Sources=@(Get-ChildItem "$ProjectRoot\android\tests" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& "$JdkPath\bin\javac.exe" -encoding UTF-8 -cp $Classpath -d $TestClasses @Sources
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
& "$JdkPath\bin\java.exe" -cp $Classpath local.ryadom.chat.AddressTest
if($LASTEXITCODE -ne 0){throw 'Address validation tests failed'}
$env:RYADOM_TEST_JAVA="$JdkPath\bin\java.exe"
$env:RYADOM_TEST_CLASSPATH=$Classpath
try { & node --test "$ProjectRoot\tests\server.test.cjs" "$ProjectRoot\tests\proxy.test.cjs"; if($LASTEXITCODE -ne 0){throw 'Android host / direct proxy integration tests failed'} }
finally { Remove-Item Env:\RYADOM_TEST_JAVA,Env:\RYADOM_TEST_CLASSPATH }
