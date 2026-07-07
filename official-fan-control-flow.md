# 官方风扇控制流程简述

本文档简要说明当前设备上官方散热风扇功能的大致控制链路，便于理解模块为什么选择 Hook Settings、SystemUI 和 PowerKeeper。

---

## 1. 用户入口

官方风扇控制主要有两个用户入口：

```txt
系统设置散热风扇页面
控制中心散热风扇磁贴
```

对应包：

```txt
com.android.settings
com.android.systemui
```

这两个入口本身主要负责展示模式、响应用户选择，并把选择结果写入系统设置项。

---

## 2. 模式写入

用户选择风扇模式后，系统会写入：

```txt
Settings.System["fan_mode"]
```

当前已确认的官方模式语义：

| fan_mode | 含义 |
|---:|---|
| `-1` | 智能调频 |
| `1` | 静谧模式 |
| `2` | 高速强冷 |

模块新增：

| fan_mode | 含义 |
|---:|---|
| `4` | 狂暴模式 |

---

## 3. PowerKeeper 监听和策略判断

官方真正负责风扇策略的是 PowerKeeper：

```txt
com.miui.powerkeeper
```

关键类：

```txt
com.miui.powerkeeper.unionpower.corehandler.FanStateHandler
```

当 `fan_mode`、风扇总开关、使用范围或场景状态变化时，PowerKeeper 会重新读取相关系统设置，并结合当前设备状态判断风扇是否允许运行。

常见参考项包括：

```txt
cooling_fan_enable
fan_mode_range
fan_mode_scene_gaming
fan_mode_scene_rapid_charge
fan_mode_scene_navigation
fan_smart_stop_on_recording
```

同时还会参考锁屏、快充、录音、通话、导航、游戏场景、thermal 状态等内部状态。

---

## 4. 官方策略下发

PowerKeeper 判断完成后，会通过内部方法下发目标风扇档位。

关键方法：

```txt
FanStateHandler.J(String path, String value)
```

典型写入目标：

```txt
target_level
```

底层对应节点：

```txt
/sys/devices/platform/soc/soc:xiaomi_fan/target_level
```

也就是说，官方链路可以简化为：

```txt
用户选择风扇模式
        ↓
Settings/SystemUI 写入 fan_mode
        ↓
PowerKeeper 监听设置变化
        ↓
FanStateHandler 判断总开关、使用范围、场景限制
        ↓
FanStateHandler.J("target_level", value)
        ↓
底层风扇节点 target_level 改变
        ↓
风扇转速变化
```

---

## 5. 智能调频的特点

`fan_mode=-1` 是官方智能调频。

智能调频下，`target_level` 不是固定值，而是由 PowerKeeper 根据温度、场景和系统状态动态决定，可能出现：

```txt
target_level=0
target_level=1
target_level=2
```

因此不能简单认为智能调频必须等于 `target_level=0`。

---

## 6. 模块接入位置

模块没有重写官方风扇控制系统，而是接入官方链路：

```txt
Settings/SystemUI：增加“狂暴模式”入口，写 fan_mode=4
PowerKeeper：在官方策略允许时，把 fan_mode=4 映射为 target_level=4
```

模块仍然参考官方风扇总开关、使用范围和场景限制，避免狂暴模式绕过官方策略全局常驻。

---

## 7. 简化总结

官方风扇控制流程可以概括为：

```txt
UI 入口只写 fan_mode
PowerKeeper 负责策略判断
FanStateHandler 负责下发 target_level
底层 sysfs 节点驱动风扇运行
```

模块的核心思路是：

```txt
不破坏官方流程
只新增 fan_mode=4 的入口
并在 PowerKeeper 侧补齐 fan_mode=4 到 target_level=4 的映射
```