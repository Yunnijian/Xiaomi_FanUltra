# 模块简要说明

## 一、模块亮点

1. **新增“狂暴模式”**

   模块在系统设置和控制中心的散热风扇模式中新增“狂暴模式”。用户选择后写入：

   ```txt
   fan_mode=4
   ```

   随后由 PowerKeeper 侧实际下发：

   ```txt
   target_level=4
   ```

2. **体积小，占用低**

   模块是轻量级 LSPosed Hook 模块，不重写系统界面，不常驻额外复杂服务，Release APK 体积很小，运行占用也低。

3. **遵循官方风扇设置调用逻辑**

   狂暴模式不是绕过系统直接粗暴写节点，而是接入 Settings、SystemUI 和 PowerKeeper 的原有调用链路：

   ```txt
   Settings/SystemUI 写 fan_mode
   PowerKeeper 处理策略
   PowerKeeper 下发 target_level
   ```

   因此整体行为更接近官方风扇档位。

4. **遵循官方使用范围和场景限制**

   狂暴模式会参考官方设置中的风扇总开关、使用范围和场景开关，例如游戏、极速充电、导航、录音智能停止等。只有在官方策略允许风扇运行的场景下，才会下发：

   ```txt
   target_level=4
   ```

   这样可以避免狂暴模式绕过官方设置全局常驻。

5. **稳定退出狂暴模式**

   从狂暴模式切回智能调频时，模块在 PowerKeeper 侧执行桥接流程：

   ```txt
   target_level=2
   fan_mode=2
   延迟 800ms
   fan_mode=-1
   ```

   这样可以避免切回智能调频后 `target_level` 仍残留 `4`。

## 二、工作流程

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
```

从狂暴模式切回智能调频时：

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