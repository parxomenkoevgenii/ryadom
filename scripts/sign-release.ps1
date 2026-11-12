param(
 [string]$DistDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist'),
 [string]$SigningDir = $(if($env:RYADOM_SIGNING_DIR){$env:RYADOM_SIGNING_DIR}else{Join-Path $env:LOCALAPPDATA 'RyadomSigning'}),
 [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
function Write-Step([string]$Text) { Write-Host "`n== $Text ==" }
function Find-Tool([string[]]$Names, [string[]]$Fallbacks = @()) {
 foreach ($name in $Names) { $cmd = Get-Command $name -ErrorAction SilentlyContinue; if ($cmd) { return $cmd.Source } }
 foreach ($path in $Fallbacks) { if (Test-Path -LiteralPath $path) { return $path } }
 return $null
}
# Нативный вызов без превращения stderr в terminating error (PowerShell 5.1).
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
 $old = $ErrorActionPreference
 $ErrorActionPreference = 'Continue'
 try { $output = & $Exe @Arguments 2>&1 | Out-String; $code = $LASTEXITCODE }
 finally { $ErrorActionPreference = $old }
 if ($output.Trim()) { Write-Host $output.TrimEnd() }
 return $code
}

if (-not (Test-Path -LiteralPath $DistDir)) { throw "Dist directory not found: $DistDir" }
$DistDir = (Resolve-Path -LiteralPath $DistDir).Path
if (-not (Test-Path -LiteralPath $SigningDir)) { New-Item -ItemType Directory -Path $SigningDir -Force | Out-Null }

$signTool = Find-Tool @('signtool') @((Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe'))
$sshKeygen = Find-Tool @('ssh-keygen') @((Join-Path $env:WINDIR 'System32\OpenSSH\ssh-keygen.exe'))
$exe = Join-Path $DistDir 'Ryadom-Windows.exe'
$sumFile = Join-Path $DistDir 'SHA256SUMS.txt'
$sumSig = "$sumFile.sig"
$allowedSigners = Join-Path (Split-Path -Parent $PSScriptRoot) 'keys\allowed_signers'
$sshIdentity = 'release@ryadom.local'
$sshNamespace = 'ryadom-release'
$sshKey = if ($env:RYADOM_SSH_SIGN_KEY) { $env:RYADOM_SSH_SIGN_KEY } else { Join-Path $SigningDir 'release_ed25519' }

Write-Step 'Подпись Windows (Authenticode)'
$pfx = if ($env:RYADOM_SIGN_PFX) { $env:RYADOM_SIGN_PFX } else { Join-Path $SigningDir 'code-signing.pfx' }
$pfxPassword = if ($env:RYADOM_SIGN_PFX_PASSWORD) { $env:RYADOM_SIGN_PFX_PASSWORD } elseif (Test-Path -LiteralPath (Join-Path $SigningDir 'code-signing.password')) { (Get-Content -LiteralPath (Join-Path $SigningDir 'code-signing.password') -Raw).Trim() } else { $null }
if (-not (Test-Path -LiteralPath $exe)) { Write-Host 'Пропуск: Ryadom-Windows.exe не найден.' }
elseif (-not (Test-Path -LiteralPath $pfx)) { Write-Host "Пропуск: сертификат не найден ($pfx)." }
elseif (-not $signTool) { Write-Host 'Пропуск: signtool.exe не найден (нужен Windows SDK).' }
elseif (-not $pfxPassword) { Write-Host "Пропуск: нет пароля PFX (RYADOM_SIGN_PFX_PASSWORD или $SigningDir\code-signing.password)." }
elseif (-not $VerifyOnly) {
 $argsSign = @('sign','/fd','SHA256','/f',$pfx,'/p',$pfxPassword)
 $code = Invoke-Native $signTool ($argsSign + @('/tr','http://timestamp.digicert.com','/td','SHA256',$exe))
 if ($code -ne 0) { $code = Invoke-Native $signTool ($argsSign + @($exe)) }
 if ($code -ne 0) { throw 'signtool не смог подписать Ryadom-Windows.exe' }
}
if (Test-Path -LiteralPath $exe) {
 $sig = Get-AuthenticodeSignature -LiteralPath $exe
 Write-Host ("Authenticode: Status={0}; Signer={1}" -f $sig.Status, $(if($sig.SignerCertificate){$sig.SignerCertificate.Subject}else{'-'}))
 if ($sig.Status -ne 'Valid') { Write-Host 'Внимание: сертификат самоподписанный/недоверенный — SmartScreen не доверяет. Для доверия нужен OV/EV-сертификат.' }
}

Write-Step 'Контрольные суммы'
$artifacts = Get-ChildItem -LiteralPath $DistDir -File | Where-Object { $_.Name -notmatch '\.(sig|asc|idsig|pfx|p12)$' -and $_.Name -ne 'SHA256SUMS.txt' } | Sort-Object Name
if (-not $artifacts) { throw 'В dist нет файлов для подписи.' }
$lines = foreach ($file in $artifacts) {
 $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower()
 "$hash  $($file.Name)"
}
[System.IO.File]::WriteAllText($sumFile, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
Get-Content -LiteralPath $sumFile -Encoding UTF8 | ForEach-Object { Write-Host "  $_" }

Write-Step 'Подпись манифеста (OpenSSH, отделённая подпись)'
if (-not $sshKeygen) { Write-Host 'Пропуск: ssh-keygen не найден.' }
elseif (-not (Test-Path -LiteralPath $allowedSigners)) { Write-Host "Пропуск: нет $allowedSigners" }
elseif (-not (Test-Path -LiteralPath $sshKey)) { Write-Host "Пропуск: нет ключа подписи $sshKey (задайте RYADOM_SSH_SIGN_KEY)." }
else {
 if (-not $VerifyOnly) {
  Remove-Item -LiteralPath $sumSig -Force -ErrorAction SilentlyContinue
  $code = Invoke-Native $sshKeygen @('-Y','sign','-f',$sshKey,'-n',$sshNamespace,$sumFile)
  if ($code -ne 0 -or -not (Test-Path -LiteralPath $sumSig)) { throw 'ssh-keygen не смог подписать манифест' }
 }
 if (-not (Test-Path -LiteralPath $sumSig)) { throw "Нет подписи: $sumSig" }
 $cmdLine = '"' + $sshKeygen + '" -Y verify -f "' + $allowedSigners + '" -I ' + $sshIdentity + ' -n ' + $sshNamespace + ' -s "' + $sumSig + '" < "' + $sumFile + '"'
 $code = Invoke-Native $env:ComSpec @('/d','/c',$cmdLine)
 if ($code -ne 0) { throw 'Подпись SHA256SUMS.txt недействительна' }
 Write-Host 'Подпись манифеста верна.'
 $fingerprint = (& $sshKeygen -lf ($sshKey + '.pub') 2>$null | Out-String).Trim()
 Write-Host "Ключ: $fingerprint"
}

Write-Host "`nГотово. Файлы в $DistDir"
