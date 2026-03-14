# GPS Joystick `.db` 合併工具

合併多個 GPS Joystick 的 Realm `.db` 檔案，透過二進位層級操作將座標資料整合到單一資料庫中。

---

## 目錄

- [資料格式詳解](#資料格式詳解)
  - [檔案總覽](#檔案總覽)
  - [節點標記系統](#節點標記系統)
  - [節點標頭格式](#節點標頭格式)
  - [節點類型一覽](#節點類型一覽)
  - [B-Tree 索引結構](#b-tree-索引結構)
  - [資料表與欄位定義](#資料表與欄位定義)
  - [座標儲存機制](#座標儲存機制)
  - [字串儲存機制](#字串儲存機制)
- [合併原理與流程](#合併原理與流程)
- [合併時注意事項](#合併時注意事項)
- [App 使用說明](#app-使用說明)
- [專案結構](#專案結構)

---

## 資料格式詳解

### 檔案總覽

GPS Joystick 使用 Realm 資料庫引擎儲存路線資料。`.db` 檔案是一個二進位 Realm 檔案，具有以下特徵：

| 項目 | 說明 |
|------|------|
| 檔案格式 | Realm Binary Database (非 SQLite) |
| 識別標頭 | `T-DB` (0x54 0x2D 0x44 0x42) 位於 offset 0x10 |
| 位元組序 | 資料值為 **Little-Endian**，節點計數欄位為 **Big-Endian** |
| 節點標記 | ASCII `AAAA` (0x41 0x41 0x41 0x41) |

**檔案標頭結構 (前 32 bytes):**

```
Offset  Hex                                ASCII
0x00    FF FF FF FF FF FF FF FF            ........     (檔案元資料/版本指標)
0x08    xx xx xx xx 00 00 00 00            ....         (檔案大小相關)
0x10    54 2D 44 42 07 07 00 01            T-DB....     (Realm 格式標頭)
0x18    41 41 41 41 0E 00 00 07            AAAA....     (第一個節點：Schema Root)
```

兩個範例檔案的對比：

| 檔案 | 大小 | AAAA 標記數 | 0x0C 節點 | Lat 葉節點 | 座標容量 | 路線 UUID 數 |
|------|------|-------------|-----------|-----------|---------|-------------|
| `gpsjoystick_20200523234823.db` | 128 KB | 163 | 23 | 5 | 4,674 | 40 |
| `gpsjoystick_20260302212225.db` | 2 MB | 1,573 | 218 | 68 | 67,674 | 575 |

### 節點標記系統

Realm 檔案中的所有結構單元都以 ASCII `AAAA` (4 bytes: 0x41 0x41 0x41 0x41) 作為識別標記。

**辨別真正的節點標記 vs 資料中的 0x41 位元組：**
- 真正的節點標記後面接的是類型位元組 (非 0x41)
- 如果連續出現超過 4 個 0x41，則非標記 (出現在 type 0x44 等資料內容中)

### 節點標頭格式

**每個節點的標頭固定為 8 bytes：**

```
偏移    大小    說明
+0      4B      AAAA 標記 (0x41414141)
+4      1B      節點類型 (Type)
+5..+7  3B      計數/長度 (3-byte Big-Endian 整數)
+8      ...     節點資料區 (格式依類型而定)
```

**計數欄位讀取方式：**

```
count = (byte[+5] << 16) | (byte[+6] << 8) | byte[+7]
```

**實例解析：**

```
41 41 41 41  0C  00 03 E8  [Float64 data...]
├─ AAAA ─┤  │   ├─ count ─┤
         type   = (0x00 << 16) | (0x03 << 8) | 0xE8
                = 0x0003E8 = 1000 個 Float64 值

41 41 41 41  11  00 53 1B  [String data...]
├─ AAAA ─┤  │   ├─ count ─┤
         type   = 0x00531B = 21,275 bytes 字串資料

41 41 41 41  C6  00 00 07  [uint32 refs...]
├─ AAAA ─┤  │   ├─ count ─┤
         type   = 7 個 uint32 參考指標
```

### 節點類型一覽

#### 資料節點

| Type | 名稱 | 計數含義 | 資料格式 | 說明 |
|------|------|---------|---------|------|
| `0x0C` | Float64 葉節點 | Float64 值數量 | `count × 8 bytes` (LE Float64) | 儲存座標 (緯度/經度/海拔) |
| `0x11` | 字串節點 | 位元組長度 | `count bytes` (UTF-8, null 分隔) | 路線 UUID、標記名稱、URL |
| `0x44` | 整數葉節點 | 項目數量 | 整數陣列 | 排序索引、關聯 ID |
| `0x05` | 索引陣列 | 項目數量 | uint16 陣列 | B-Tree 內部排序索引 |
| `0x06` | 參考陣列 | 項目數量 | 混合格式 | 跨表關聯參考 |

#### 結構節點

| Type | 名稱 | 計數含義 | 資料格式 | 說明 |
|------|------|---------|---------|------|
| `0x0E` | Schema Root | 表數量 | 混合 | 整個資料庫的 Schema 定義起點 |
| `0x0D` | 欄位定義 | 欄位數量 | 固定長度字串 | 表的欄位名稱 (latitude, longitude 等) |
| `0x03` | 表定義 | 欄位數量 | 混合 | 表的結構定義和 B-Tree 參考 |
| `0x46` | 欄位指標陣列 | 指標數量 | `count × 4 bytes` (LE uint32) | 指向各欄位 B-Tree 根節點 |
| `0x45` | 結構參考 | 參考數量 | uint16 或 uint32 | 內部結構連結 |

#### B-Tree 索引節點

| Type | 名稱 | 計數含義 | 資料格式 | 說明 |
|------|------|---------|---------|------|
| `0xC5` | B-Tree 根 (小) | 參考數量 | `count × 2 bytes` (**LE uint16**) | 資料量小時使用，指向葉節點 |
| `0xC6` | B-Tree 索引 (大) | 參考數量 | `count × 4 bytes` (**LE uint32**) | 資料量大時使用，指向葉節點或子索引 |

### B-Tree 索引結構

Realm 使用 B-Tree 來組織每個資料欄位 (Column) 的資料。理解 B-Tree 結構是正確合併的關鍵。

#### 層級架構

```
0x46 (欄位指標陣列)
 ├── [0] ──→ 0xC5/0xC6 ──→ latitude 葉節點群 (0x0C)
 ├── [1] ──→ 0xC5/0xC6 ──→ longitude 葉節點群 (0x0C)
 ├── [2] ──→ 0xC5/0xC6 ──→ altitude 葉節點群 (0x0C)
 └── [3] ──→ 0xC5/0xC6 ──→ 其他欄位 (0x44 等)
```

#### 0xC5 / 0xC6 參考陣列的讀取規則

**重要：首尾項目是元資料 (metadata sentinel)，不是子節點指標！**

```
0xC5 節點 (uint16 refs):
  refs[0]       = 0x07D1  ← metadata (可能為總記錄數或邊界值)
  refs[1]       = 0x0278  ← 第 1 個葉節點偏移
  refs[2]       = 0x21C0  ← 第 2 個葉節點偏移
  ...
  refs[N-2]     = 0x7F98  ← 最後一個葉節點偏移
  refs[N-1]     = 0x2485  ← metadata sentinel

0xC6 節點 (uint32 refs):
  refs[0]       = 0x000007D1  ← metadata
  refs[1]       = 0x000094C8  ← 第 1 個葉節點偏移
  ...
  refs[N-2]     = 0x000111E8  ← 最後一個葉節點偏移
  refs[N-1]     = 0x00002485  ← metadata sentinel
```

兩種類型的 metadata sentinel 值相同 (0x07D1 和 0x2485)，只是儲存寬度不同。

#### 實例：128KB 檔案的 B-Tree 追蹤

```
0x46 at 0x1CC20 (4 entries = 4 columns of class_CoordinateData):
  ├── [0] = 0x94B0 → 0xC5 (7 uint16 refs)
  │         refs[1..5] = [0x0278, 0x21C0, 0x4108, 0x6050, 0x7F98]
  │         → 5 個 latitude 葉節點
  │         → 首值: 25.006° (台灣緯度)
  │
  ├── [1] = 0x12700 → 0xC6 (7 uint32 refs)
  │         refs[1..5] = [0x94C8, 0xB410, 0xD358, 0xF2A0, 0x111E8]
  │         → 5 個 longitude 葉節點
  │         → 首值: 121.521° (台灣經度)
  │
  ├── [2] = 0x1B960 → 0xC6 (7 uint32 refs)
  │         → 5 個 altitude 葉節點 (大部分為 NaN/未填入)
  │
  └── [3] = 0x1CBF8 → 0xC6
            → 指向 0x44 類型節點 (排序/速度等輔助資料)
```

#### 0xC5 vs 0xC6 的選擇規則

| 條件 | 使用類型 | 說明 |
|------|---------|------|
| 所有葉節點偏移 < 0xFFFF | 0xC5 (uint16) | 檔案較小時，偏移值可用 2 bytes 表示 |
| 任一葉節點偏移 ≥ 0x10000 | 0xC6 (uint32) | 檔案較大時，需要 4 bytes 偏移 |

128KB 檔案的 latitude 使用 0xC5 (偏移均 < 64KB)，而 longitude 使用 0xC6 (偏移如 0x111E8 > 64KB)。

### 資料表與欄位定義

GPS Joystick 的 `.db` 定義了 5 個 Realm Class：

| Class 名稱 | 偏移 | 說明 |
|-----------|------|------|
| `class_CoordinateData` | 0x60 | 座標資料 (經緯度、海拔) — **合併核心** |
| `class_MarkerData` | 0x80 | 標記點資料 |
| `class_MarkerTypeData` | 0xA0 | 標記類型定義 |
| `class_PlaceLocationData` | 0xC0 | 地點位置資料 |
| `class_RouteData` | 0xE0 | 路線定義 (包含路線名稱 UUID) |

**class_CoordinateData 的欄位結構：**

```
欄位名稱     資料類型    B-Tree 欄位索引    說明
latitude    Float64     [0]               緯度 (-90 ~ +90)
longitude   Float64     [1]               經度 (-180 ~ +180)
altitude    Float64     [2]               海拔 (公尺，常為 0 或 NaN)
(pk/other)  Int/Mixed   [3]               主鍵/排序索引
```

### 座標儲存機制

#### Float64 葉節點 (Type 0x0C)

每個葉節點儲存最多 **1000** 個 Float64 值 (8 bytes each = 8000 bytes 資料區)。
較小的「尾端」葉節點可能存放 674 或其他數量的值。

```
節點結構：
+0x00  41 41 41 41          AAAA 標記
+0x04  0C                   類型 = Float64 葉節點
+0x05  00 03 E8             計數 = 1000 (3-byte BE)
+0x08  EA 8B 84 B6 9C 01 39 40  Float64 LE = 25.006297... (latitude)
+0x10  58 BC BF 55 00 02 39 40  Float64 LE = 25.007817...
...
+0x1F48  (最後一個 Float64 值)
+0x1F50  (下一個節點的 AAAA)
```

#### 座標在 B-Tree 中的分布

latitude 和 longitude 分別有自己的 B-Tree，葉節點在檔案中**不一定**連續排列：

```
檔案偏移:  0x278   0x21C0  0x4108  ...  0x94C8  0xB410  0xD358  ...
           ├── lat ──────────────┤    ├── lon ──────────────────┤
           5 個葉節點                  5 個葉節點
```

latitude 和 longitude 的葉節點數量**永遠相同** (每個座標點同時有一個 lat 值和一個 lon 值)。

### 字串儲存機制

#### String 節點 (Type 0x11)

字串節點使用 **null byte (0x00)** 分隔多個字串：

```
+0x00  41 41 41 41 11 00 00 1B     AAAA + type + 27 bytes
+0x08  4C 61 6E 64 6D 61 72 6B     "Landmark"
+0x10  00                          null 分隔符
+0x11  52 65 63 72 65 61 74 69     "Recreational Area"
+0x19  6F 6E 61 6C 20 41 72 65
+0x21  61 00 00 00 00 00 00        null + padding
```

#### 路線 UUID 字串節點

路線識別使用 UUID v4 格式 (36 字元)，儲存在大型 0x11 節點中：

```
UUID 格式: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
範例:      b87bd3a5-132a-406c-a9a3-3625a9d13386

大型 UUID 節點: count = 21,275 bytes, 包含 575 個 UUID
每個 UUID 佔 37 bytes (36 字元 + 1 null separator)
```

---

## 合併原理與流程

### 核心策略：模板原地覆寫 (Template In-place Merge)

由於 Realm 的內部結構極為嚴密 (B-Tree 索引、偏移指標等)，不可能透過「拼接」兩個檔案來合併。
正確的方式是選擇較大的檔案作為**模板 (Host)**，在不改變其結構的前提下，**只覆寫葉節點的值**。

### 合併流程

```
步驟 1: 選擇模板
  ├── 比較兩個 .db 檔案大小
  └── 較大者作為 Host (模板)，較小者作為 Guest (來源)

步驟 2: B-Tree 追蹤
  ├── 掃描所有 AAAA 標記，建立節點索引
  ├── 找到 class_CoordinateData 的 0x46 欄位指標陣列
  ├── 追蹤 [0] → latitude B-Tree → 所有 lat 葉節點
  └── 追蹤 [1] → longitude B-Tree → 所有 lon 葉節點

步驟 3: 提取座標
  ├── 從 Host 的 lat/lon 葉節點讀取所有 finite Float64 值
  └── 從 Guest 的 lat/lon 葉節點讀取所有 finite Float64 值

步驟 4: 組合座標陣列
  ├── combined_lat = [host_lat..., gap_lat, guest_lat...]
  ├── combined_lon = [host_lon..., gap_lon, guest_lon...]
  └── gap = host 最後一點的緯度 +3° (≈ 333km，超過 300km 閾值)

步驟 5: 覆寫模板
  ├── 將 combined_lat 依序寫入 Host 的 lat 葉節點
  ├── 將 combined_lon 依序寫入 Host 的 lon 葉節點
  ├── 超出容量的 Guest 座標被截斷
  └── 剩餘空槽填入最後一個有效值 (避免 NaN)

步驟 6: 更新路線 UUID
  ├── 找到包含 UUID 的 0x11 字串節點
  └── 將 Host + Guest 的 UUID 合併寫入 (受限於節點容量)

步驟 7: 驗證
  ├── 檔案大小 == 原始模板大小 (必須完全一致)
  ├── AAAA 標記總數未變動 (變動代表偏移錯位)
  └── 所有 Float64 值通過 isFinite() 檢查
```

### 300km 間隔跳轉原理

GPS Joystick 在讀取軌跡時，如果相鄰兩點距離超過 300km，會自動視為不同路徑片段。
利用這個特性，在 Host 最後一點和 Guest 第一點之間插入一個「跳轉點」：

```
... Host 路線終點 (25.030°N, 121.530°E)
    ↓ 插入跳轉點 (28.030°N, 121.530°E)  ← 緯度 +3° ≈ 333km
    ↓
... Guest 路線起點 (35.630°N, 139.880°E)
```

---

## 合併時注意事項

### 1. 容量限制 (最重要)

**模板的容量是固定的。** 合併後的總座標數不能超過模板的 lat 葉節點總容量。

| 情境 | 說明 | 處理方式 |
|------|------|---------|
| Host 已滿載 | Host 的所有座標槽位都有有效資料 | **無法合併。** 必須先在 GPS Joystick 中建立一條很長的空路徑來擴張 `.db` 容量 |
| 部分空間 | Host 有部分空槽 | Guest 資料被截斷至可用空間 |
| 充足空間 | Host 空槽 > Guest 座標數 | 完整合併 |

**典型場景：** 範例中的 2MB 檔案有 67,674 個座標槽位且全部使用中，128KB 檔案有 4,674 個座標需要放入。若直接合併，空間不足。

**解決方案：**
1. 在 GPS Joystick 中建立一條包含大量空點的路線 (例如直線往返數萬點)
2. 匯出新的 `.db`，此檔案會有更多的 0x0C 葉節點
3. 使用此新檔案作為合併模板

### 2. 檔案大小不可變

合併後的檔案大小**必須與模板完全一致** (byte-for-byte)。增加或減少任何位元組都會使 Realm 內部偏移失效，導致 GPS Joystick 崩潰或無法開啟。

### 3. AAAA 標記數量不可變

合併過程中只能修改葉節點的**資料內容**，不能增刪節點。如果合併後的 AAAA 標記數量與原始不同，代表覆寫時發生了偏移錯位，檔案已損毀。

### 4. Little-Endian 寫入

所有 Float64 座標值寫入時必須使用 **Little-Endian** 格式。錯誤的位元組序會產生無效座標。

### 5. 避免 NaN 和 Infinity

寫入的 Float64 值必須全部通過 `isFinite()` 檢查。NaN 或 Infinity 座標會導致 GPS Joystick 閃退。空槽應填入最後一個有效座標值 (重複填充)，不要填 0.0 (0.0 是有效座標但指向赤道/本初子午線)。

### 6. 路線名稱 (UUID) 的限制

- 路線名稱儲存在 0x11 字串節點中，容量固定
- 如果合併的 UUID 總數超過字串節點容量，多餘的 UUID 會被截斷
- 被截斷的路線仍可透過 300km 跳轉法在地圖上區分，只是沒有獨立名稱

### 7. 海拔和速度資料 (Run 2)

- 合併目前只處理 latitude (Column 0) 和 longitude (Column 1)
- altitude (Column 2) 保留模板原始值 (通常為 0 或 NaN)
- 如需保留海拔，需額外同步覆寫 Column 2 的葉節點

### 8. 備份原始檔案

**永遠在合併前備份原始 `.db` 檔案。** 合併是不可逆的覆寫操作。

### 9. B-Tree 索引不需更新

由於我們只覆寫葉節點的值而不改變節點位置或數量，B-Tree 的所有索引節點 (0xC5, 0xC6) 和結構節點 (0x46, 0x45 等) 都保持不變。GPS Joystick 會照常透過索引找到葉節點，只是讀到的值已經是合併後的新座標。

### 10. 多檔案合併

如果需要合併 3 個以上的檔案：
1. 先用最大的檔案作為模板，合併第 2 個檔案
2. 將合併結果作為新模板，合併第 3 個檔案
3. 依此類推

每次合併都會在路線銜接處插入 300km 跳轉點。

---

## App 使用說明

### 系統需求

- Android 8.0 (API 26) 以上
- 不需要特殊權限 (使用 SAF 檔案選擇器)

### 操作步驟

1. 開啟 App
2. 點擊「選擇檔案」分別載入兩個 `.db` 檔案
3. App 會自動分析並顯示：
   - 檔案大小、AAAA 標記數
   - Float64 節點數、座標容量
   - 路線 UUID 數量
   - 哪個檔案將作為模板 (主容器)
4. 查看「合併檢查」結果確認可行性
5. 點擊「合併」按鈕，選擇輸出檔案位置
6. 檢查驗證結果 (大小、標記數、Float64 有效性)
7. 將輸出檔案複製回 GPS Joystick 的資料目錄

---

## 專案結構

```
MergeDbApp/
├── app/src/main/java/com/mergedb/app/
│   ├── db/
│   │   ├── RealmNode.kt           # 節點資料類別
│   │   ├── RealmBinaryParser.kt   # 二進位解析 + B-Tree 追蹤
│   │   ├── MergeEngine.kt         # 合併引擎 (模板覆寫演算法)
│   │   └── MergeValidator.kt      # 合併後驗證
│   ├── ui/
│   │   ├── MergeScreen.kt         # Jetpack Compose 主畫面
│   │   └── theme/Theme.kt         # Material3 主題
│   ├── MainActivity.kt            # SAF 檔案選擇 + Compose 入口
│   └── MainViewModel.kt           # 狀態管理 + 合併流程控制
└── build.gradle.kts               # Kotlin 1.9 + Compose + SDK 34
```
