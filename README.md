# Edge ReVanced

ReVanced-патчи для Microsoft Edge Canary на Android с упором на удобство
телефонного интерфейса.

Патчи проверены на ARM64-сборках:

- `152.0.4180.0`;
- `152.0.4184.0`.

В коде нет whitelist версий. Патчи доступны для пакета
`com.microsoft.emmx.canary` и ищут точки внедрения по структурным признакам:
стабильным Chromium/Microsoft API, сигнатурам, строкам, resource references и
характерным инструкциям. Если нужный code path изменится, сборка должна упасть
на конкретном fingerprint, а не молча изменить похожий метод.

## Возможности

- **Своя новая вкладка** — открывает настраиваемый HTTP/HTTPS-адрес вместо
  встроенной NTP. По умолчанию используется `http://tabpage.ariex.ru`.
- **Мобильные DevTools** — добавляет штатный пункт «Средства разработчика» в
  меню Edge, запускает встроенный Chromium DevTools frontend через локальный
  CDP-proxy и адаптирует интерфейс к портретному экрану. Кнопка `»` для скрытых
  панелей остаётся доступной на нижней панели.
- **Экран вкладок для правой руки** — первая вкладка находится справа снизу,
  новые вкладки заполняют сетку в обратном порядке вверх, а карточки остаются
  полноценно прокручиваемыми.
- **Свайп вверх к вкладкам** — открывает экран вкладок свайпом вверх по панели
  инструментов при верхнем и нижнем расположении адресной строки.
- **Chrome Web Store** — включает обычную кнопку установки расширений на сайте
  Chrome Web Store, передаёт установку родному механизму Edge и автоматически
  активирует успешно установленное расширение.
- **Без повторяющегося окна аккаунта Microsoft** — закрывает только
  информационное веб-окно «Краткое примечание о вашей учетной записи
  Майкрософт», не отключая аккаунт и синхронизацию.

## Как устроен проект

```text
чистый Edge APK
      │
      ▼
ReVanced Patcher 22
      ├── Kotlin bytecode/resource patches
      ├── mobile.rve с runtime Java-кодом
      └── мобильный Chromium DevTools frontend
      │
      ▼
пересобранные DEX и resources
      │
      ▼
подпись постоянным edge-mod.keystore
      │
      ▼
adb install -r
```

- `patches/src/main/kotlin/app/revanced/patches/edge/EdgePatches.kt` содержит
  fingerprints и статические изменения DEX/resources.
- `extensions/edge/mobile` собирается в `mobile.rve`. Его Java-код выполняется
  уже внутри Edge: запускает DevTools proxy, обрабатывает Chrome Web Store,
  закрывает точное account notice и настраивает Android View экрана вкладок.
- `scripts/devtools-mobile.js` модифицирует собранный Chromium DevTools frontend
  для touch-интерфейса.
- `scripts/bootstrap.ps1` проверяет ReVanced CLI по SHA-256, получает
  зафиксированный commit официального Gradle plugin и собирает DevTools
  frontend.
- `scripts/build.ps1` собирает Android-совместимый `.rvp`.
- `scripts/patch.ps1` применяет все патчи, перепаковывает APK и подписывает его.

`.rvp` — это JAR-контейнер с manifest metadata, JVM-классами, их Android DEX
версией, runtime extension и ресурсами патчей. Сам Edge внутрь `.rvp` не входит.

## Первый запуск

Нужны:

- Windows PowerShell;
- Git;
- JDK 21;
- Bun;
- Android SDK Platform `37.0` и Build-Tools `37.0.0` для перепаковки Edge;
- чистый монолитный ARM64 APK Edge Canary, не split APK.

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1
```

Bootstrap создаёт только ignored-артефакты в `local/` и воспроизводимо получает:

- ReVanced CLI `6.0.0` / Patcher `22.0.0`;
- официальный `revanced-patches-gradle-plugin` на зафиксированном commit;
- Chromium DevTools frontend с русской и английской локалями.

## Создание APK

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk'
```

Другой адрес новой вкладки и лимит CPU:

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk' `
    -NewTabUrl 'https://example.com/start' `
    -CpuCount 6
```

Результат сохраняется рядом с исходным APK с суффиксом `-revanced.apk`.
Gradle и Patcher по умолчанию ограничены четырьмя логическими CPU и работают с
пониженным приоритетом.

Постоянный ключ создаётся в `local/edge-mod.keystore`. Не удаляйте его: Android
разрешает обновлять установленный мод только APK с той же подписью.

```powershell
adb install -r 'C:\path\to\Edge-Canary-arm64-revanced.apk'
```

Подписанный мод нельзя поставить поверх официального Edge с подписью Microsoft.
Первый переход требует удалить официальную Canary. Последующие сборки Edge
ReVanced обновляются через `adb install -r` без удаления данных.

Для отдельной тестовой установки доступен пакет
`com.microsoft.emmx.canary.revanced`:

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk' `
    -SideBySide
```

Этот режим не является основным: Microsoft/Google login и внешние интеграции
могут проверять исходное package name.

## Обновление на новую версию Edge

1. Скачайте чистый монолитный `arm64-v8a` APK новой Canary.
2. Запустите тот же `scripts/patch.ps1` без изменения номера версии в коде.
3. Убедитесь, что все пользовательские патчи завершились с `succeeded`.
4. Установите APK через `adb install -r`.
5. На настоящем ARM64-устройстве проверьте:
   - новую вкладку;
   - кнопку и портретный интерфейс DevTools;
   - порядок и прокрутку вкладок;
   - свайп по панели;
   - установку и автоматическое включение расширения;
   - отсутствие повторного account notice.

Если структура не изменилась, новая версия применяется автоматически. Если
fingerprint перестал совпадать, исправляется только соответствующая точка
поиска/инъекции. Обфусцированные имена вроде `hu6`, `uor` или `t4h` в патчах не
закреплены и при обычной минификации могут меняться без доработки проекта.

Успешная сборка APK ещё не доказывает корректность runtime UX. Финальной
проверкой считается реальный сценарий на ARM64-телефоне.

## ReVanced Manager

ReVanced Manager запускает тот же Patcher непосредственно на Android: загружает
`.rvp`, выбирает патчи, декодирует APK, применяет изменения, собирает результат
и подписывает его своим постоянным keystore. Компьютер для архитектуры ReVanced
не обязателен.

Текущий Edge ReVanced пока официально поддерживает PC pipeline. Причина не в
smali/Kotlin-патчах, а в DevTools frontend: он добавляет сотни файлов в APK и
требует полной перекомпиляции ресурсов Edge с Android SDK 37 framework и
совместимым `aapt2`. `patch.ps1` передаёт эти зависимости явно, а обычный Manager
не получает их из этого репозитория. До переноса framework/aapt2 contract внутрь
самодостаточного Android flow применение через Manager нельзя считать
поддержанным.

## Лицензия

GPLv3. См. [LICENSE](LICENSE).
