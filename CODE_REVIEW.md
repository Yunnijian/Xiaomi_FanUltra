# Code Review

## Findings

### Fixed: Scene fallback previously bypassed global fan disable and high-priority stop guards

`app/src/main/java/com/mifan/kt/HookEntry.kt:620` 的 `resolveEffectivePerAppFanLevel()` 先运行 `resolvePerAppFanLevel()`，但随后无条件合并 `HookEntry.kt:841` 的 `resolveCustomSceneLevel()`。

安全闸在 `resolvePerAppFanLevel()` 里，包括总开关、录音停转、听筒通话、息屏保持；`resolveCustomSceneLevel()` 没有这些检查。所以自定义模式下，只要快充或导航场景命中，就可能在总开关关闭、录音/听筒通话、息屏不允许保持时把 `target_level` 强制回 `2`。

处理：已在 scene fallback 合并前增加公共保护检查，至少覆盖总开关、录音智能停转、SIM 通话 + 听筒等硬保护。快充/导航保底和快充息屏散热语义保持不变。

### Retracted: Per-app off/quiet losing to rapid-charge/navigation fallback is expected

`app/src/main/java/com/mifan/kt/HookEntry.kt:831` 的 `mergeCustomFanLevels()` 对 `PER_APP_LEVEL_OFF = 0` 执行 `maxOf(appLevel, sceneLevel)`。如果某 App 明确配置为关闭风扇，但快充或导航 fallback 返回 `2`，最终档位会变成 `2`。

经确认，这是预期产品语义：快充/导航场景不受 App 的关闭风扇或静谧模式影响，场景档位保底为 `2`；只有高于 `2` 的 App 档位可以覆盖快充和导航档位。

建议：代码逻辑可保留，但应调整 UI 文案和 README，明确“关闭风扇/静谧模式不覆盖快充和导航场景”。否则用户看到“此应用前台运行时关闭风扇”时，容易误解为绝对关闭。

### Fixed: MISettings optional obfuscated class could abort the whole per-app config hook install

`app/src/main/java/com/mifan/kt/HookEntry.kt:1242` 到 `HookEntry.kt:1245` 在函数开头一次性加载 Activity、Fragment、SearchFragment、`oa.j`。其中 `oa.j` 是混淆类，ROM 差异下很容易改名或不存在。

一旦 `MISETTINGS_FOLLOW_VIEW_HOLDER` 加载失败，异常会直接冒出 `installMiSettingsFanConfigHostHooks()`，导致本来可用的 Activity/Fragment hook 也完全不注册。

处理：已把 search/follow-holder 相关 hook 拆为独立 `runCatching`，基础 Activity/Fragment hook 不再依赖 `oa.j`。

### Fixed: MISettings follow-holder method signature was a hard dependency

`app/src/main/java/com/mifan/kt/HookEntry.kt:1322` 直接解析 `oa.j.a(androidx.recyclerview.widget.p0, Any, int)`。这个类名和签名都高度依赖 ROM 构建。

失败时会让安装流程报错。即使前面的 hook 已注册，也会把包级安装日志标成失败，并阻断后续新增 hook。

处理：已把 follow-row 绑定逻辑降级为可选增强，失败时只跳过对应行 UI 改造。

### Fixed: SystemUI detail adapter method lookups were too strict

`app/src/main/java/com/mifan/kt/HookEntry.kt:361` 的 `updateItems` 和 `HookEntry.kt:375` 的 `createDetailView(...)` 都在 `runCatching` 外。

如果某版 SystemUI 只改了其中一个方法名或签名，后续包括自定义点击处理 `HookEntry.kt:387` 都不会继续安装。

处理：已拆成独立可选 hook。构造器、列表刷新、详情页创建、点击处理之间不再互相拖垮。

### Retracted: Fast-charge screen-off fan preservation is expected

`app/src/main/java/com/mifan/kt/HookEntry.kt:788` 的 `keepCurrentFanLevelOnScreenOff()` 在 `fastChargeActive` 为 true 时，即使“息屏保持”开关关闭也会保留正档位。

经确认，这是预期产品语义：熄屏高速充电时通常伴随高热，快充场景需要主动维持散热，不受普通“息屏保持风扇开启”开关约束。

建议：代码逻辑可保留，但应在 README、设置文案或日志中明确说明“快充高热场景可能在息屏后继续保持风扇”，避免用户把“息屏保持”开关理解成所有息屏场景的唯一控制入口。

## Reproducibility

`scripts/gradle.sh:6` 依赖仓库外层的 `../.toolchains` 和 `../gradle-8.13`，`local.properties:1` 也硬编码了本机路径。

当前机器可以构建，但 fresh checkout 不能靠仓库内容直接复现。

建议：补初始化脚本或 Gradle wrapper，并把 `local.properties` 排除出版本输入。

## Improvement Plan

### 1. First fix custom fan policy safety gates

优先处理 `resolveEffectivePerAppFanLevel()` / `resolveCustomSceneLevel()` 的安全闸问题。建议新增一个公共函数，例如 `isCustomFanPolicyAllowed(handler, resolver)`，集中检查：

- `cooling_fan_enable` 总开关；
- 录音智能停转；
- SIM 通话 + 听筒；
- 息屏/锁屏与 `mifan_keep_fan_on_screen_off`；
- 快充、导航场景开关。

然后让 App 规则和 scene fallback 都先经过这个公共函数。这样可以避免两条策略路径以后继续分叉。

### 2. Document scene fallback priority over low app levels

快充/导航场景是保底 `2` 档，不受 App 的关闭风扇或静谧模式影响；只有高于 `2` 的 App 档位可以覆盖场景档位。当前 `maxOf(appLevel, sceneLevel)` 符合这个语义。

建议把规则显式写成文档和代码注释：

```text
sceneLevel == 2 and appLevel <= 2 -> 2
sceneLevel == 2 and appLevel > 2  -> appLevel
sceneLevel == null                -> appLevel
```

同时建议调整 App 档位文案，例如“关闭风扇（快充/导航场景除外）”，避免用户把它理解成全局绝对关闭。

### 3. Split fragile hooks into independent optional installers

MISettings 和 SystemUI 的 hook 建议拆成小函数，例如：

- `installMiSettingsHostHooks()`：Activity/Fragment 基础页面；
- `installMiSettingsSearchHooks()`：搜索页；
- `installMiSettingsFollowHolderHook()`：`oa.j` 行 UI 增强；
- `installSystemUiUpdateItemsHook()`；
- `installSystemUiCreateDetailViewHook()`；
- `installSystemUiDetailClickHook()`。

每个函数内部独立 `runCatching`。目标是“一个 ROM 差异只损失一个小能力”，不要让一个混淆类拖垮整个包的 hook 安装。

### 4. Document fast-charge screen-off behavior

快充时即使息屏保持开关关闭，也可能保留风扇档位。经确认这是预期行为，因为熄屏高速充电通常伴随高热，需要保留主动散热能力。

建议在设置文案、README 和日志中明确说明：

- “息屏时保持风扇开启”控制普通息屏/锁屏场景；
- 快充高热场景属于独立散热保护逻辑；
- 快充场景下即使未开启普通息屏保持，也可能继续保留风扇档位。

### 5. Treat module splitting as mid-term refactoring

Nemotron 报告建议把 `HookEntry.kt` 拆成多个模块，这个方向成立，但不应排在当前 P1 修复之前。

建议把拆分模块降为中期重构，放在行为语义稳定之后执行。推荐顺序：

1. 先修安全闸与可选 hook 隔离；
2. 再补文档和回归验证清单；
3. 最后按 Settings / PowerKeeper / SystemUI / MISettings / policy / reflection utils 分文件。

这样可以避免在行为还没收敛时做大规模移动，减少误伤。

### 6. Improve build reproducibility

推荐按优先级补齐：

1. 提供 `gradlew` 和 `gradle/wrapper/gradle-wrapper.properties`，让 Gradle 版本进入仓库输入；
2. 新增 `scripts/init-toolchain.sh`，明确 JDK、Android SDK commandline-tools、platform 34、build-tools 35.0.0 的安装方式；
3. 把 `local.properties` 加入 `.gitignore`，提供 `local.properties.example`；
4. README 写清 release keystore 的文件位置、生成方式和环境变量要求。

### Done: Add focused regression checks

这个项目很难做普通单元测试，但可以补几类轻量验证：

- 把纯策略函数抽成可测试 helpers，覆盖 `OFF + sceneLevel -> sceneLevel`、`level4 + sceneLevel -> 4`、总开关关闭、录音停转、普通息屏保持关闭、快充息屏保持允许等组合；
- 增加一份手工真机验证清单，固定记录 `fan_mode`、`target_level`、前台包、锁屏状态、快充/导航/录音场景；已新增 [REGRESSION_TESTS.md](REGRESSION_TESTS.md)。
- 对每个可选 hook 安装失败输出单独日志，方便 ROM 适配时快速定位是哪一块失效。

## Verification

审查修复和后续清理完成后运行 debug 构建，结果通过：

```text
BUILD SUCCESSFUL in 19s
:app:assembleDebug
```

源码级 Kotlin 警告已清理。当前唯一构建提示是 Kotlin 插件警告 Gradle 8.13 未来会被弃用，当前不影响产物。
