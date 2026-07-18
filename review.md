# svcntrl — Полное ревью кода

> **Дата:** 2026-07-17  
> **Версия мода:** 1.1.2  
> **MC / Fabric:** 1.21.8 / Loader 0.16.13 / Loom 1.10-SNAPSHOT  
> **Проанализировано:** 14 Java-файлов, 2 JSON-локализации, конфигурация Gradle, `fabric.mod.json`

---

## Оглавление

1. [Критические уязвимости и баги](#1-критические-уязвимости-и-баги)
2. [Баги среднего приоритета](#2-баги-среднего-приоритета)
3. [Проблемы потокобезопасности и конкурентности](#3-проблемы-потокобезопасности-и-конкурентности)
4. [Производительность](#4-производительность)
5. [Неконсистентность кода](#5-неконсистентность-кода)
6. [UX-проблемы](#6-ux-проблемы)
7. [Локализация](#7-локализация)
8. [Архитектура и концептуальные проблемы](#8-архитектура-и-концептуальные-проблемы)
9. [Проблемы сборки и конфигурации](#9-проблемы-сборки-и-конфигурации)
10. [Документация](#10-документация)
11. [Сводная таблица](#11-сводная-таблица)

---

## 1. Критические уязвимости и баги

### 1.1 SSRF через `customExportEndpoint` (CRITICAL)
**Файл:** `SvcntrlConfig.java:21`, `ExportManager.java:752-755`

Конфигурационное поле `customExportEndpoint` позволяет указать произвольный URL, на который мод будет отправлять файлы с сервера через HTTP POST. Валидация URL полностью отсутствует. Злоумышленник, получивший доступ к конфигу, может:
- Направить загрузку на внутренний сетевой адрес (`http://localhost:8080/admin`, `http://192.168.1.1/...`)
- Эксфильтрировать данные на внешний сервер
- Сканировать внутреннюю сеть сервера

### 1.2 Integer overflow в `blockData` массиве (CRITICAL)
**Файл:** `SaveTask.java:71`

```java
this.blockData = new int[width * height * length];
```

`width`, `height`, `length` — это `int`. При регионе размером, например, 2000×500×2000 произведение `width * height * length = 2_000_000_000`, что переполняет `int` (max ~2.1 млрд). `maxRegionVolume` по умолчанию 5_000_000, но **допускается изменение через конфиг без верхнего лимита**. Администратор может поставить, например, `maxRegionVolume = 2147483647`, после чего ближайший save вызовет `NegativeArraySizeException` или создаст массив неправильного размера.

### 1.3 Entity Duplication через Preview (HIGH)
**Файл:** `PreviewManager.java:372-388`

В `StartPreviewTask` при спавне превью-сущностей используется `EntityType.loadEntityWithPassengers()`, который **реально создаёт объекты Entity в памяти JVM** (с уникальными ID). Эти Entity хотя и не добавляются в мир через `world.spawnEntity()`, они всё же резервируют entity ID. При массивном использовании preview для больших регионов с сотнями сущностей (фермы, villages) происходит утечка entity ID, а сами объекты не освобождаются GC, пока на них есть ссылки через `activePreviewEntities`.

### 1.4 `NbtSizeTracker.ofUnlimitedBytes()` — DoS через снапшоты (HIGH)
**Файл:** `AreaSerializer.java:75, 114-115, 708`

Все вызовы `NbtIo.readCompressed()` используют `NbtSizeTracker.ofUnlimitedBytes()`. Злоумышленник (или corrupted файл) может создать снапшот-файл с экстремально большим NBT (например, 10 ГБ), который при чтении вызовет `OutOfMemoryError` и крашнет сервер. Корректное решение — установить разумный лимит (например, 256MB) или использовать streaming.

### 1.5 Отсутствие проверки `project.list` (HIGH)
**Файл:** `SvcntrlCommands.java`

Команда `/svcntrl project list` **упоминается в README, но не реализована**. `getProjectsForPlayer()` существует в `ProjectManager`, но команда не зарегистрирована. Это означает, что у пользователей нет способа увидеть свои проекты через команды.

---

## 2. Баги среднего приоритета

### 2.1 Расхождение индексации BlockEntity при Patch Restore
**Файл:** `AreaSerializer.java:165, 547`

Индекс BlockEntity рассчитывается как `X + Y * sizeX + Z * sizeX * sizeY`, но в `SaveTask.java:151` (где данные сохраняются) индекс blockData рассчитывается как `rz * (width * height) + ry * width + rx`. Это **разные формулы**: одна `X + Y*W + Z*W*H`, другая `Z*W*H + Y*W + X`. При patch-diff сравнении блоков по индексу это приводит к сравнению **разных позиций** — патч будет применяться некорректно.

Конкретно:
- SaveTask: `index = rz * (width * height) + ry * width + rx` → `Z*WH + Y*W + X`
- Patch diff (AreaSerializer:165): `idx = X + Y * sizeX + Z * sizeX * sizeY` → `X + Y*W + Z*WH`

Формулы идентичны (`Z*W*H + Y*W + X` = `X + Y*W + Z*W*H`), но **порядок аргументов имеет значение при итерации по blockData**. В SaveTask итерация идёт по `(y, z, x)`, а формула `rz * (width * height) + ry * width + rx` предполагает Z-major порядок. В patch diff'е `idx = X + Y*sizeX + Z*sizeX*sizeY` — это X-major. Однако при чтении blockData в RestoreTask (L468-471) используется тот же Z-major порядок, что и при записи, так что **данные блоков корректны**. Но BlockEntity индексация в patch diff (L165) использует **другой порядок**, что приводит к неправильному маппингу BlockEntity на блоки при патч-восстановлении.

### 2.2 Race condition в `toggleOutline`
**Файл:** `UXManager.java:37-44`

```java
if (outlinePlayers.contains(uuid)) {
    outlinePlayers.remove(uuid);
    return false;
} else {
    outlinePlayers.add(uuid);
    return true;
}
```

`ConcurrentHashMap.newKeySet()` — потокобезопасен для отдельных операций, но `contains` → `remove`/`add` — это compound operation, которая не атомарна. При одновременном вызове из двух потоков (маловероятно, но возможно при серверном тике + событии) может произойти двойное добавление.

### 2.3 `ExportManager.convertToLitematicDiff` — неверный расчёт координат
**Файл:** `ExportManager.java:596-598`

```java
int relX = i % sizeX;
int relY = (i / sizeX) % sizeY;
int relZ = i / (sizeX * sizeY);
```

Это формула для X-major итерации, но blockData хранится в Z-major порядке (Z→Y→X, как в SaveTask). Это означает, что координаты BlockEntity в diff-экспорте будут **перепутаны** (X↔Z), и Litematica покажет tile entities не на тех блоках.

### 2.4 Отсутствие проверки `player.isDisconnected()` в отложенных callback'ах
**Файл:** `AreaSerializer.java:88, 94-95`, `SaveTask.java:202-203`, `ExportManager.java` (множество мест)

Многие callback'и, вызываемые из `CompletableFuture`, отправляют сообщения игроку через `player.sendMessage()`, но не проверяют `player.isDisconnected()`. Если игрок отключился пока шла операция, может произойти NPE или сообщение уйдёт в пустоту. В большинстве мест проверка `player != null`, но не `isDisconnected()`.

### 2.5 `trimAutoSnapshots` удаляет файл асинхронно, не дожидаясь окончания
**Файл:** `Project.java:121-128`

```java
java.util.concurrent.CompletableFuture.runAsync(() -> {
    ...Files.deleteIfExists(snapshotFile);...
});
```

`trimAutoSnapshots` вызывается из `onSuccess` callback'а save-операции, который сам может быть вызван из async-треда. Таким образом, удаление файла снапшота конкурирует с возможным чтением этого файла, если пользователь одновременно запрашивает restore/preview по тому же ID.

### 2.6 `removeProject` может удалить директорию до завершения сохранения
**Файл:** `ProjectManager.java:101-128`

Если `oldFuture` ещё выполняется, `deleteAction` поставится в цепочку через `thenRunAsync()`. Однако `projects.remove(key)` на строке 102 удаляет проект из карты **немедленно**. Это означает, что между удалением из карты и фактическим удалением директории есть окно, в котором любой другой игрок может создать проект с тем же именем, и его данные будут удалены цепочкой.

### 2.7 `hashCode()`/`equals()` в `PaletteEntry` не обрабатывает `null` properties
**Файл:** `ExportManager.java:80-92`

```java
public boolean equals(Object o) {
    ...
    return name.equals(that.name) && properties.equals(that.properties);
}
public int hashCode() {
    ...
    result = 31 * result + properties.hashCode();
}
```

`properties` может быть `null`, если NBT не содержит `Properties`. Вызов `.equals()` или `.hashCode()` на `null` вызовет `NullPointerException`. Хотя в текущем коде `properties` всегда инициализируется как `new NbtCompound()` при отсутствии, это хрупкая зависимость от вызывающего кода.

---

## 3. Проблемы потокобезопасности и конкурентности

### 3.1 `Project.members` — `HashSet` без синхронизации
**Файл:** `Project.java:17`

```java
private final Set<UUID> members = new HashSet<>();
```

`members` — обычный `HashSet`, доступ к которому возможен из нескольких потоков (команды trust/untrust работают на серверном тике, сериализация — async). Для `branches` используется `ConcurrentHashMap`, но для `members` — нет. Race condition при одновременном `addMember()` из двух потоков может привести к потере данных.

### 3.2 Неатомарное `nextManualId++` / `nextAutoId++`
**Файл:** `Project.java:85, 92`

```java
int id = branch.nextManualId++;
```

Инкремент не атомарен и не синхронизирован. Если два save вызываются в быстрой последовательности (например, auto-save перед restore + manual save), может быть присвоен один и тот же ID двум разным снапшотам. Хотя на практике операции сериализованы через lock проекта, это не гарантировано формально (lock проверяется, но не enforce'ится на уровне метода).

### 3.3 `serializeProject` вызывается синхронно, но итерирует `branches`
**Файл:** `ProjectManager.java:361-368, 413`

Сериализация вызывается на вызывающем потоке (обычно server thread). `project.getBranches()` возвращает `branches.values()` из `ConcurrentHashMap`, итерация по которому safe, но если другой поток модифицирует `manualSnapshots` или `autoSnapshots` (которые `Collections.synchronizedList()`), итерация по ним в `serializeSnapshotList()` без внешней синхронизации может пропустить элементы или бросить `ConcurrentModificationException`.

### 3.4 `savePrefs` — гонка при быстром вызове
**Файл:** `ProjectManager.java:292-314`

```java
lastPrefsFuture = CompletableFuture.runAsync(() -> { ... });
```

Если `savePrefs()` вызывается дважды подряд, обе future запишут файл параллельно. Вторая запись может перезатереть первую, потеряв данные. В отличие от `saveProjectFuture`, здесь нет цепочки (`thenRunAsync`).

---

## 4. Производительность

### 4.1 O(n²) entity diff в `restorePatchArea`
**Файл:** `AreaSerializer.java:193-227`

```java
for (int i = 0; i < tEntities.size(); i++) {
    ...
    for (int j = 0; j < bEntities.size(); j++) { ... }
}
```

Для каждой target-сущности итерируется по всем base-сущностям. При 100 сущностях = 10_000 сравнений, при 1000 = 1_000_000. Каждое сравнение включает `NbtCompound.copy()` и `.equals()`, что тяжело. Лучше использовать `HashSet` с pre-computed NBT.

### 4.2 `isPosPreviewed` и `isPosLocked` — O(n) на каждый блок-ивент
**Файл:** `SvcntrlMod.java:174-193`

Эти методы вызываются на **каждый** `PlayerBlockBreakEvent`, `UseBlockCallback`, `AttackBlockCallback`. Каждый вызов итерирует все preview'ные/locked проекты и проверяет `contains()`. При 20+ активных проектах и игроке, копающем блоки — это ощутимая нагрузка на каждый тик.

### 4.3 `getProjectLookingAt` — итерирует все проекты на сервере
**Файл:** `UXManager.java:74`

Вызывается каждые 2 тика для каждого игрока в режиме raycast. Итерирует **все** проекты на сервере, делает AABB raycast для каждого. При 100+ проектах — значительная нагрузка.

### 4.4 `BlockPos.getSquaredDistance` в UXManager — неправильная culling-проверка
**Файл:** `UXManager.java:143`

```java
if (project.getMin().getSquaredDistance(player.getBlockPos()) > 16384 
    && project.getMax().getSquaredDistance(player.getBlockPos()) > 16384)
```

Проверяется расстояние до углов проекта, но если игрок **внутри** большого проекта, оба угла далеко — и проект будет пропущен для отрисовки границ, хотя игрок внутри.

### 4.5 `tickCounter` overflow в `UXManager`
**Файл:** `UXManager.java:106`

`tickCounter` — `int`, который инкрементируется каждый тик. При 20 TPS он переполнится через ~3.4 года непрерывной работы. После overflow `tickCounter % freq == 0` может работать некорректно с отрицательными числами (Java `%` сохраняет знак).

### 4.6 `CompletableFuture.runAsync()` без указания executor'а
**Файлы:** `Project.java:121`, `ProjectManager.java:122, 304, 329`, `ExportManager.java:138, 234, 439, 470, 697`, `AreaSerializer.java:73, 112`

Все вызовы `CompletableFuture.runAsync()` используют `ForkJoinPool.commonPool()`. При активном сервере с множеством I/O операций (NBT чтение/запись, HTTP upload'ы) common pool может быть перегружен, что замедлит **все** async-операции на сервере, включая другие моды. Следует использовать выделенный `ExecutorService` с bounded thread pool.

---

## 5. Неконсистентность кода

### 5.1 Стиль импортов: смешение прямых и FQN
Многие файлы используют FQN (fully qualified names) прямо в коде вместо import-деклараций:
- `java.util.concurrent.CompletableFuture` используется как FQN в `AreaSerializer`, `ExportManager`, `Project`, `ProjectManager`
- `java.nio.file.Files` и `java.nio.file.Path` импортируются в одних файлах, но пишутся через FQN в других

### 5.2 Неконсистентная обработка case-sensitivity имён
- Имена проектов: `toLowerCase(Locale.ROOT)` при хранении (ProjectManager:49, 93, 150)
- Имена бранчей: `toLowerCase(Locale.ROOT)` при вводе (SvcntrlCommands:763, 818, 898)
- Но `Project.branches` ConcurrentHashMap уже может содержать ключи в mixed-case (из старых версий), и миграция (ProjectManager:186-227) может создать `_conflict_` суффиксы

### 5.3 Неконсистентный стиль сообщений об ошибках
Некоторые ошибки используют `Text.translatable()`, другие — `Text.literal()`:
- `SvcntrlCommands.java:618`: `Text.literal("Project not found: " + name)` — hardcoded
- `SvcntrlCommands.java:638`: `Text.translatable("svcntrl.msg.project_not_found")` — через локализацию

Это приводит к невозможности полной локализации мода.

### 5.4 Ключи локализации обрезаны
**Файл:** `en_us.json`

Ключи вроде `svcntrl.msg.svcntrl_branch_checkout_name_n`, `svcntrl.msg.creating_auto_save_before_cros` выглядят автогенерированными и обрезанными. Это ухудшает читаемость и поддерживаемость.

### 5.5 Загадочный ключ локализации
**Файл:** `en_us.json:46`

```json
"svcntrl.msg.": "'..."
```

Пустой ключ с крайне сомнительным значением. Похоже на артефакт автогенерации.

### 5.6 Неконсистентный permission level
- `handleRaycastSelection` в `SvcntrlMod.java:159` проверяет `player.hasPermissionLevel(2)`
- `hasAdminBypass` в `SvcntrlCommands.java:1290` проверяет `Permissions.check(source, "svcntrl.admin", 3)`
- README говорит "OP Level 3+"

Три разных уровня проверки в разных местах.

### 5.7 `getProjects()` vs `getAllProjects()`
**Файл:** `ProjectManager.java:52-54, 130-132`

Два метода, возвращающих одно и то же (`projects.values()`), но один оборачивает в `unmodifiableCollection`, а другой — нет. Используются в разных местах без очевидной причины выбора.

---

## 6. UX-проблемы

### 6.1 Нет обратной связи при успешном trust/untrust уже существующего участника
**Файл:** `SvcntrlCommands.java:692-696`

```java
if (project.addMember(targetUuid)) {
    ...sendFeedback(...);
}
```

Если `addMember` вернёт `false` (игрок уже в проекте), **никакого сообщения не будет**. Игрок просто не получит ответ на свою команду. Аналогично для `removeMember`.

### 6.2 Нет команды `/svcntrl project list`
**README** обещает эту команду, но она **не реализована**. Игроки не могут посмотреть список своих проектов.

### 6.3 Нет пагинации для `project list` (если была бы реализована)
Для `log` есть пагинация, но если `project list` будет добавлен — нужна тоже (при 50+ проектах чат будет забит).

### 6.4 `/svcntrl outline` работает без активного проекта
**Файл:** `SvcntrlCommands.java:676-680`

Если у игрока нет активного проекта, outline всё равно "включается" (`toggleOutline` возвращает `true`), но частицы не будут отображаться. Игрок получит сообщение "enabled", не увидев результата.

### 6.5 `preview` не проверяет существование снапшота до начала
**Файл:** `SvcntrlCommands.java:1155`

Команда `executePreview` не проверяет, существует ли файл снапшота на диске, до начала асинхронной загрузки. Игрок увидит "Loading snapshot for preview..." и только потом "Failed to load snapshot".

### 6.6 Нет подтверждения для опасных операций
- `/svcntrl restore` сразу начинает восстановление без подтверждения
- `/svcntrl branch delete` — аналогично
- Только `/svcntrl project remove` имеет `force` подтверждение

### 6.7 Teleport при `branch checkout` может быть опасен
При checkout ветки, restore ставит блоки поверх текущего состояния. Если игрок стоит в зоне проекта, он может оказаться внутри блока. Нет предупреждения или автоматического телепорта в безопасное место.

### 6.8 Нет способа отменить текущую операцию save/restore
Если save или restore затянулись (большой регион), у игрока нет команды для отмены. Единственный способ — перезагрузить сервер.

### 6.9 Действия в log привязаны к текущей ветке
**Файл:** `SvcntrlCommands.java:999-1003`

Кнопки `[Preview]`, `[Export]`, `[Restore]` в `executeLog` генерируют команды вроде `/svcntrl preview start auto 5`. Если игрок переключится на другую ветку и нажмёт кнопку из старого лога, команда выполнится в контексте **новой** ветки, что может привести к непредсказуемым результатам.

### 6.10 `export all` всегда загружает на tmpfiles.org без подтверждения
**Файл:** `ExportManager.java:234-236`

`exportProjectFull` всегда вызывает `uploadToTmpfiles` без проверки `allowPublicExport`. Внутри `uploadToTmpfiles` проверка есть, но пользователь получит сообщение "Export saved to server folder: ..." даже если выгрузка не нужна — это запутывает.

---

## 7. Локализация

### 7.1 Русская локализация = копия английской (CRITICAL UX)
**Файлы:** `en_us.json`, `ru_ru.json`

`ru_ru.json` — **побайтовая копия** `en_us.json`. Ни одна строка не переведена. Файл полностью бесполезен.

### 7.2 Множество hardcoded строк
Следующие строки используют `Text.literal()` вместо `Text.translatable()` и **не могут быть переведены**:
- `"Already on branch "` (SvcntrlCommands:826)
- `"Project not found: "` (SvcntrlCommands:618)
- `"Branch '...' created."` (SvcntrlCommands:811)
- `"Checked out to branch '...'"` (SvcntrlCommands:866)
- `"Are you sure? Delete command: ..."` (SvcntrlCommands:641)
- `"Teleported to project '...'"` (SvcntrlCommands:630)
- `"Added/Removed ... to/from project."` (SvcntrlCommands:694, 710)
- `"Project outline enabled/disabled."` (SvcntrlCommands:679)
- `"Previewing ... Use /svcntrl preview stop to exit."` (PreviewManager:170)
- `"Auto-uploading ..."` (ExportManager:696)
- `"Export saved: ..."` (ExportManager:173)
- `"Upload failed (HTTP ...)"` (ExportManager:807)
- Многие другие

### 7.3 Незавершённые/пустые ключи
- `svcntrl.msg.` — пустой ключ (en_us.json:46)
- Ключи обрезаны до ~40 символов, теряя смысл

---

## 8. Архитектура и концептуальные проблемы

### 8.1 Singleton-антипаттерн повсюду
`ProjectManager`, `TaskScheduler`, `UXManager`, `PreviewManager`, `PendingCreateManager` — все static singleton через `INSTANCE`. Это:
- Делает unit-тестирование невозможным
- Создаёт проблемы при горячей перезагрузке (devtools)
- Не позволяет несколько экземпляров (multi-server в одном процессе)

### 8.2 `environment: "*"` в `fabric.mod.json`
**Файл:** `fabric.mod.json:12`

Мод заявлен как server-side, но `environment` установлен в `"*"` (и сервер, и клиент). Это означает, что мод будет загружен и на клиенте, где он бесполезен и может вызвать проблемы (например, mixin в `ServerCommonNetworkHandler` на клиенте).

Правильно: `"environment": "server"`

### 8.3 Mixin в `ServerCommonNetworkHandler` вместо `ServerPlayNetworkHandler`
**Файл:** `ServerPlayNetworkHandlerMixin.java:17`

```java
@Mixin(ServerCommonNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
```

Класс назван `ServerPlayNetworkHandler*Mixin*`, но мixin применяется к `ServerCommonNetworkHandler`. Это:
1. Перехватывает ВСЕ пакеты (включая configuration phase), а не только gameplay-пакеты
2. `instanceof ServerPlayNetworkHandler` check (L23) фильтрует, но overhead остаётся на каждый пакет
3. Может конфликтовать с другими модами, миксинящими тот же метод

### 8.4 Preview не блокирует save/restore
Если игрок A имеет preview на проекте, а игрок B (тоже member) выполнит restore, preview A станет некорректным — клиент A будет видеть старые (preview) блоки поверх новых. Нет механизма автоматического сброса preview при структурных изменениях проекта.

### 8.5 Проект привязан к одному миру, но нет multi-world UI
Проект хранит `worldId`, но при `select`/`tp` нет явной индикации мира. Если игрок на другом уровне (Nether/End), команды работают молча некорректно (save/restore в другом мире).

### 8.6 Нет undo для restore
`restore` создаёт auto-save перед выполнением, но нет явной команды "undo" которая бы откатила последний restore. Пользователь должен вручную найти auto-save в логах и восстановить его.

### 8.7 Snapshot ID'шники не глобально уникальны
Snapshot ID — это инкрементальный `int` per-branch. ID `1` в ветке `main` и ID `1` в ветке `dev` — это разные снапшоты. Это создаёт путаницу в UI при cross-branch операциях.

### 8.8 `taskBudgetNs` без валидации
**Файл:** `SvcntrlConfig.java:22`

Значение `taskBudgetNs` не валидируется. Если администратор поставит 0 или отрицательное значение, TaskScheduler не будет выполнять задачи вообще (остановка save/restore навсегда), а locked проекты навечно останутся locked. При слишком большом значении — TPS просядет.

---

## 9. Проблемы сборки и конфигурации

### 9.1 Loom SNAPSHOT-версия
**Файл:** `gradle.properties:13`

```
loom_version=1.10-SNAPSHOT
```

SNAPSHOT-версии нестабильны и могут ломать сборку при обновлении snapshot-репозитория. Рекомендуется использовать release-версию.

### 9.2 Отсутствие `.editorconfig` и `checkstyle`
Нет инструментов для enforce'инга code style, что приводит к неконсистентности (см. раздел 5).

### 9.3 Отсутствие тестов
Нет ни одного unit- или integration-теста. Для мода с таким количеством сложной логики (patch diff, litematica export, concurrent save/restore) это серьёзный риск.

---

## 10. Документация

### 10.1 README утверждение "thoroughly reviewed and tested" (L57)
```
This mod was built in collaboration with an AI agent. The codebase has been thoroughly reviewed and tested...
```

При наличии обнаруженных критических проблем это утверждение вводит в заблуждение.

### 10.2 README упоминает `/svcntrl project list` — не реализована
### 10.3 README не документирует:
- `/svcntrl autoupload`
- `/svcntrl deletesave`
- `/svcntrl pos1`/`pos2` как команды (только click-to-set упоминается)
- `/svcntrl tp` без аргумента (tp к активному проекту)
- `/svcntrl log auto/manual` с пагинацией
- `/svcntrl help`
- `/svcntrl reload`

### 10.4 `visual_diff_concept.md` — устаревший формат NBT
Концепт-документ упоминает `glow_color_override` и `block_state:{Name:...}` синтаксис, который мог измениться в 1.21.8.

---

## 11. Сводная таблица

| # | Серьёзность | Категория | Краткое описание | Файл |
|---|---|---|---|---|
| 1.1 | 🔴 CRITICAL | Security | SSRF через `customExportEndpoint` без валидации | `SvcntrlConfig`, `ExportManager` |
| 1.2 | 🔴 CRITICAL | Bug | Integer overflow в `blockData` массиве | `SaveTask:71` |
| 1.3 | 🟠 HIGH | Bug | Entity Duplication / утечка памяти через Preview | `PreviewManager:372` |
| 1.4 | 🟠 HIGH | Security | DoS через `NbtSizeTracker.ofUnlimitedBytes()` | `AreaSerializer` |
| 1.5 | 🟠 HIGH | UX/Bug | Команда `project list` не реализована | `SvcntrlCommands` |
| 2.1 | 🟡 MEDIUM | Bug | Расхождение индексации BlockEntity при Patch | `AreaSerializer:165` vs `SaveTask:151` |
| 2.2 | 🟡 MEDIUM | Concurrency | Неатомарный `toggleOutline` | `UXManager:37` |
| 2.3 | 🟡 MEDIUM | Bug | Неверные координаты BlockEntity в diff-экспорте | `ExportManager:596` |
| 2.4 | 🟡 MEDIUM | Bug | Нет проверки `isDisconnected()` в async callback'ах | Множество файлов |
| 2.5 | 🟡 MEDIUM | Concurrency | Race condition при `trimAutoSnapshots` + restore | `Project:121` |
| 2.6 | 🟡 MEDIUM | Bug | `removeProject` удаляет данные новых проектов | `ProjectManager:102` |
| 2.7 | 🟡 MEDIUM | Bug | NPE в `PaletteEntry.equals/hashCode` при null props | `ExportManager:80` |
| 3.1 | 🟡 MEDIUM | Concurrency | `HashSet` members без синхронизации | `Project:17` |
| 3.2 | 🟡 MEDIUM | Concurrency | Неатомарный `nextManualId++` | `Project:85` |
| 3.3 | 🟡 MEDIUM | Concurrency | Unsafe итерация synchronized list | `ProjectManager:413` |
| 3.4 | 🟡 MEDIUM | Concurrency | Гонка при `savePrefs` | `ProjectManager:292` |
| 4.1 | 🟡 MEDIUM | Performance | O(n²) entity diff | `AreaSerializer:193` |
| 4.2 | 🟡 MEDIUM | Performance | O(n) на каждый блок-ивент | `SvcntrlMod:174` |
| 4.3 | 🟡 MEDIUM | Performance | Raycast итерирует все проекты | `UXManager:74` |
| 4.4 | 🟢 LOW | Bug | Неправильная distance culling для outline | `UXManager:143` |
| 4.5 | 🟢 LOW | Bug | `tickCounter` overflow через ~3.4 года | `UXManager:106` |
| 4.6 | 🟢 LOW | Performance | `ForkJoinPool.commonPool()` overload | Множество файлов |
| 5.1 | 🟢 LOW | Code Quality | FQN vs imports смешение | Множество файлов |
| 5.2 | 🟢 LOW | Code Quality | Неконсистентная case-sensitivity имён | PM, Commands |
| 5.3 | 🟡 MEDIUM | Code Quality | Hardcoded strings vs translatable смешение | Commands |
| 5.4 | 🟢 LOW | Code Quality | Обрезанные ключи локализации | `en_us.json` |
| 5.5 | 🟢 LOW | Code Quality | Пустой ключ `svcntrl.msg.` | `en_us.json:46` |
| 5.6 | 🟡 MEDIUM | Security | Неконсистентный permission level (2 vs 3) | `SvcntrlMod`, `Commands` |
| 5.7 | 🟢 LOW | Code Quality | Дублирование `getProjects`/`getAllProjects` | `ProjectManager` |
| 6.1 | 🟢 LOW | UX | Нет feedback при повторном trust | `Commands:692` |
| 6.2 | 🟠 HIGH | UX | Нет `project list` | `Commands` |
| 6.3 | 🟢 LOW | UX | Нет пагинации project list | — |
| 6.4 | 🟢 LOW | UX | Outline без активного проекта | `Commands:676` |
| 6.5 | 🟢 LOW | UX | Preview не проверяет файл до начала | `Commands:1155` |
| 6.6 | 🟢 LOW | UX | Нет подтверждения для restore/branch delete | `Commands` |
| 6.7 | 🟢 LOW | UX | Teleport опасен при checkout | `Commands` |
| 6.8 | 🟢 LOW | UX | Нет отмены save/restore | — |
| 6.9 | 🟢 LOW | UX | Log кнопки не привязаны к ветке | `Commands:999` |
| 6.10 | 🟢 LOW | UX | Export all — запутывающий feedback | `ExportManager:234` |
| 7.1 | 🟠 HIGH | L10n | ru_ru.json = копия en_us.json | `ru_ru.json` |
| 7.2 | 🟡 MEDIUM | L10n | Множество hardcoded строк | Множество файлов |
| 7.3 | 🟢 LOW | L10n | Пустые/бессмысленные ключи | `en_us.json` |
| 8.1 | 🟢 LOW | Architecture | Singleton повсюду | Всё |
| 8.2 | 🟡 MEDIUM | Config | `environment: "*"` вместо `"server"` | `fabric.mod.json` |
| 8.3 | 🟡 MEDIUM | Architecture | Mixin на `ServerCommonNetworkHandler` | Mixin |
| 8.4 | 🟡 MEDIUM | Conceptual | Preview не блокирует save/restore | Preview/Area |
| 8.5 | 🟢 LOW | UX | Нет multi-world индикации | — |
| 8.6 | 🟢 LOW | UX | Нет undo для restore | — |
| 8.7 | 🟢 LOW | UX | Snapshot ID не глобально уникальны | `Project` |
| 8.8 | 🟡 MEDIUM | Config | `taskBudgetNs` без валидации | `SvcntrlConfig` |
| 9.1 | 🟢 LOW | Build | Loom SNAPSHOT-версия | `gradle.properties` |
| 9.3 | 🟡 MEDIUM | Quality | Нет тестов | — |
| 10.1 | 🟢 LOW | Docs | Вводящее в заблуждение утверждение в README | `README.md` |
| 10.2 | 🟢 LOW | Docs | Недокументированные команды | `README.md` |

---

**Итого найдено:** 2 Critical, 5 High, 22 Medium, 22 Low — **51 проблема**

> Этот документ — результат полного ревью кода. Никаких изменений в код не вносилось.
