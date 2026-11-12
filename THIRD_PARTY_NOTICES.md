# Сторонние компоненты

«Рядом» распространяется по лицензии MIT (см. [LICENSE](LICENSE)). В состав сборок и инструментов входят сторонние компоненты.

## Windows-сборка

- **Node.js runtime.** В `Ryadom-Windows.exe` встроен Node.js (неизменённый бинарник); серверная часть — `server.cjs`. Node.js распространяется по лицензии MIT и включает компоненты под другими свободными лицензиями (V8, OpenSSL, ICU и др.). Полный текст лицензий — в файле [`Node-LICENSE.txt`](https://github.com/parxomenkoevgenii/ryadom/releases) в составе выпуска и в репозитории Node.js: <https://github.com/nodejs/node/blob/main/LICENSE>.
- **.NET Framework / Windows Forms** — системный компонент Windows, отдельно не распространяется.

## Android-сборка

- Приложение использует только системные API Android и системный WebView. Сторонние библиотеки в APK не включаются.
- `org.json` используется **только в JVM-тестах** (скачивается скриптом с проверкой SHA-256) и в APK не попадает.

## Инструменты сборки и подписи

Не распространяются вместе с приложением:

- Java JDK, Android SDK/Build Tools, .NET Framework C# Compiler;
- Windows SDK `signtool.exe` (подпись Authenticode);
- OpenSSH `ssh-keygen.exe` (Windows, подпись манифеста релиза);
- Git (среда сборки).

## Изображения и шрифты

Иконки и графика — оригинальные для проекта. Шрифты не поставляются: интерфейс использует системные шрифты.
