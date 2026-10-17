# 性能与资源管理修复记录

实际工作目录：`D:\lyricon-source`。用户指定的 `F:\github\lyricon-source\lyricon-source` 不存在。本次修改位于该工作区。

## 逐项改动

| 类别 | 实际改动 |
| --- | --- |
| 动画空转 | `FrameAnimator` 根据渲染器是否仍需推进决定下一帧；静止、结束、隐藏、窗口不可见、休眠时停止。滚动初始延时仍会推进，恢复时重置帧时间，避免跨隐藏时段的大步跳动；回调中重启不会重复调度。休眠同时停止歌词及封面动画。 |
| 进度轮询 | `PlayerEventDispatcher` 从 16ms 改为 50ms，播放、亮屏、有监听者、有共享内存时才启动；暂停、熄屏、无监听者、断连时取消。绘制仍由 Choreographer 驱动，保留逐帧插值；重新启动时读取当前位置。 |
| 封面加载 | 状态栏、悬浮窗及 Logo 的文件检查、采样解码和取色转移到后台；取色最大边长 256px。先查缓存，同一版本封面共享正在执行的取色任务。控制器取消旧等待，防止旧结果覆盖；临时位图在 finally 中回收，Logo 脱离时取消加载并清空图片。 |
| 歌词深拷贝 | 保留原始缓存与加工流水线的数据隔离；流水线复制移至后台，删除 normalize 前的重复深拷贝。后置处理未改变对象时跳过第二轮显示加工；AI 无有效译文时返回原对象，删除额外歌词快照。 |
| AI 翻译 | 并发上限 2，待处理上限 2；同 key 等待者共享任务，最后一个等待者离开时取消排队或运行任务，清缓存取消全部任务。网络取消绑定实际 OkHttp Call，包括等待响应头和读取正文阶段；保持原有 Chat Completions、SSE、自定义服务地址及鉴权。 |
| 内存与缓存 | 订阅端、提供端、中央会话补齐 SharedMemory.unmap，映射失败关闭句柄，读写与释放互斥。SDK 客户端缓存上限 8，淘汰后待活动使用者结束再关闭连接池和线程池；取色结果采用 32 项 LRU，后台取色工作限制为 2 路。 |
| TextDrawer | 辉光 alpha 先夹在 0～1 再乘 255；避免 Shader 与 Paint 重复叠乘颜色 alpha。修复批量绘制的文本连续性检查、变换后裁剪、辉光跨 pass 边界、遮罩总宽度缓存及渐变位置范围，并复用未变化的 ComposeShader。 |

额外清理：屏幕状态接收器统一放在 subscriber 模块，由中央端与 SystemUI 共用，初始化读取实际亮灭屏状态；压缩保留 ZLIB 格式，改用快速压缩并保证 finally 释放 native 编解码器；拒绝不完整或需要字典的输入，避免解压空转。移除未被调用的外部 Bonsai 依赖及版本项，保留项目内 Bonsai 实现。

## 验证

- 12 个模块共 **125 项单元测试通过，0 失败、0 跳过**；其中新增 20 项回归测试。
- 新增回归涵盖：空闲不调帧、结束帧重绘后停止、滚动延时、隐藏与恢复、辉光 alpha、ZLIB 兼容及截断/字典输入、AI 请求共享/并发/取消。
- 5 项本地 HTTP 测试覆盖普通回复的中文与鉴权、SSE 回复、响应头前取消、正文读取中取消、流式请求取消；未调用外部 AI 服务。
- release lint：0 错误、2 警告（依赖更新提示及 ChromeOS 缺少 x86_64 ABI），未为本次任务升级依赖或增加 ABI。
- 模型模块现有测试只使用 JUnit，移除未使用的 kotlin-test 依赖并使用已有 JUnit 配置。本机全局代理 127.0.0.1:7897 未运行，最终构建使用缓存与命令行代理覆盖；未更改用户全局配置。
- 未连接 Android 真机/模拟器，未执行设备仪器测试、SystemUI 注入体验或实际功耗测量；不能据此宣称实际节电比例。

SDK 用法核对参考 [OpenAI 官方 SDK 文档](https://developers.openai.com/api/docs/libraries) 与项目本地 4.54.0 库，未升级 SDK。

## 构建与产物

- 最终 `:lyricon:assembleRelease :lyricon:lintRelease` 成功，启用项目现有 R8 混淆与资源缩减。
- 原始 APK：`D:\lyricon-source\lyricon\build\outputs\apk\release\lyricon-arm64-v8a-release.apk`
- 已复制并核对一致的 APK：`D:\output\lyricon-1.0.40.rc-performance-release-arm64-v8a.apk`
- 包名：`io.github.proify.lyricon`；版本名 `1.0.40.rc`，版本号 `51`；ABI `arm64-v8a`。
- 文件大小：`12,679,966` 字节。
- SHA-256：`05fbc5c7feddb9cd6ea5d455857883126489559c4f8330dd6604f6fef521fb70`。
- APK Signature Scheme v2 验证通过；ZIP 每个条目 CRC 检查通过；16KB zipalign 检查通过。最终混淆映射中已核对包含修复后的帧调度及 HTTP 取消实现。
- **签名限制**：沿用仓库已有的 release 签名配置，证书为 `C=US, O=Android, CN=Android Debug`。这是 release 构建类型，未使用生产发布证书。
- 证书 SHA-256：`abf97a0881b81b921112538d8d23284bb783c3b610a1a4b9003dd46c2ca409e7`。

最终测试日志：`D:\lyricon-source\build\performance-tests.log`；构建日志：`D:\lyricon-source\build\performance-release.log`；lint 报告：`D:\lyricon-source\lyricon\build\reports\lint-results-release.html`。

本次执行使用本机 Java 与 Android SDK，构建命令为：

```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\Administrator\AppData\Local\Android\Sdk'
.\gradlew.bat '-Dhttp.proxyHost=' '-Dhttps.proxyHost=' :lyricon:assembleRelease :lyricon:lintRelease --offline --console=plain
```
