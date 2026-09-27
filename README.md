# Warlock Camera 2.0 — MERGED (RV101)

Объединённая сборка: компактный Camera2-код из вашего ZIP + dual-path кнопка из официальных docs / community.

## Сравнение источников

| | Ваш ZIP | Мой каркас | MERGED |
|--|---------|------------|--------|
| Camera2 live preview | ✅ компактный | ✅ модульный | ✅ берём ZIP |
| Фото ImageReader + MediaStore | ✅ | частично | ✅ ZIP |
| Видео MediaRecorder | ✅ | ✅ | ✅ ZIP |
| KeyEvent MENU/VOLUME_DOWN | ✅ | — | ✅ |
| System broadcasts CLICK/LONG | заготовка | ✅ | ✅ оба |
| Настройки preview (W/H/X/Y/α) | ✅ SettingsActivity RU | ✅ | ✅ ZIP |
| Иконка | ✅ | — | ✅ |
| Диагностика / Файлы / Receiver | — | каркас | не в этом APK |
| Пакет | com.warlock.rokidcamera | то же | то же |
| Версия | 2.0.0 | 2.0.0 | 2.0.0 |

## Кнопка (dual path)

1. **Broadcasts** (x-docs Glass3 / Sprite):
   - `com.rokid.glass3.action.button.CLICK`
   - `com.rokid.glass3.action.button.DOUBLE_CLICK`
   - `com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS`
   - + fallbacks из community
2. **KeyEvent** (системный Camera2 на RV101):
   - `KEYCODE_MENU`, `KEYCODE_VOLUME_DOWN`, `KEYCODE_CAMERA`
   - короткое → фото / стоп REC
   - длинное → старт/стоп видео

Поведение по ТЗ: короткое = фото (или стоп записи), длинное = toggle записи.

## Сборка APK

Требования: Android Studio / JDK 17, SDK 32–34.

```bash
cd WarlockCamera2_MERGED
# указать sdk.dir в local.properties
./gradlew assembleDebug
```

APK:
`app/build/outputs/apk/debug/app-debug.apk`

Переименовать при желании:
`WarlockCamera2_RV101_v2.0.0.apk`

Установка на очки:
```bash
adb install -r -g WarlockCamera2_RV101_v2.0.0.apk
adb shell am start -n com.warlock.rokidcamera/.MainActivity
adb logcat -s WarlockBtn:V
```

## Важно (честно)

- APK **не** тестировался на физическом RV101 в этой среде (нет устройства).
- Camera2 + TextureView — правильный путь для live preview на дисплее очков (подтверждено системным Camera2.apk и community).
- Точные action-строки кнопки на прошивке **1.26.009** нужно проверить logcat при первом запуске.
- Полный ТЗ (Файлы, передача на телефон, диагностика) — отдельные модули; этот APK — рабочее ядро камеры + превью + кнопка.

## Источники

- x-docs.rokid.com — Buttons and side touchpad
- buildwithfenna/rokid-docs — Camera2 system app
- cursive-team/rokid-apps — RV101 deploy (480x640, SDK 32, arm64)
- Системный Camera2: KEYCODE_VOLUME_DOWN / CAMERA → shutter
