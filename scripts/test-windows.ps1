$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path -Parent $PSScriptRoot
$TestRoot=Join-Path $env:TEMP 'RyadomWindowsTransportTest'
New-Item -ItemType Directory -Path $TestRoot -Force | Out-Null
Copy-Item -LiteralPath "$ProjectRoot\windows\LanTransport.cs" -Destination "$TestRoot\LanTransport.cs" -Force
Copy-Item -LiteralPath "$ProjectRoot\tests\WindowsTransportTest.cs" -Destination "$TestRoot\WindowsTransportTest.cs" -Force
Push-Location $TestRoot
try {
 & 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /codepage:65001 /target:exe /out:WindowsTransportTest.exe /reference:System.Management.dll LanTransport.cs WindowsTransportTest.cs
 if($LASTEXITCODE -ne 0){throw 'Native transport test compilation failed'}
 & "$TestRoot\WindowsTransportTest.exe"
 if($LASTEXITCODE -ne 0){throw 'Physical network transport test failed'}
} finally { Pop-Location }
