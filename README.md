# ramen-training-advisor

拉面杯训练决策器 — 手机端

## 来源

- 训练逻辑：[hzyhhzy/UmaAi](https://github.com/hzyhhzy/UmaAi) `HandwrittenLogic.cpp` Kotlin 移植
- 数据来源：[hlpatch](https://github.com/xf8410/hlpatch) SO 插件 `/summary` 端点

## 配卡

3速1耐1智1友人

## 权重

| 属性 | 基础权重 | 上限 |
|------|---------|------|
| 速度 | 10 | 2200 |
| 耐力 | 5 | 1700 |
| 力量 | 3 | 1700 |
| 根性 | 2 | 1700 |
| 智力 | 6 | 1800 |

属性超过85%上限时权重自动衰减，转移给还有空间的属性。

## 拉面选择预设

- Y1（T2）：速/耐/智
- Y2（T26）：2号/3号/5号
- Y3（T50）：速/耐/智
- URA：中间

## 使用

1. 手机安装 hlpatch SO 插件（端口18765）
2. 进游戏主界面后安装hook
3. 启动本App，授悬浮窗权限
4. 开浮窗，训练界面自动显示建议
