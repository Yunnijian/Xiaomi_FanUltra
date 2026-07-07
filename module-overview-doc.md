# MifanUltra 风扇狂暴模式模块说明

## 一、模块作用

MifanUltra 是一个用于 LSPosed 的轻量级风扇增强模块，主要作用是在系统原有散热风扇功能中新增“狂暴模式”。

模块会在系统设置和控制中心的散热风扇模式列表中增加一个新的模式入口：

```txt
狂暴模式
```

用户选择后，模块会让系统写入：

```txt
fan_mode=4
```

随后由 PowerKeeper 侧按照官方风扇控制流程下发：

```txt
target_level=4
```

从而实现比官方高速强冷更强的风扇档位。

---

## 二、特色亮点

### 1. 新增狂暴模式

模块在官方散热风扇模式基础上新增“狂暴模式”，用户可以像选择官方档位一样，在系统设置或控制中心中直接选择。

### 2. 体积小，占用低

模块是轻量级 LSPosed Hook 模块，Release APK 体积很小，不重写系统界面，不常驻额外复杂服务，运行占用低。

### 3. 遵循官方风扇调用逻辑

模块不是绕过系统粗暴写入底层节点，而是接入官方链路：

```txt
Settings/SystemUI 写入 fan_mode
PowerKeeper 处理风扇策略
PowerKeeper 下发 target_level
```

因此整体行为更接近官方风扇档位逻辑。

### 4. 遵循官方使用范围和场景限制

狂暴模式会参考官方散热风扇设置中的总开关、使用范围和场景限制，例如：

```txt
散热风扇总开关
使用范围
游戏场景
极速充电场景
导航场景
录音智能停止
```

只有在官方策略允许风扇运行的情况下，模块才会下发 `target_level=4`，避免狂暴模式绕过官方设置全局常驻。

### 5. 稳定退出狂暴模式

从狂暴模式切回智能调频时，模块会在 PowerKeeper 侧执行桥接流程：

```txt
target_level=2
fan_mode=2
延迟 800ms
fan_mode=-1
```

这样可以避免切回智能调频后底层 `target_level` 仍残留 `4` 的问题。

---

## 三、工作流程

### 1. 选择狂暴模式

```txt
用户在系统设置或控制中心选择“狂暴模式”
        ↓
Settings/SystemUI 写入 fan_mode=4
        ↓
PowerKeeper 监听到 fan_mode 变化
        ↓
模块 Hook FanStateHandler
        ↓
判断官方总开关、使用范围、场景限制
        ↓
官方策略允许时写入 target_level=4
        ↓
风扇进入狂暴档位
```

### 2. 切回智能调频

```txt
用户选择智能调频 fan_mode=-1
        ↓
PowerKeeper 检测到刚从狂暴模式退出
        ↓
先写 target_level=2，并切到 fan_mode=2
        ↓
延迟 800ms 后写回 fan_mode=-1
        ↓
最终回到官方智能调频，target_level 不再残留 4
```

---

## 四、使用方法

### 1. 安装模块

安装生成的 Release APK：

```txt
fan-mode-hook-release.apk
```

当前模块包名：

```txt
com.mifan.kt
```

### 2. 在 LSPosed 中启用模块

在 LSPosed 中启用模块，并勾选以下作用域：

```txt
com.android.settings
com.miui.powerkeeper
com.android.systemui
```

作用说明：

| 作用域 | 作用 |
|---|---|
| com.android.settings | 系统设置中显示“狂暴模式” |
| com.miui.powerkeeper | 实际下发狂暴档位，并处理退出逻辑 |
| com.android.systemui | 控制中心磁贴中显示“狂暴模式” |

### 3. 重启相关进程或重启手机

启用模块后，建议重启手机。也可以至少重启相关进程：

```sh
su -c 'pkill -f com.android.settings'
su -c 'pkill -f com.miui.powerkeeper'
su -c 'pkill -f com.android.systemui'
```

### 4. 使用狂暴模式

进入系统散热风扇设置页面，或打开控制中心散热风扇磁贴二级页，选择：

```txt
狂暴模式
```

选择后可以通过以下命令验证：

```sh
settings get system fan_mode
cat /sys/devices/platform/soc/soc:xiaomi_fan/target_level
```

正常情况下：

```txt
fan_mode=4
target_level=4
```

从狂暴模式切回智能调频后：

```txt
fan_mode=-1
target_level 不再残留 4
```

智能调频下 `target_level` 可能是 `0/1/2`，这是官方动态调节行为。

---

## 五、注意事项

1. 模块依赖 LSPosed，需要在 LSPosed 中正确启用并勾选作用域。
2. 狂暴模式会遵循官方总开关、使用范围和场景限制，不是任何时候都强制运行。
3. 系统更新后，如果 Settings、SystemUI 或 PowerKeeper 的类名、字段名变化，模块可能需要重新适配。
4. 如果控制中心没有显示“狂暴模式”，请确认 `com.android.systemui` 作用域已启用并重启 SystemUI。
5. 如果狂暴模式不能实际生效，请确认 `com.miui.powerkeeper` 作用域已启用并重启 PowerKeeper 或重启手机。
