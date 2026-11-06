# 小爱同学plus · UI 与模块架构

> 版本：0.7 规划（四页结构）
> 说明：Mermaid 图在 GitHub / 支持 Mermaid 的 Markdown 渲染器中可直接显示。

## 0. 总体架构

```mermaid
flowchart TB
    subgraph APP["模块 App · plus.xiaoi.module"]
        H["主页"]
        C["配置"]
        L["日志"]
        A["关于"]
        FAB["保存 FAB"]
    end

    subgraph DATA["数据层"]
        REPO["ConfigRepository<br/>唯一写入口 + 强校验"]
        JSON[("config.json<br/>providers / models / mcp")]
        PREFS[("SharedPreferences<br/>生效快照")]
    end

    subgraph BR["能力桥 · 小爱进程内 127.0.0.1"]
        MCP["POST /mcp"]
        LOGS["GET /logs"]
        ISL["POST /island"]
        CFGE["POST /config"]
    end

    subgraph XA["小爱 com.miui.voiceassist"]
        HOOK["Hook<br/>模型重定向 / 请求头标识"]
        BUF["日志环形缓冲 300 条"]
    end

    H --> REPO
    C --> REPO
    L --> LOGS
    A --> H
    C --> MCP
    FAB --> REPO
    REPO --> JSON
    REPO --> PREFS
    PREFS --> HOOK
    HOOK --> BUF
    BUF --> LOGS
    MCP --> HOOK
    CFGE --> PREFS
    ISL --> BUF
```

**文字版（同样的关系）**

```
模块 App
 ├─ 主页 ──┐
 ├─ 配置 ──┼─> ConfigRepository（校验 / 原子写 / 单一入口）
 ├─ 日志 ──┘        ├─> config.json（唯一事实源）
 ├─ 关于              └─> SharedPreferences（生效快照）
 └─ FAB(保存)              │
                           ▼
                    小爱进程 800ms 热读
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   Hook: 重定向/标识    日志环形缓冲        能力桥
                                          /mcp /logs /island /config
```

## 1. 主页

```mermaid
flowchart TB
    T["顶栏：模块图标 · 小爱同学plus · 暂停/恢复"]
    HERO["Hero 状态面<br/>已启用/未配置/已暂停 · 徽章 · 模型 · 版本"]
    TILES["双数字砖<br/>今日请求 N ｜ 日志 N 条"]
    ACT["快捷动作<br/>测试连接 · 切换模型 · 显示超级岛"]
    INFO["信息流卡<br/>当前模型 / 接口地址 / 内核 / Android / Root / 版本"]
    ERR["异常单行（可选，点按跳日志）"]
    NAV["经典导航：主页 · 配置 · 日志 · 关于"]

    T --> HERO --> TILES --> ACT --> INFO --> ERR --> NAV
```

数据来源：`ConfigRepository`（配置）、能力桥 `/health`（在线）、`/logs`（计数）。

## 2. 配置

```mermaid
flowchart TB
    EN["总开关：启用第三方模型"]
    subgraph MODEL["模型板块（开关开启后可编辑）"]
        PV["服务商选择"]
        MD["模型选择 / 添加"]
        KEY["密钥"]
    end
    subgraph MCPS["MCP 板块"]
        M1["开关"]
        M2["端口（leader 上报；未上报时 8771 提示）"]
        M3["令牌 · 生成"]
        M4["复制连接信息（动态生成）"]
        M5["复制 Skill 文本"]
    end
    subgraph EDIT["配置编辑"]
        E1["导出 / 备份（SAF）"]
        E2["还原（SAF + 校验）"]
        E3["分享（FileProvider · 含密钥，注意保管）"]
    end
    FAB["保存 FAB（常驻，脏时高亮）"]

    EN --> MODEL
    EN --> MCPS
    MODEL --> FAB
    MCPS --> FAB
    EDIT --> FAB
```

校验规则：`baseUrl` http(s)、`model` 非空、`apiKey` 非空；不通过则阻止开启并提示。

### 2.1 配置页类结构（0.12.0 WP5，机械拆分）

`ConfigActivity` 只保留状态所有权与流程编排；纯视图/对话框移到 `plus.xiaoi.module.ui.config`，
不改变任何布局 ID、文案与点击行为。Section 不持有 Activity：构造时传入 root View、宿主
`AppConfig` 读取接口与必要的 `Runnable`/小接口，状态更新仍回调 Activity。

```mermaid
flowchart TB
    A["ConfigActivity（shell）<br/>cfg / dirty / rendering / statusState / savedWaiting / ui Handler<br/>生命周期 · render() · save() · collect() · checkModelNow()"]
    D["ConfigDialogs<br/>对话框容器 / 管理行 / 输入框 / 错误行"]
    M["McpSection<br/>开关联动视图 / 令牌 / URL / JSON 速览 / 复制 / 端口探测"]
    S["StatusSection<br/>状态条 / 推送行 / 文件冲突条 / 运行时冲突条"]
    E["EditSection<br/>导入 / 脱敏导出 / 完整备份 / 还原 / 分享"]
    R["ModelEditor<br/>服务商/模型管理列表与编辑对话框"]
    P["AppearanceSection<br/>主题 chips"]

    A --> D
    A --> M
    A --> S
    A --> E
    A --> R
    A --> P
```

模型 chips 的渲染与“选中服务商/模型”仍留在 `ConfigActivity`（它们同时改 cfg、状态条与自动校验），
`ModelEditor` 只接管管理/编辑对话框。文件选择器结果仍由 Activity 的 `onActivityResult`
转发给 `EditSection`。

## 3. 日志（独立页）

```mermaid
flowchart TB
    BAR["顶栏：级别筛选 · 清空 · 分享"]
    FOLLOW["跟随最新（开关）"]
    LIST["列表行：时间 · 级别胶囊 · 正文<br/>错误行左侧红条"]
    EMPTY["空状态：未运行 / 暂无日志"]
    SRC["数据源：GET /logs?limit=&since=<br/>环形缓冲 300 条"]

    BAR --> FOLLOW --> LIST
    LIST --> EMPTY
    SRC --> LIST
```

## 4. 关于

```mermaid
flowchart TB
    H["Hero：图标 · 小爱同学plus · v0.6.0 (6) · 已安装"]
    INFO["信息卡：作者 / GitHub / 许可证 / 致谢 / 检查更新"]
    DISC["免责声明卡"]
    BUILD["构建信息：日期 · commit"]

    H --> INFO --> DISC --> BUILD
```

链接：`https://github.com/xuanyuanluoxue/xiaoi-plus`（作者 轩辕落雪，GPL-3.0）。

## 5. 数据流

```mermaid
sequenceDiagram
    participant U as 用户
    participant UI as 模块 App
    participant R as ConfigRepository
    participant J as config.json
    participant P as 小爱 prefs
    participant X as 小爱进程

    U->>UI: 修改配置 / 点 FAB
    UI->>R: 校验
    R->>J: 原子写（tmp + rename）
    R->>P: 写生效快照
    P-->>X: mtime 变化，800ms 内热读
    X-->>UI: /health 反映在线
```

```mermaid
sequenceDiagram
    participant A as 电脑端 Agent
    participant ADB as adb forward
    participant B as 能力桥
    participant X as 小爱进程

    A->>ADB: adb forward tcp:8771 tcp:8771
    A->>B: POST /mcp (initialize)
    B->>X: Island / CLI
    X-->>B: 结果
    B-->>A: JSON-RPC
```

## 6. 深色模式

```mermaid
flowchart LR
    S["ThemeManager<br/>prefs 唯一真源"] --> R["attachBaseContext<br/>uiMode 覆写"]
    R --> C1["资源限定符<br/>values / values-night"]
    C1 --> P1["主页"]
    C1 --> P2["配置"]
    C1 --> P3["日志"]
    C1 --> P4["关于"]
    S --> T["onResume 比对<br/>不一致则 recreate"]
    T --> P1
    T --> P2
    T --> P3
    T --> P4
```

- 主题不写入 `config.json`，`ConfigSync` 也不会再回写 prefs
- 任何页面切换主题后，其余页面在下次 `onResume` 时自动重建
- 配置页切主题时未保存的编辑随 Bundle 恢复，不会被文件版本覆盖
