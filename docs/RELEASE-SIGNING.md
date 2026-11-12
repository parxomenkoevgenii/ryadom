# Подпись и проверка релизов

Документ описывает, как подписываются артефакты «Рядом» и как их проверить.

## Что подписывается

1. **Windows-сборка `Ryadom-Windows.exe`** — Authenticode-подпись (`signtool.exe`, Windows SDK).
2. **Манифест `dist/SHA256SUMS.txt`** — отделённая подпись OpenSSH (`ssh-keygen -Y sign`). Манифест содержит SHA-256 всех файлов выпуска, поэтому его подпись покрывает EXE, APK, архив исходников и текстовые файлы.

Публичные ключи и сертификат лежат в каталоге [`keys/`](../keys/):

| Файл | Назначение |
| --- | --- |
| `keys/ryadom-release.cer` | Публичный сертификат Authenticode (DER). |
| `keys/ryadom-release.pub` | Публичный ключ OpenSSH для проверки `SHA256SUMS.txt.sig`. |
| `keys/allowed_signers` | Список доверенных подписантов для `ssh-keygen -Y verify`. |

## Текущие отпечатки (2026-11-13)

- Authenticode-сертификат: `CN=Ryadom Release Signing, O=Ryadom, C=RU`, SHA-1 отпечаток `AFB91358DBA309C2CABF241821ACB384D8C5F4B5`, действует до 2029-11-13. **Самоподписанный** (для локальной сборки и подготовки релиза).
- Ключ OpenSSH: `SHA256:/cRSWhszTRR4y5tmQUhxUsI0BDpay+/Oq/2PkXhwjg0` (`release@ryadom.local`), алгоритм ED25519.

> Для доверия SmartScreen нужен коммерческий **OV/EV code-signing сертификат**. Самоподписанный сертификат подтверждает целостность сборки, но не снимает предупреждение Windows. После покупки сертификата замените PFX и опубликуйте новый отпечаток; подпись SSH при этом не меняется.

## Где хранятся секреты

Каталог подписи по умолчанию: `%LOCALAPPDATA%\RyadomSigning` (вне OneDrive; ACL: только пользователь, SYSTEM, администраторы).

| Файл | Что это |
| --- | --- |
| `ryadom.jks` + `password.txt` | Ключ подписи Android APK и пароль. Прежний ключ нельзя терять: иначе APK не сможет обновить установленное приложение. |
| `code-signing.pfx` + `code-signing.password` | Сертификат Authenticode и пароль PFX. |
| `release_ed25519` + `.pub` | Ключ OpenSSH для подписи манифеста (без пароля — только для сборочной машины). |

Для CI/другой машины каталог задаётся переменной `RYADOM_SIGNING_DIR`; отдельные пути — `RYADOM_SIGN_PFX`, `RYADOM_SSH_SIGN_KEY`.

Запрещено: коммитить эти файлы в репозиторий, хранить в OneDrive/Dropbox/Google Drive, пересылать по почте. `.gitignore` уже исключает `*.pfx`, `*.p12`, `*.password`, `android/signing/`.

## Как подписать

```powershell
# из корня проекта
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sign-release.ps1 -DistDir dist
```

Или сборка Windows сразу с подписью:

```powershell
$env:RYADOM_SIGN_RELEASE = '1'
node scripts/build-windows.cjs
```

Скрипт:
1. подписывает EXE через signtool (с попыткой RFC3161-таймстампа, при недоступности — без него);
2. пересчитывает `SHA256SUMS.txt`;
3. создаёт отделённую подпись `SHA256SUMS.txt.sig`;
4. проверяет обе подписи и печатает отпечатки.

Проверка без создания подписи:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sign-release.ps1 -DistDir dist -VerifyOnly
```

## Как проверить скачанный выпуск

```powershell
# 1. Проверить хэши
$sums = Get-Content dist\SHA256SUMS.txt
foreach ($line in $sums) {
  $hash, $name = $line -split '\s+', 2
  if ((Get-FileHash -LiteralPath "dist\$name" -Algorithm SHA256).Hash.ToLower() -ne $hash) { throw "Bad hash: $name" }
}

# 2. Проверить подпись манифеста
ssh-keygen -Y verify -f keys\allowed_signers -I release@ryadom.local -n ryadom-release -s dist\SHA256SUMS.txt.sig < dist\SHA256SUMS.txt

# 3. Проверить Authenticode EXE
Get-AuthenticodeSignature dist\Ryadom-Windows.exe | Format-List Status, StatusMessage, SignerCertificate
```

Для самоподписанного сертификата `Status` будет `UnknownError`/`NotTrusted` — это ожидаемо до покупки OV/EV. Сверяйте отпечаток сертификата с опубликованным выше.

## CI

Для релизов в GitHub Actions:
- `RYADOM_SIGNING_DIR` — каталог, восстановленный из secrets (base64 + распаковка в защищённый temp);
- `RYADOM_SIGN_PFX_PASSWORD`, `RYADOM_SIGN_PFX`, `RYADOM_SSH_SIGN_KEY` — из GitHub Actions secrets;
- PFX и ключ не печатать в логах; после подписи — удалить временные файлы;
- сборка без ключа обязана падать: `build-android.ps1` не создаёт новый keystore автоматически (только с явным `-CreateSigningKey` для первого локального стенда).

## Ротация и компрометация

- Ключ APK: при компрометации пользователям придётся переустанавливать приложение (Android не принимает APK с другой подписью). Действующий ключ хранить в защищённом месте, резервную копию — офлайн.
- Authenticode: заменить PFX, выпустить обновление, уведомить в примечаниях к релизу.
- OpenSSH: добавить новый ключ в `keys/allowed_signers`, сохранить старый для проверки предыдущих выпусков; при компрометации отозвать и переподписать манифест.
