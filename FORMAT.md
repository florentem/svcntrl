# Svcntrl Snapshot Format

Снапшоты мода Svcntrl сохраняются в бинарном сжатом (GZIP) формате `.nbt`. 
Здесь описана внутренняя структура NBT (версия 2).

## Структура файла (V2)

Все данные лежат в корневом `NbtCompound`.

```json
{
  "Version": 2, // Версия формата. 1 - старая (тяжелая), 2 - новая с палитрой
  "MinX": int,
  "MinY": int,
  "MinZ": int,
  "MaxX": int,
  "MaxY": int,
  "MaxZ": int,

  // Палитра уникальных блоков
  "Palette": [
    {
      "BlockId": "minecraft:stone", // Строковый ID блока
      "Properties": {               // NbtCompound со свойствами (опционально)
        "facing": "north",
        "waterlogged": "false"
      }
    },
    // ...
  ],

  // Массив блоков. Размер = Width * Height * Length
  // Индекс блока считается как: (x - minX) + (y - minY) * Width + (z - minZ) * Width * Height
  // Значение элемента — это индекс (int) в массиве Palette.
  "BlockData": [int, int, int...],

  // Данные BlockEntities (сундуки, печи)
  "BlockEntities": [
    {
      "X": int, // Относительная координата X (от MinX)
      "Y": int, // Относительная координата Y (от MinY)
      "Z": int, // Относительная координата Z (от MinZ)
      "Data": { // NbtCompound самого BlockEntity без абсолютных координат
        "id": "minecraft:chest",
        "Items": [...]
      }
    }
  ],

  // Данные обычных сущностей (Entities: рамки, картины, стойки)
  "Entities": [
    {
      // Оригинальный NBT сущности, созданный через entity.saveSelfData()
      // К нему добавляются 3 поля с относительными координатами спавна:
      "svcntrl_RelX": double,
      "svcntrl_RelY": double,
      "svcntrl_RelZ": double,

      // Если это прикрепленная сущность (рамка, картина), добавляются координаты блока, к которому она прикреплена:
      "svcntrl_AttachRelX": int,
      "svcntrl_AttachRelY": int,
      "svcntrl_AttachRelZ": int,

      "id": "minecraft:item_frame",
      "Item": {...},
      // ... другие поля сущности ...
    }
  ]
}
```

## Устаревший формат (V1)
Формат V1 использовал огромный NbtList `Blocks`, где каждый элемент хранил свои координаты `X`, `Y`, `Z`, `BlockId` и `Properties`. Это занимало в 50-100 раз больше места на диске и в оперативной памяти. Формат V1 поддерживается только для обратной совместимости (только чтение). Новые снапшоты всегда сохраняются в V2.
