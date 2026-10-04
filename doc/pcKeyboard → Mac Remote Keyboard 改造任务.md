# pcKeyboard → Mac Remote Keyboard 改造任务

## 0. 项目目标

基于现有开源项目 **pcKeyboard** 进行最小化改造。

目标不是开发一个新的 Android 中文输入法，而是把 pcKeyboard 改造成：

> **面向 Android 折叠屏 + 远程桌面场景的虚拟硬件键盘。**

主要使用场景：

```text
Android Foldable
      ↓
pcKeyboard
      ↓
网易 UU 远程
      ↓
macOS
      ↓
macOS 自己的中文/英文输入法
```

目前已经人工验证：

1. Android 手机连接物理键盘；
2. 手机通过 UU 控制远端 Mac；
3. Mac 开启中文拼音输入法；
4. 在 Android 物理键盘输入 `nihao`；
5. 远端 Mac 可以正常进入拼音 composing，并显示「你好」候选词；
6. UU 开启必要的无障碍/键盘相关权限后，Mac 快捷键也可以正常使用。

因此：

> **UU 本身已经能够正确转发硬件 Keyboard Event。**

目前 pcKeyboard 的问题是：

- 普通字符主要通过 `InputConnection.commitText()` 提交；
- 某些方向键、Enter 等使用 Android 本地文本编辑语义；
- 结果是远端 Mac 收到已经确定的英文字符，而不是物理键盘事件；
- 因而即使 Mac 当前开启中文输入法，也只能直接输入英文。

本项目首先解决这一问题。

---

# 1. 第一阶段：实现 Remote Raw KeyEvent Mode

## 核心原则

**保留 pcKeyboard 当前 UI，不要先重写界面。**

新增一种输入模式：

```text
Normal Android Mode
Remote Raw Keyboard Mode
```

Normal Mode 保持当前行为，不破坏现有功能。

Remote Raw Keyboard Mode 下：

> 所有具有物理键盘含义的按键都应该尽量通过 `InputConnection.sendKeyEvent()` 发送真实 `KeyEvent`，而不是通过 `commitText()`、`setSelection()`、`performEditorAction()` 等 Android 文本编辑 API 实现。

---

# 2. 首先阅读并理解现有代码

开始修改前，请先定位：

1. `InputMethodService` / keyboard service 主实现；
2. 普通字符最终调用 `commitText()` 的位置；
3. Ctrl / Alt / Win / Shift 等 ModifierState 的实现；
4. F1-F12、Esc、Tab、Backspace 等现有 `sendKeyEvent()` 实现；
5. Arrow Key 当前是否通过 `setSelection()` 实现；
6. Enter 当前是否调用 `performEditorAction()`；
7. keyboard layout / key definition 的组织方式。

先输出一个非常简短的代码路径说明，例如：

```text
UI Key
 ↓
onKey(...)
 ↓
dispatchKey(...)
 ↓
commitText / sendKeyEvent / setSelection
```

不要一开始进行大规模重构。

---

# 3. 增加 Dispatch Mode

建议引入类似：

```kotlin
enum class DispatchMode {
    NORMAL,
    RAW_REMOTE
}
```

可以暂时通过：

- Settings；
- SharedPreferences；
- 或开发阶段 hardcode；

控制。

最终希望 UI 中存在：

```text
Input Mode

○ Normal Android
● Remote Desktop / Raw Keyboard
```

但第一版不需要花大量时间实现 Settings UI。

---

# 4. 实现统一的 Raw KeyEvent Dispatcher

实现一个统一函数，例如：

```kotlin
sendRawKey(
    keyCode: Int,
    modifiers: ModifierState
)
```

要求发送：

```text
ACTION_DOWN
ACTION_UP
```

并携带正确的 `metaState`。

概念上：

```kotlin
private fun sendRawKey(
    keyCode: Int,
    metaState: Int = 0
) {
    val ic = currentInputConnection ?: return
    val downTime = SystemClock.uptimeMillis()

    val down = KeyEvent(
        downTime,
        downTime,
        KeyEvent.ACTION_DOWN,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        KeyEvent.FLAG_SOFT_KEYBOARD
    )

    val up = KeyEvent.changeAction(
        down,
        KeyEvent.ACTION_UP
    )

    ic.sendKeyEvent(down)
    ic.sendKeyEvent(up)
}
```

以上只是参考。

请结合 pcKeyboard 当前代码结构以及 Android API 正确实现，不必机械照抄。

重点是：

> RAW_REMOTE 模式不要通过 `commitText()` 输入普通字符。

---

# 5. 第一批必须 Raw 化的按键

## 字母

```text
A-Z
```

映射为：

```text
KEYCODE_A
...
KEYCODE_Z
```

必须验证：

```text
N I H A O
```

在远端 Mac 中文拼音状态下能够产生：

```text
nihao
→
你好 / 你号 / ...
```

候选框。

这是第一阶段最重要的验收标准。

---

## 数字

```text
0-9
```

使用对应 Android KeyCode。

---

## 标点符号

至少覆盖：

```text
`
-
=
[
]
\
;
'
,
.
/
```

Shift 组合也要正确，例如：

```text
Shift + 1 → !
Shift + 2 → @
Shift + [ → {
Shift + ; → :
```

不要为了得到 `!` 而：

```text
commitText("!")
```

应该尽量发送：

```text
Shift + KEYCODE_1
```

让远端系统决定最终字符。

---

# 6. 特殊键全部采用物理键盘语义

RAW_REMOTE 下避免：

```text
setSelection()
performEditorAction()
deleteSurroundingText()
commitText()
```

来模拟键盘操作。

这些键应该发送真实 KeyEvent：

```text
Space
Enter / Return
Backspace
Forward Delete
Tab
Esc

↑
↓
←
→

F1-F12
```

必要时也支持：

```text
Home
End
Page Up
Page Down
Insert
```

虽然后续 MacBook Layout 可能不会显示这些键。

---

# 7. Modifier 处理

需要保留并尽量复用 pcKeyboard 当前 ModifierState 设计。

需要支持：

```text
Shift
Ctrl
Alt
Meta
```

最终 Mac UI 对应关系计划为：

```text
⌃ Control → Android CTRL
⌥ Option  → Android ALT
⌘ Command → Android META
⇧ Shift   → Android SHIFT
```

例如用户点击：

```text
⌘
C
```

应该让 C KeyEvent 携带：

```text
META_META_ON
META_META_LEFT_ON
```

或者按照 Android 正确的组合键事件顺序：

```text
META down
C down
C up
META up
```

请研究 pcKeyboard 当前 modifier 实现，并选择与 UU 兼容性最好的方式。

---

# 8. Modifier 必须支持触屏键盘使用方式

触屏无法像物理键盘一样自然同时按住多个键，因此保留或实现：

### One-shot latch

点一次：

```text
⌘
```

显示激活：

```text
⌘ [ON]
```

再点击：

```text
C
```

发送：

```text
⌘C
```

随后自动解除 Command。

### Sticky / Lock

如果 pcKeyboard 当前已经有类似功能，尽量复用，不要重新实现。

例如：

```text
single tap → one-shot
double tap / long press → lock
```

这一阶段不要求改变当前 UI 交互，只要求保证组合键最终正确发送。

---

# 9. 不要实现中文输入法

非常重要：

**Android 端完全不负责中文输入。**

不要增加：

```text
拼音解析
候选词
词典
自动纠错
联想
中文 composing
```

目标是模拟硬件键盘：

```text
用户按 N
↓
Android KEYCODE_N
↓
UU
↓
macOS
↓
macOS 当前 Input Method
```

因此：

- Mac 是中文输入法 → 输入中文；
- Mac 是英文输入法 → 输入英文；
- Mac 是日文输入法 → 理论上同样交给 macOS 处理。

Android 键盘不关心远端语言。

---

# 10. Phase 1 暂时不要修改 UI

第一阶段保持 pcKeyboard 当前 UI。

只修改：

```text
Input dispatch backend
```

不要同时：

- 重画键帽；
- 调整尺寸；
- 模仿 MacBook；
- 修改大量 layout；
- 加动画；
- 加主题；
- 重构整个项目。

我们需要先验证：

> **pcKeyboard UI → Raw KeyEvent → UU → macOS**

链路。

---

# 11. Phase 1 验收测试

必须在真实环境测试：

```text
Android 折叠屏
     ↓
pcKeyboard modified APK
     ↓
UU
     ↓
Mac
```

## Test A：中文输入

Mac：

```text
输入法 = 中文拼音
```

Android 依次按：

```text
n
i
h
a
o
```

预期：

```text
macOS 出现拼音 composing
并出现「你好」等候选词
```

如果仍然直接输入：

```text
nihao
```

说明普通按键仍未真正走 hardware-like KeyEvent 链路。

这是：

> **P0 阻断问题。**

在解决之前不要继续 UI 工作。

---

## Test B：英文

Mac 切换 ABC。

输入：

```text
Hello, world!
```

必须正常。

重点验证 Shift：

```text
H
!
,
.
```

---

## Test C：编辑键

验证：

```text
Backspace
Enter
Tab
Esc
← ↑ ↓ →
```

行为应该和物理键盘通过 UU 操作 Mac 基本一致。

---

## Test D：Mac 快捷键

测试：

```text
⌘C
⌘V
⌘X
⌘A
⌘Z

⌘Tab
⌘Space

⌥←
⌥→

⌃C
⌃D
```

重点场景：

```text
Terminal
Codex
Claude Code
浏览器
文本编辑器
```

---

# 12. 调试要求

如果某些按键失败，不要立即用 `commitText()` fallback。

先记录：

```text
keyCode
action
metaState
scanCode
flags
deviceId
source
```

并与真实 Android 外接物理键盘产生的事件进行对比。

如果有必要，写一个简单的：

```text
KeyEvent Debug Activity
```

记录物理键盘事件。

目标是：

> 让虚拟键盘产生的语义尽量接近已经验证可用的真实物理键盘。

---

# 13. Phase 2：MacBook Layout

只有 Phase 1 全部通过以后再进行。

目标不是 87-key PC TKL，而是：

> **尽量复刻 MacBook 内置键盘布局。**

大致：

```text
Esc  F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12

`  1 2 3 4 5 6 7 8 9 0 - =     Delete
Tab  Q W E R T Y U I O P [ ]       \
Caps  A S D F G H J K L ; '       Return
Shift   Z X C V B N M , . /       Shift

fn   ⌃   ⌥   ⌘        Space        ⌘   ⌥   ◀ ▲ ▼ ▶
```

视觉目标：

- 极简；
- 接近 MacBook；
- 不做花哨设计；
- 尽可能保留 pcKeyboard 当前简洁视觉风格；
- 折叠屏展开状态优先；
- 主要面向竖屏；
- 键盘占屏幕高度约 30–35%，后续可配置。

---

# 14. Fn 的设计

第一版不要强求透传真正的 macOS Fn/Globe key。

Fn 可以首先作为本地 Layer Key：

```text
fn + ← → Home
fn + → → End
fn + ↑ → Page Up
fn + ↓ → Page Down
```

以及未来可以控制：

```text
F1-F12
vs
媒体功能键
```

不要因为 Fn 阻塞 MVP。

---

# 15. 设计原则

始终遵循以下原则：

### 1. 最小改造

尽量 fork + patch pcKeyboard。

不要重写 IME。

### 2. UI 与事件语义解耦

例如 UI：

```text
⌘
```

内部定义为：

```text
MAC_COMMAND
```

然后再映射：

```text
MAC_COMMAND → Android META
```

未来如果 UU 对某些键需要特殊映射，可以只修改 KeyMapper。

推荐结构：

```text
UI Key
   ↓
LogicalKey
   ↓
KeyMapper
   ↓
Android KeyCode + metaState
   ↓
RawKeyDispatcher
   ↓
InputConnection.sendKeyEvent()
```

不要让 UI 直接散落大量 `KEYCODE_*` 逻辑。

### 3. Remote Mode 不理解文本

Remote Mode 只理解：

```text
key
modifier
down
up
```

不要理解：

```text
我要输入什么字符
```

最终字符由：

```text
macOS keyboard layout
+
macOS input method
```

决定。

---

# 16. 第一轮交付要求

第一轮不要完成整个产品。

只交付：

1. 对 pcKeyboard 当前输入事件链的简要分析；
2. 新增 `RAW_REMOTE` mode；
3. A-Z、数字、常见标点 raw KeyEvent；
4. Space / Enter / Backspace / Tab / Esc；
5. 四个方向键；
6. Shift / Ctrl / Alt / Meta；
7. 可编译 APK；
8. 测试说明；
9. 修改文件列表；
10. 已知问题列表。

---

# 17. 第一轮成功标准

最关键的唯一 Go / No-Go 指标：

```text
Mac 中文拼音开启
↓
Android pcKeyboard 输入 nihao
↓
Mac 出现「你好」候选词
```

如果成功：

```text
GO
```

继续 Phase 2 MacBook Layout。

如果失败：

```text
NO-GO FOR UI WORK
```

继续调试 KeyEvent dispatch，不要通过添加中文输入功能绕过问题。

---

# 18. Codex 工作方式

请自行：

```text
Explore
→ 找到事件链
→ 修改
→ Build
→ 静态检查
→ 输出 APK 路径
→ 总结变更
```

优先做最小闭环。

如果发现当前假设不成立，例如：

- `sendKeyEvent()` 被 UU 特殊处理；
- 虚拟 IME KeyEvent 和物理 Keyboard KeyEvent 存在无法忽略的区别；
- 普通字符无法通过当前方式透传；

请明确报告：

```text
观察到的行为
↓
代码/日志证据
↓
最可能原因
↓
最小下一实验
```

不要未经验证就引入大规模 workaround。

---

## 最终目标

最终这个项目应该成为：

> **一个外观看起来像 MacBook 键盘、运行在 Android 折叠屏下方、专门用于控制远端 Mac 的虚拟硬件键盘。**

它不承担输入法智能。

它只做一件事情：

> **尽可能忠实地把用户在触屏上按下的 Mac 键，转换成远端 Mac 能理解的物理键盘事件。**
