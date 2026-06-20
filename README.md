# Miku VVVF Fighter HUD V10 Phone Sensor

车速绑定声浪模拟 Demo。V10 在 V9 的战斗 HUD、真实采样 VVVF、MainApp Hook、音频平滑算法基础上，新增“手机传感器车速数据源”。

## V10 更新

- 新增手机加速度传感器绑定 VVVF：可用手机自身传感器估算速度。
- 新增 GPS 车速绑定 VVVF：直接使用 Android Location 的 `speed`。
- 新增 GPS + 加速度融合模式：GPS 负责绝对速度，加速度负责快速响应，推荐实车使用。
- 保留 MainApp Hook 模式：车机上仍可用 `com.ts.MainUI` 的 CarInfoService 原车数据。
- 保留原有平滑算法：音频线程继续对速度/RPM 做连续插值，真实采样 VVVF 的播放窗口仍然平滑移动，避免加减速时“嘟嘟嘟”跳采样。
- 设置页新增“加速度校准/归零”和“反转加速度方向”。

## 数据源模式

长按主界面进入设置，可选择：

```text
GPS + 加速度融合   推荐手机上车使用，GPS 定绝对速度，加速度补响应
仅 GPS 车速        最稳，但 GPS 刷新率低，速度变化会慢一点
仅加速度估算       无 GPS 也能响，但会随时间漂移，适合短时间测试
MainApp Hook       车机专用，读取 com.ts.MainUI 的 CarInfoService
手动/UDP/ADB       离车调试
```

## 传感器说明

- GPS 模式需要定位权限。
- 加速度模式需要手机固定安装，不要手持晃动测试。
- 第一次车辆明显加速时，程序会自动锁定手机的前进轴向。
- 如果加速时声音像是在减速，进入设置点“反转加速度方向”。
- 如果拆装手机或改变安装角度，点“加速度校准/归零”。
- 纯加速度无法获得绝对车速，长期使用一定会漂移；实车建议用“GPS + 加速度融合”。

## 主界面操作

```text
长按屏幕：打开设置
```

主界面无按钮，默认只显示大号 HUD 车速。

## UDP 调试

端口：`47230`

```text
MODE FUSION
MODE GPS
MODE ACCEL
MODE HOOK
MODE MANUAL
CALIBRATE
ACCEL_INVERT TOGGLE
SPEED 45
STYLE SAMPLE_VVVF_0_140
STYLE AIRCRAFT_TURBINE
PING
STOP
```

示例：

```bash
echo MODE FUSION | nc -u 手机IP 47230
echo CALIBRATE | nc -u 手机IP 47230
echo ACCEL_INVERT TOGGLE | nc -u 手机IP 47230
echo SPEED 45 | nc -u 手机IP 47230
```

## ADB 调试

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode PHONE_FUSION
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode PHONE_GPS
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode PHONE_ACCEL
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode MAINAPP_HOOK
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode MANUAL
adb shell am broadcast -a com.jlxc.mikuvvvf.RESET_SENSOR
adb shell am broadcast -a com.jlxc.mikuvvvf.TOGGLE_ACCEL
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140
adb shell am broadcast -a com.jlxc.mikuvvvf.STOP
```

老命令仍保留：

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled true
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled false
```

## MainApp Hook 模式

车机专用，默认目标：

```text
com.ts.MainUI/com.ts.can.carinfo.CarInfoService
```

读取：

```text
ICarInfoService.requestCarBaseInfo()
base[2] = 车速 km/h
base[3] = 发动机转速 rpm
```

轮询最低 500ms。声音引擎内部会继续对速度/RPM 做连续插值。

## 声音模式

```text
SAMPLE_VVVF_0_140   真实采样 VVVF，0→140km/h
SIEMENS_GZ_GTO      广东地铁西门子 GTO 风格
GTO                 通用老式 GTO
IGBT                通用现代 IGBT
AIRCRAFT_TURBINE    飞机/涡扇引擎
POP_BANG_TURBO      偏时点火/回火
NATURAL_ASPIRATED   自然吸气
ROTARY              转子发动机
SUPERCHARGED_V8     机械增压 V8
```
