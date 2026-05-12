# 筑规通桌面客户端 - 阶段1：基础框架实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 Flutter + Rust 跨平台桌面应用的基础框架，建立 FFI 通信桥接。

**Architecture:** Flutter 作为 UI 层负责用户界面，Rust 作为核心服务层处理业务逻辑，通过 flutter_rust_bridge v2 实现 FFI 通信。采用 Flutter 3.22+ 和 Rust 1.78+。

**Tech Stack:** Flutter 3.22+, Rust 1.78+, flutter_rust_bridge v2, Riverpod 2.x, Tokio 1.x

---

## 文件结构规划

```
zhu-gui-tong-desktop/
├── app/                              # Flutter 应用
│   ├── lib/
│   │   ├── main.dart                 # 应用入口
│   │   ├── app.dart                  # 根组件
│   │   ├── bridge/                   # Rust FFI 桥接
│   │   │   ├── bridge.dart           # 桥接入口
│   │   │   └── generated.dart        # 自动生成
│   │   ├── features/
│   │   │   ├── chat/
│   │   │   │   └── chat_page.dart
│   │   │   ├── knowledge/
│   │   │   │   └── knowledge_page.dart
│   │   │   ├── settings/
│   │   │   │   └── settings_page.dart
│   │   │   └── profile/
│   │   │       └── profile_page.dart
│   │   ├── shared/
│   │   │   ├── widgets/
│   │   │   │   ├── sidebar.dart
│   │   │   │   ├── title_bar.dart
│   │   │   │   └── status_bar.dart
│   │   │   ├── theme/
│   │   │   │   ├── app_theme.dart
│   │   │   │   └── colors.dart
│   │   │   └── utils/
│   │   │       └── platform.dart
│   │   └── providers/
│   │       ├── app_provider.dart
│   │       └── settings_provider.dart
│   ├── pubspec.yaml
│   ├── analysis_options.yaml
│   └── linux/
│       └── ...
│
├── core/                             # Rust 核心服务
│   ├── src/
│   │   ├── lib.rs                    # 库入口
│   │   ├── error.rs                  # 错误定义
│   │   ├── config/
│   │   │   ├── mod.rs
│   │   │   └── settings.rs
│   │   ├── storage/
│   │   │   ├── mod.rs
│   │   │   └── sqlite.rs
│   │   └── bridge/
│   │       └── mod.rs                # FFI 导出
│   ├── Cargo.toml
│   └── build.rs
│
├── scripts/
│   ├── setup.sh
│   └── build.sh
│
├── docs/
│   └── ARCHITECTURE.md
│
├── .gitignore
├── README.md
└── Makefile
```

---

## Task 1: 项目初始化与目录结构

**Files:**
- Create: `zhu-gui-tong-desktop/.gitignore`
- Create: `zhu-gui-tong-desktop/README.md`
- Create: `zhu-gui-tong-desktop/Makefile`

- [ ] **Step 1: 创建项目根目录和基础文件**

```bash
mkdir -p zhu-gui-tong-desktop/{app,core,scripts,docs,skills/builtin,skills/custom,native}
cd zhu-gui-tong-desktop
```

- [ ] **Step 2: 创建 .gitignore 文件**

```gitignore
# Flutter
app/.dart_tool/
app/.packages
app/.pub/
app/.pub-cache/
app/build/
app/.flutter-plugins
app/.flutter-plugins-dependencies
app/linux/flutter/ephemeral/

# Rust
core/target/
core/Cargo.lock

# IDE
.idea/
.vscode/
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

# Build
dist/
*.deb
*.rpm
*.AppImage
*.exe
*.msi

# Logs
*.log
logs/

# Environment
.env
.env.local
```

- [ ] **Step 3: 创建 README.md 文件**

```markdown
# 筑规通桌面客户端

基于 Flutter + Rust 的跨平台建筑行业规范智能问答系统。

## 技术栈

- **UI 层**: Flutter 3.22+ + Riverpod
- **核心服务**: Rust 1.78+ + Tokio
- **FFI 桥接**: flutter_rust_bridge v2

## 开发环境

### 依赖要求

- Flutter 3.22+
- Rust 1.78+
- LLVM/Clang (用于 FFI)

### 快速开始

```bash
# 安装依赖
make setup

# 开发模式
make dev

# 构建
make build
```

## 项目结构

- `app/` - Flutter 应用
- `core/` - Rust 核心服务
- `scripts/` - 构建脚本
- `docs/` - 文档

## License

MIT
```

- [ ] **Step 4: 创建 Makefile**

```makefile
.PHONY: setup dev build clean test

# 设置开发环境
setup:
	cd app && flutter pub get
	cd core && cargo build

# 开发模式
dev:
	cd app && flutter run -d linux

# 构建
build:
	cd core && cargo build --release
	cd app && flutter build linux --release

# 清理
clean:
	cd app && flutter clean
	cd core && cargo clean

# 测试
test:
	cd core && cargo test
	cd app && flutter test

# 生成 FFI 桥接
codegen:
	cd app && dart run flutter_rust_bridge:generate
```

- [ ] **Step 5: 提交初始化文件**

```bash
git init
git add .gitignore README.md Makefile
git commit -m "chore: initialize project structure"
```

---

## Task 2: Rust 核心服务框架

**Files:**
- Create: `core/Cargo.toml`
- Create: `core/src/lib.rs`
- Create: `core/src/error.rs`
- Create: `core/src/config/mod.rs`
- Create: `core/src/config/settings.rs`
- Create: `core/src/storage/mod.rs`
- Create: `core/src/storage/sqlite.rs`

- [ ] **Step 1: 创建 Cargo.toml 配置**

```toml
[package]
name = "zhu-gui-tong-core"
version = "0.1.0"
edition = "2021"
authors = ["ZhuGuiTong Team"]
description = "Core service for ZhuGuiTong desktop client"

[lib]
name = "zhu_gui_tong_core"
crate-type = ["lib", "cdylib", "staticlib"]

[dependencies]
# Async runtime
tokio = { version = "1.38", features = ["full"] }
futures = "0.3"

# Serialization
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"

# Database
rusqlite = { version = "0.31", features = ["bundled"] }

# Error handling
thiserror = "1.0"
anyhow = "1.0"

# Logging
tracing = "0.1"
tracing-subscriber = "0.3"

# Utils
uuid = { version = "1.8", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
directories = "5.0"

[build-dependencies]
flutter_rust_bridge_codegen = "2.0"

[dev-dependencies]
tokio-test = "0.4"
```

- [ ] **Step 2: 创建库入口文件 lib.rs**

```rust
//! ZhuGuiTong Core Library
//!
//! 核心服务库，提供 RAG、LLM、向量存储等功能

pub mod config;
pub mod error;
pub mod storage;

// Re-exports
pub use error::{Error, Result};
pub use config::Settings;
```

- [ ] **Step 3: 创建错误定义模块 error.rs**

```rust
//! 统一错误定义

use thiserror::Error;

/// 核心库错误类型
#[derive(Error, Debug)]
pub enum Error {
    #[error("配置错误: {0}")]
    Config(String),

    #[error("存储错误: {0}")]
    Storage(String),

    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),

    #[error("数据库错误: {0}")]
    Database(#[from] rusqlite::Error),

    #[error("序列化错误: {0}")]
    Serialization(#[from] serde_json::Error),

    #[error("未知错误: {0}")]
    Unknown(String),
}

/// 结果类型别名
pub type Result<T> = std::result::Result<T, Error>;
```

- [ ] **Step 4: 创建配置模块 config/mod.rs**

```rust
//! 配置管理模块

mod settings;

pub use settings::*;

use directories::ProjectDirs;
use std::path::PathBuf;

/// 获取项目数据目录
pub fn get_data_dir() -> crate::Result<PathBuf> {
    let project_dirs = ProjectDirs::from("com", "ZhuGuiTong", "ZhuGuiTong")
        .ok_or_else(|| crate::Error::Config("无法确定项目目录".to_string()))?;
    
    let data_dir = project_dirs.data_dir();
    std::fs::create_dir_all(data_dir)?;
    
    Ok(data_dir.to_path_buf())
}

/// 获取配置文件路径
pub fn get_config_path() -> crate::Result<PathBuf> {
    let data_dir = get_data_dir()?;
    Ok(data_dir.join("config.json"))
}
```

- [ ] **Step 5: 创建设置定义 config/settings.rs**

```rust
//! 用户设置定义

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// 应用设置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    /// 当前模型模式
    pub model_mode: ModelMode,
    
    /// 本地模型配置
    pub local_model: LocalModelConfig,
    
    /// 云端 API 配置
    pub cloud_api: CloudApiConfig,
    
    /// RAG 配置
    pub rag: RagConfig,
    
    /// UI 配置
    pub ui: UiConfig,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            model_mode: ModelMode::Local,
            local_model: LocalModelConfig::default(),
            cloud_api: CloudApiConfig::default(),
            rag: RagConfig::default(),
            ui: UiConfig::default(),
        }
    }
}

/// 模型运行模式
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ModelMode {
    Local,
    Cloud,
    Hybrid,
}

/// 本地模型配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalModelConfig {
    /// 对话模型路径
    pub chat_model_path: Option<PathBuf>,
    
    /// 嵌入模型路径
    pub embedding_model_path: Option<PathBuf>,
    
    /// GPU 层数
    pub gpu_layers: i32,
    
    /// 上下文长度
    pub context_length: usize,
    
    /// 线程数
    pub threads: i32,
}

impl Default for LocalModelConfig {
    fn default() -> Self {
        Self {
            chat_model_path: None,
            embedding_model_path: None,
            gpu_layers: 35,
            context_length: 4096,
            threads: 4,
        }
    }
}

/// 云端 API 配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CloudApiConfig {
    /// 服务商
    pub provider: CloudProvider,
    
    /// API Key (加密存储)
    pub api_key: Option<String>,
    
    /// 模型名称
    pub model: String,
    
    /// 自定义 URL
    pub custom_url: Option<String>,
    
    /// 代理设置
    pub proxy: Option<String>,
}

impl Default for CloudApiConfig {
    fn default() -> Self {
        Self {
            provider: CloudProvider::DeepSeek,
            api_key: None,
            model: "deepseek-chat".to_string(),
            custom_url: None,
            proxy: None,
        }
    }
}

/// 云端服务商
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum CloudProvider {
    Anthropic,
    DeepSeek,
    OpenAI,
    Qwen,
    Moonshot,
    Zhipu,
    CustomAnthropic,
    CustomOpenAI,
}

/// RAG 配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RagConfig {
    /// 检索数量
    pub top_k: i32,
    
    /// 相似度阈值
    pub threshold: f32,
    
    /// 上下文长度
    pub context_length: usize,
}

impl Default for RagConfig {
    fn default() -> Self {
        Self {
            top_k: 5,
            threshold: 0.7,
            context_length: 2048,
        }
    }
}

/// UI 配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UiConfig {
    /// 主题
    pub theme: Theme,
    
    /// 字体大小
    pub font_size: f32,
    
    /// 侧边栏折叠
    pub sidebar_collapsed: bool,
}

impl Default for UiConfig {
    fn default() -> Self {
        Self {
            theme: Theme::System,
            font_size: 14.0,
            sidebar_collapsed: false,
        }
    }
}

/// 主题
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Theme {
    Light,
    Dark,
    System,
}

impl Settings {
    /// 从文件加载设置
    pub fn load() -> crate::Result<Self> {
        let config_path = super::get_config_path()?;
        
        if config_path.exists() {
            let content = std::fs::read_to_string(&config_path)?;
            let settings: Self = serde_json::from_str(&content)?;
            Ok(settings)
        } else {
            let settings = Self::default();
            settings.save()?;
            Ok(settings)
        }
    }
    
    /// 保存设置到文件
    pub fn save(&self) -> crate::Result<()> {
        let config_path = super::get_config_path()?;
        let content = serde_json::to_string_pretty(self)?;
        std::fs::write(&config_path, content)?;
        Ok(())
    }
}
```

- [ ] **Step 6: 创建存储模块 storage/mod.rs**

```rust
//! 数据存储模块

mod sqlite;

pub use sqlite::*;

use crate::config::get_data_dir;
use std::path::PathBuf;

/// 获取数据库文件路径
pub fn get_db_path() -> crate::Result<PathBuf> {
    let data_dir = get_data_dir()?;
    Ok(data_dir.join("zhu-gui-tong.db"))
}
```

- [ ] **Step 7: 创建 SQLite 存储实现 storage/sqlite.rs**

```rust
//! SQLite 数据库实现

use rusqlite::Connection;
use std::path::Path;

use crate::error::Result;

/// 数据库存储
pub struct Storage {
    conn: Connection,
}

impl Storage {
    /// 打开数据库
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)?;
        
        // 初始化表结构
        Self::init_tables(&conn)?;
        
        Ok(Self { conn })
    }
    
    /// 初始化表结构
    fn init_tables(conn: &Connection) -> Result<()> {
        conn.execute_batch(
            r#"
            -- 用户配置
            CREATE TABLE IF NOT EXISTS config (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );
            
            -- 对话会话
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            
            -- 对话消息
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                sources TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id)
            );
            
            -- 知识库文档
            CREATE TABLE IF NOT EXISTS documents (
                id TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                category TEXT,
                status TEXT NOT NULL,
                chunks_count INTEGER DEFAULT 0,
                error_message TEXT,
                uploaded_at INTEGER NOT NULL,
                processed_at INTEGER
            );
            
            -- 模型管理
            CREATE TABLE IF NOT EXISTS models (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                downloaded_at INTEGER NOT NULL
            );
            
            -- 操作日志
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY,
                level TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            
            -- 索引
            CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
            CREATE INDEX IF NOT EXISTS idx_documents_category ON documents(category);
            CREATE INDEX IF NOT EXISTS idx_logs_created ON logs(created_at);
            "#,
        )?;
        
        Ok(())
    }
    
    /// 获取配置项
    pub fn get_config(&self, key: &str) -> Result<Option<String>> {
        let mut stmt = self.conn.prepare("SELECT value FROM config WHERE key = ?")?;
        let result = stmt.query_row([key], |row| row.get::<_, String>(0));
        
        match result {
            Ok(value) => Ok(Some(value)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }
    
    /// 设置配置项
    pub fn set_config(&self, key: &str, value: &str) -> Result<()> {
        let now = chrono::Utc::now().timestamp();
        self.conn.execute(
            "INSERT OR REPLACE INTO config (key, value, updated_at) VALUES (?, ?, ?)",
            [key, value, &now.to_string()],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;
    
    #[test]
    fn test_storage_open() {
        let temp_file = NamedTempFile::new().unwrap();
        let storage = Storage::open(temp_file.path());
        assert!(storage.is_ok());
    }
    
    #[test]
    fn test_config_operations() {
        let temp_file = NamedTempFile::new().unwrap();
        let storage = Storage::open(temp_file.path()).unwrap();
        
        storage.set_config("test_key", "test_value").unwrap();
        let value = storage.get_config("test_key").unwrap();
        assert_eq!(value, Some("test_value".to_string()));
    }
}
```

- [ ] **Step 8: 运行 Rust 测试验证**

```bash
cd core
cargo test
```

Expected: 所有测试通过

- [ ] **Step 9: 提交 Rust 核心框架**

```bash
git add core/
git commit -m "feat(core): add Rust core service framework

- Add config module with settings management
- Add storage module with SQLite backend
- Add error handling with thiserror
- Add basic unit tests"
```

---

## Task 3: Flutter 应用框架

**Files:**
- Create: `app/pubspec.yaml`
- Create: `app/lib/main.dart`
- Create: `app/lib/app.dart`
- Create: `app/lib/shared/theme/colors.dart`
- Create: `app/lib/shared/theme/app_theme.dart`
- Create: `app/lib/shared/widgets/sidebar.dart`
- Create: `app/lib/shared/widgets/title_bar.dart`
- Create: `app/lib/shared/widgets/status_bar.dart`
- Create: `app/lib/features/chat/chat_page.dart`
- Create: `app/lib/features/knowledge/knowledge_page.dart`
- Create: `app/lib/features/settings/settings_page.dart`
- Create: `app/lib/features/profile/profile_page.dart`
- Create: `app/lib/providers/app_provider.dart`

- [ ] **Step 1: 创建 Flutter 项目**

```bash
cd zhu-gui-tong-desktop
flutter create --platforms=linux,windows app
```

- [ ] **Step 2: 更新 pubspec.yaml 添加依赖**

```yaml
name: zhu_gui_tong_app
description: 筑规通桌面客户端 - 建筑行业规范智能问答系统
publish_to: 'none'
version: 1.0.0+1

environment:
  sdk: '>=3.4.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  
  # 状态管理
  flutter_riverpod: ^2.5.1
  riverpod_annotation: ^2.3.3
  
  # 路由
  go_router: ^14.2.0
  
  # UI 组件
  flutter_animate: ^4.5.0
  
  # 工具
  path_provider: ^2.1.3
  shared_preferences: ^2.2.3
  uuid: ^4.4.0
  
  # FFI 桥接
  flutter_rust_bridge: ^2.0.0
  ffi: ^2.1.3

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^4.0.0
  riverpod_generator: ^2.4.0
  build_runner: ^2.4.11
  custom_lint: ^0.6.4
  riverpod_lint: ^2.3.6

flutter:
  uses-material-design: true
  
  assets:
    - assets/images/
    - assets/icons/
```

- [ ] **Step 3: 创建应用入口 main.dart**

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  
  runApp(
    const ProviderScope(
      child: ZhuGuiTongApp(),
    ),
  );
}
```

- [ ] **Step 4: 创建根组件 app.dart**

```dart
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'shared/theme/app_theme.dart';
import 'shared/widgets/sidebar.dart';
import 'shared/widgets/title_bar.dart';
import 'shared/widgets/status_bar.dart';
import 'features/chat/chat_page.dart';
import 'features/knowledge/knowledge_page.dart';
import 'features/settings/settings_page.dart';
import 'features/profile/profile_page.dart';

/// 路由配置
final _router = GoRouter(
  routes: [
    ShellRoute(
      builder: (context, state, child) => MainShell(child: child),
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => const ChatPage(),
        ),
        GoRoute(
          path: '/knowledge',
          builder: (context, state) => const KnowledgePage(),
        ),
        GoRoute(
          path: '/settings',
          builder: (context, state) => const SettingsPage(),
        ),
        GoRoute(
          path: '/profile',
          builder: (context, state) => const ProfilePage(),
        ),
      ],
    ),
  ],
);

/// 主应用
class ZhuGuiTongApp extends StatelessWidget {
  const ZhuGuiTongApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: '筑规通 AI',
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.system,
      routerConfig: _router,
      debugShowCheckedModeBanner: false,
    );
  }
}

/// 主布局 Shell
class MainShell extends StatelessWidget {
  final Widget child;
  
  const MainShell({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        children: [
          const TitleBar(),
          Expanded(
            child: Row(
              children: [
                const Sidebar(),
                Expanded(child: child),
              ],
            ),
          ),
          const StatusBar(),
        ],
      ),
    );
  }
}
```

- [ ] **Step 5: 创建颜色定义 colors.dart**

```dart
import 'package:flutter/material.dart';

/// 应用颜色
class AppColors {
  AppColors._();
  
  // 主色
  static const Color primary = Color(0xFF2563EB);
  static const Color primaryLight = Color(0xFF3B82F6);
  static const Color primaryDark = Color(0xFF1D4ED8);
  
  // 辅助色
  static const Color secondary = Color(0xFF64748B);
  static const Color accent = Color(0xFF8B5CF6);
  
  // 成功/警告/错误
  static const Color success = Color(0xFF10B981);
  static const Color warning = Color(0xFFF59E0B);
  static const Color error = Color(0xFFEF4444);
  
  // 文字颜色
  static const Color textPrimary = Color(0xFF1E293B);
  static const Color textSecondary = Color(0xFF64748B);
  static const Color textHint = Color(0xFF94A3B8);
  
  // 背景色
  static const Color background = Color(0xFFF8FAFC);
  static const Color surface = Color(0xFFFFFFFF);
  static const Color surfaceVariant = Color(0xFFF1F5F9);
  
  // 边框
  static const Color border = Color(0xFFE2E8F0);
  static const Color borderDark = Color(0xFFCBD5E1);
  
  // 侧边栏
  static const Color sidebarBackground = Color(0xFF1E293B);
  static const Color sidebarText = Color(0xFFE2E8F0);
  static const Color sidebarActiveItem = Color(0xFF334155);
}
```

- [ ] **Step 6: 创建主题配置 app_theme.dart**

```dart
import 'package:flutter/material.dart';
import 'colors.dart';

/// 应用主题
class AppTheme {
  AppTheme._();
  
  /// 亮色主题
  static ThemeData get light => ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    colorScheme: ColorScheme.light(
      primary: AppColors.primary,
      secondary: AppColors.secondary,
      surface: AppColors.surface,
      error: AppColors.error,
    ),
    scaffoldBackgroundColor: AppColors.background,
    appBarTheme: const AppBarTheme(
      backgroundColor: AppColors.surface,
      foregroundColor: AppColors.textPrimary,
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardTheme(
      color: AppColors.surface,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
        side: const BorderSide(color: AppColors.border),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.surface,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.primary, width: 2),
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: AppColors.primary,
      ),
    ),
    iconTheme: const IconThemeData(
      color: AppColors.textSecondary,
    ),
    dividerTheme: const DividerThemeData(
      color: AppColors.border,
      thickness: 1,
    ),
  );
  
  /// 暗色主题
  static ThemeData get dark => ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: ColorScheme.dark(
      primary: AppColors.primaryLight,
      secondary: AppColors.secondary,
      surface: const Color(0xFF1E293B),
      error: AppColors.error,
    ),
    scaffoldBackgroundColor: const Color(0xFF0F172A),
    appBarTheme: const AppBarTheme(
      backgroundColor: Color(0xFF1E293B),
      foregroundColor: Colors.white,
      elevation: 0,
    ),
    cardTheme: CardTheme(
      color: const Color(0xFF1E293B),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
        side: const BorderSide(color: Color(0xFF334155)),
      ),
    ),
  );
}
```

- [ ] **Step 7: 创建侧边栏组件 sidebar.dart**

```dart
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// 侧边栏组件
class Sidebar extends StatelessWidget {
  const Sidebar({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 240,
      color: const Color(0xFF1E293B),
      child: Column(
        children: [
          // Logo 区域
          Container(
            height: 64,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: const Color(0xFF2563EB),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(
                    Icons.architecture,
                    color: Colors.white,
                    size: 20,
                  ),
                ),
                const SizedBox(width: 12),
                const Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '筑规通',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      'ZhuGuiTong AI',
                      style: TextStyle(
                        color: Color(0xFF94A3B8),
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          
          const Divider(color: Color(0xFF334155), height: 1),
          
          // 导航菜单
          Expanded(
            child: ListView(
              padding: const EdgeInsets.symmetric(vertical: 8),
              children: [
                _NavItem(
                  icon: Icons.chat_outlined,
                  label: '智能对话',
                  path: '/',
                ),
                _NavItem(
                  icon: Icons.folder_outlined,
                  label: '知识库管理',
                  path: '/knowledge',
                ),
                _NavItem(
                  icon: Icons.settings_outlined,
                  label: 'AI 配置',
                  path: '/settings',
                ),
              ],
            ),
          ),
          
          const Divider(color: Color(0xFF334155), height: 1),
          
          // 底部菜单
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Column(
              children: [
                _NavItem(
                  icon: Icons.person_outline,
                  label: '个人中心',
                  path: '/profile',
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 导航项
class _NavItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final String path;
  
  const _NavItem({
    required this.icon,
    required this.label,
    required this.path,
  });

  @override
  Widget build(BuildContext context) {
    final isSelected = GoRouterState.of(context).matchedLocation == path;
    
    return Material(
      color: isSelected ? const Color(0xFF334155) : Colors.transparent,
      child: InkWell(
        onTap: () => context.go(path),
        child: Container(
          height: 44,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Icon(
                icon,
                size: 20,
                color: isSelected ? Colors.white : const Color(0xFF94A3B8),
              ),
              const SizedBox(width: 12),
              Text(
                label,
                style: TextStyle(
                  color: isSelected ? Colors.white : const Color(0xFF94A3B8),
                  fontSize: 14,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
```

- [ ] **Step 8: 创建标题栏组件 title_bar.dart**

```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 自定义标题栏
class TitleBar extends StatelessWidget {
  const TitleBar({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 40,
      color: const Color(0xFF1E293B),
      child: Row(
        children: [
          // 左侧拖拽区域
          Expanded(
            child: GestureDetector(
              onPanStart: (_) {},
              child: Container(
                padding: const EdgeInsets.only(left: 260), // 侧边栏宽度
                child: const Text(
                  '筑规通 AI - 建筑行业规范智能助手',
                  style: TextStyle(
                    color: Color(0xFF94A3B8),
                    fontSize: 12,
                  ),
                ),
              ),
            ),
          ),
          
          // 窗口控制按钮
          Row(
            children: [
              _WindowButton(
                icon: Icons.remove,
                onPressed: () {
                  // TODO: 实现最小化
                },
              ),
              _WindowButton(
                icon: Icons.crop_square,
                onPressed: () {
                  // TODO: 实现最大化
                },
              ),
              _WindowButton(
                icon: Icons.close,
                onPressed: () {
                  // TODO: 实现关闭
                },
                isClose: true,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// 窗口控制按钮
class _WindowButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onPressed;
  final bool isClose;
  
  const _WindowButton({
    required this.icon,
    required this.onPressed,
    this.isClose = false,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 46,
      height: 40,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onPressed,
          hoverColor: isClose ? Colors.red : Colors.white.withOpacity(0.1),
          child: Icon(
            icon,
            size: 16,
            color: const Color(0xFF94A3B8),
          ),
        ),
      ),
    );
  }
}
```

- [ ] **Step 9: 创建状态栏组件 status_bar.dart**

```dart
import 'package:flutter/material.dart';

/// 状态栏
class StatusBar extends StatelessWidget {
  const StatusBar({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 28,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border(
          top: BorderSide(
            color: Theme.of(context).dividerColor,
          ),
        ),
      ),
      child: Row(
        children: [
          // 模型状态
          const Icon(
            Icons.memory,
            size: 14,
            color: Color(0xFF64748B),
          ),
          const SizedBox(width: 4),
          const Text(
            '模型: 本地模式',
            style: TextStyle(
              fontSize: 11,
              color: Color(0xFF64748B),
            ),
          ),
          
          const SizedBox(width: 16),
          
          // 文档数量
          const Icon(
            Icons.description_outlined,
            size: 14,
            color: Color(0xFF64748B),
          ),
          const SizedBox(width: 4),
          const Text(
            '文档: 0',
            style: TextStyle(
              fontSize: 11,
              color: Color(0xFF64748B),
            ),
          ),
          
          const Spacer(),
          
          // 状态
          Row(
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  color: const Color(0xFF10B981),
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
              const SizedBox(width: 6),
              const Text(
                '就绪',
                style: TextStyle(
                  fontSize: 11,
                  color: Color(0xFF64748B),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
```

- [ ] **Step 10: 创建各功能页面占位**

```dart
// features/chat/chat_page.dart
import 'package:flutter/material.dart';

class ChatPage extends StatelessWidget {
  const ChatPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Text('智能对话页面 - 待实现'),
    );
  }
}

// features/knowledge/knowledge_page.dart
import 'package:flutter/material.dart';

class KnowledgePage extends StatelessWidget {
  const KnowledgePage({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Text('知识库管理页面 - 待实现'),
    );
  }
}

// features/settings/settings_page.dart
import 'package:flutter/material.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Text('AI 配置页面 - 待实现'),
    );
  }
}

// features/profile/profile_page.dart
import 'package:flutter/material.dart';

class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Text('个人中心页面 - 待实现'),
    );
  }
}
```

- [ ] **Step 11: 创建资源目录**

```bash
mkdir -p app/assets/images app/assets/icons
```

- [ ] **Step 12: 安装 Flutter 依赖**

```bash
cd app
flutter pub get
```

Expected: 依赖安装成功

- [ ] **Step 13: 运行 Flutter 应用验证**

```bash
cd app
flutter run -d linux
```

Expected: 应用启动，显示基础布局

- [ ] **Step 14: 提交 Flutter 应用框架**

```bash
git add app/
git commit -m "feat(app): add Flutter application framework

- Add main app structure with go_router
- Add custom title bar and sidebar
- Add theme configuration
- Add placeholder pages for all features"
```

---

## Task 4: FFI 桥接层集成

**Files:**
- Modify: `core/src/bridge/mod.rs`
- Modify: `core/build.rs`
- Create: `app/lib/bridge/bridge.dart`

- [ ] **Step 1: 创建 Rust FFI 导出模块**

```rust
// core/src/bridge/mod.rs

//! FFI 桥接层 - 导出给 Flutter 调用的函数

use crate::config::Settings;
use crate::error::Result;

/// 初始化核心服务
#[flutter_rust_bridge::frb(sync)]
pub fn init_services() -> Result<()> {
    // 初始化日志
    tracing_subscriber::fmt::init();
    
    // 加载配置
    let _settings = Settings::load()?;
    
    tracing::info!("核心服务初始化完成");
    Ok(())
}

/// 获取应用版本
#[flutter_rust_bridge::frb(sync)]
pub fn get_app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// 获取服务状态
#[flutter_rust_bridge::frb(sync)]
pub fn get_service_status() -> ServiceStatus {
    ServiceStatus {
        initialized: true,
        model_loaded: false,
        milvus_connected: false,
    }
}

/// 服务状态
#[derive(Debug, Clone)]
pub struct ServiceStatus {
    pub initialized: bool,
    pub model_loaded: bool,
    pub milvus_connected: bool,
}

/// 获取当前设置
#[flutter_rust_bridge::frb(sync)]
pub fn get_settings() -> Result<String> {
    let settings = Settings::load()?;
    Ok(serde_json::to_string(&settings)?)
}

/// 保存设置
#[flutter_rust_bridge::frb(sync)]
pub fn save_settings(settings_json: String) -> Result<()> {
    let settings: Settings = serde_json::from_str(&settings_json)?;
    settings.save()
}
```

- [ ] **Step 2: 更新 lib.rs 导出 bridge 模块**

```rust
//! ZhuGuiTong Core Library

pub mod bridge;
pub mod config;
pub mod error;
pub mod storage;

pub use error::{Error, Result};
pub use config::Settings;
```

- [ ] **Step 3: 创建 build.rs 用于生成 FFI 代码**

```rust
// core/build.rs

fn main() {
    // flutter_rust_bridge 会在构建时自动生成代码
    println!("cargo:rerun-if-changed=src/bridge/mod.rs");
}
```

- [ ] **Step 4: 更新 Cargo.toml 添加 flutter_rust_bridge 依赖**

```toml
# 在 [dependencies] 中添加
flutter_rust_bridge = "2.0"
```

- [ ] **Step 5: 创建 Flutter 端桥接代码**

```dart
// app/lib/bridge/bridge.dart

import 'package:flutter_rust_bridge/flutter_rust_bridge.dart';

/// 核心服务桥接
class CoreBridge {
  static CoreBridge? _instance;
  
  final RustLib _rustLib;
  
  CoreBridge._(this._rustLib);
  
  /// 获取单例
  static Future<CoreBridge> getInstance() async {
    if (_instance == null) {
      final rustLib = RustLib();
      await rustLib.init();
      _instance = CoreBridge._(rustLib);
    }
    return _instance!;
  }
  
  /// 初始化服务
  Future<void> initServices() async {
    // 调用 Rust init_services
  }
  
  /// 获取应用版本
  Future<String> getAppVersion() async {
    // 调用 Rust get_app_version
    return '0.1.0';
  }
  
  /// 获取服务状态
  Future<ServiceStatus> getServiceStatus() async {
    // 调用 Rust get_service_status
    return ServiceStatus(
      initialized: true,
      modelLoaded: false,
      milvusConnected: false,
    );
  }
}

/// 服务状态
class ServiceStatus {
  final bool initialized;
  final bool modelLoaded;
  final bool milvusConnected;
  
  ServiceStatus({
    required this.initialized,
    required this.modelLoaded,
    required this.milvusConnected,
  });
}
```

- [ ] **Step 6: 更新 main.dart 集成桥接**

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'bridge/bridge.dart';
import 'app.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  // 初始化核心服务
  final bridge = await CoreBridge.getInstance();
  await bridge.initServices();
  
  runApp(
    const ProviderScope(
      child: ZhuGuiTongApp(),
    ),
  );
}
```

- [ ] **Step 7: 构建 Rust 库**

```bash
cd core
cargo build --release
```

Expected: 构建成功，生成 libzhu_gui_tong_core.so

- [ ] **Step 8: 提交 FFI 桥接层**

```bash
git add core/src/bridge/ core/build.rs app/lib/bridge/ app/lib/main.dart
git commit -m "feat: add FFI bridge layer

- Add Rust bridge module with exported functions
- Add Flutter bridge wrapper
- Integrate bridge initialization in main"
```

---

## Task 5: 构建脚本与文档

**Files:**
- Create: `scripts/setup.sh`
- Create: `scripts/build.sh`
- Create: `docs/ARCHITECTURE.md`

- [ ] **Step 1: 创建安装脚本 scripts/setup.sh**

```bash
#!/bin/bash
set -e

echo "🚀 设置筑规通开发环境..."

# 检查 Flutter
if ! command -v flutter &> /dev/null; then
    echo "❌ Flutter 未安装，请先安装 Flutter"
    exit 1
fi

# 检查 Rust
if ! command -v rustc &> /dev/null; then
    echo "❌ Rust 未安装，请先安装 Rust"
    exit 1
fi

echo "✅ Flutter 版本: $(flutter --version | head -1)"
echo "✅ Rust 版本: $(rustc --version)"

# 安装 Flutter 依赖
echo "📦 安装 Flutter 依赖..."
cd app
flutter pub get

# 构建 Rust 库
echo "🔧 构建 Rust 核心库..."
cd ../core
cargo build

echo "✅ 开发环境设置完成!"
echo ""
echo "运行 'make dev' 启动开发服务器"
```

- [ ] **Step 2: 创建构建脚本 scripts/build.sh**

```bash
#!/bin/bash
set -e

echo "🏗️ 构建筑规通桌面客户端..."

VERSION=${1:-"0.1.0"}
PLATFORM=$(uname -s)

# 构建 Rust 核心库
echo "📦 构建 Rust 核心库..."
cd core
cargo build --release

# 构建 Flutter 应用
echo "📱 构建 Flutter 应用..."
cd ../app

case "$PLATFORM" in
    Linux)
        flutter build linux --release
        ;;
    MINGW*|MSYS*|CYGWIN*)
        flutter build windows --release
        ;;
    *)
        echo "❌ 不支持的平台: $PLATFORM"
        exit 1
        ;;
esac

echo "✅ 构建完成!"
```

- [ ] **Step 3: 创建架构文档 docs/ARCHITECTURE.md**

```markdown
# 筑规通桌面客户端架构文档

## 整体架构

\`\`\`
┌──────────────────────────────────────────────────────────────────┐
│                        用户界面层 (Flutter)                       │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │  智能对话   │ │  知识库管理 │ │  AI 配置   │ │  系统设置   │    │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘    │
└──────────────────────────────────────────────────────────────────┘
                              │
                              │ FFI (flutter_rust_bridge)
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      核心服务层 (Rust)                           │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                      API 网关模块                            │ │
│  └─────────────────────────────────────────────────────────────┘ │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐          │
│  │   RAG 引擎    │ │  文档解析器   │ │  向量管理器   │          │
│  └───────────────┘ └───────────────┘ └───────────────┘          │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐          │
│  │  LLM 适配器   │ │  嵌入模型     │ │  配置管理器   │          │
│  └───────────────┘ └───────────────┘ └───────────────┘          │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      基础设施层                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ Milvus   │ │ llama.cpp│ │ 云端API  │ │ 本地存储  │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
└──────────────────────────────────────────────────────────────────┘
\`\`\`

## 模块职责

### Flutter UI 层

- **main.dart**: 应用入口
- **app.dart**: 根组件，路由配置
- **features/**: 各功能模块页面
- **shared/**: 共享组件、主题、工具
- **bridge/**: Rust FFI 桥接

### Rust 核心层

- **bridge/**: FFI 导出函数
- **config/**: 配置管理
- **storage/**: 数据存储 (SQLite)
- **llm/**: LLM 适配器 (本地/云端)
- **embedding/**: 嵌入模型
- **vector/**: 向量管理 (Milvus)
- **rag/**: RAG 引擎
- **parser/**: 文档解析

## 数据流

1. 用户输入问题
2. Flutter 通过 FFI 调用 Rust
3. Rust RAG 引擎处理:
   - 问题预处理
   - 向量嵌入
   - Milvus 检索
   - 构建上下文
   - LLM 生成
4. 流式返回结果给 Flutter
5. Flutter 渲染响应

## 技术选型理由

| 技术 | 理由 |
|------|------|
| Flutter | 跨平台 UI 成熟，开发效率高 |
| Rust | 性能优秀，内存安全，部署简单 |
| flutter_rust_bridge | 类型安全，代码生成，开发体验好 |
| Riverpod | 状态管理简洁，支持异步 |
| SQLite | 轻量级本地存储，无需额外服务 |
\`\`\`
```

- [ ] **Step 4: 设置脚本权限**

```bash
chmod +x scripts/setup.sh scripts/build.sh
```

- [ ] **Step 5: 提交构建脚本和文档**

```bash
git add scripts/ docs/
git commit -m "docs: add build scripts and architecture documentation

- Add setup.sh for development environment setup
- Add build.sh for production build
- Add ARCHITECTURE.md with system design overview"
```

---

## 验收检查

- [ ] **验证 Rust 核心库构建**

```bash
cd core && cargo build --release
```

Expected: 构建成功，无错误

- [ ] **验证 Flutter 应用运行**

```bash
cd app && flutter run -d linux
```

Expected: 应用启动，显示主界面

- [ ] **验证 FFI 桥接**

```bash
# 检查生成的库文件
ls -la core/target/release/libzhu_gui_tong_core.so
```

Expected: 库文件存在

---

## 完成标志

阶段 1 完成后，项目应具备：

1. ✅ 完整的项目目录结构
2. ✅ Rust 核心服务框架（配置、存储）
3. ✅ Flutter 应用框架（主题、布局、路由）
4. ✅ FFI 桥接层基础
5. ✅ 开发环境设置脚本
6. ✅ 架构文档

---

**计划创建日期**: 2026-04-21
**预计完成时间**: 2周
