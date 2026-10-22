# Lua 工具插件

本插件是独立的 MIT Android DEX 包，Lua 解释器与源包管理均不链接到主程序。

依赖 LuaJ 3.0.1，MIT，Copyright (c) 2007 LuaJ。完整许可证见 licenses/luaj.txt。
官方源码与受限环境示例：https://github.com/luaj/luaj 。

仅加载基础、数学、字符串、表和 bit32 库。按 LuaJ 的 DebugLib 指令回调限制运行预算，
脚本看不到 debug、Java 反射、操作系统、文件系统、包加载器或协程库。
这限制的是源脚本能力，不构成操作系统级沙盒；可信原生插件包仍在宿主应用 UID 下运行。
