# ATM10 To the Sky

Подготовка сборки 2.3.1 для LANMinecraft. [Авторский клиентский выпуск](https://www.curseforge.com/minecraft/modpacks/all-the-mods-10-sky/files/9368148)

`tools/upstream-client-lock.json` фиксирует SHA256 и размер оригинального ZIP, ссылки на официальный источник, записи зависимостей и SHA256 файлов внутри авторских overrides. Minecraft и загрузчик взяты из оригинального манифеста. Java в `portable-pack.json` соответствует выбору текущего лаунчера для этой версии Minecraft и проверяется при запуске.

## Восстановление авторского клиента

Закреплённый авторский клиент CurseForge использует project ID 1298402 и file ID 9368148.
Для подготовки текущего кандидата из корня LANMinecraft запустите:

```powershell
python -B tools/PackData/CurseForge/release_overlay.py stage --pack-source LocalSources/ATM10-To-the-Sky --output $stage
```

`tools/overlay.json` сохраняет политику открытия мира через Steam. Для нового авторского выпуска снимите отдельную квитанцию через `prepare.py --client-only` и проверьте её командой `release_overlay.py review-update`. При изменении защищённого входа пересмотрите рецепт и закрепите новые точные байты. После нативной проверки сохраните полный `tools/upstream-lock.json` и выполните `release_overlay.py verify` по готовому манифесту.

## Публикация

Условия авторского источника — All Rights Reserved.

Пользователь подтвердил наличие разрешения на перезалив. Публикация следует после проверки полного состава и нативной приёмки.
