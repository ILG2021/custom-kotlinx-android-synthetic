# Synthetic Compatibility

保留 `kotlinx.android.synthetic.main.<layout>.*` 和直接访问控件 ID 的写法，减少旧 Android 项目的迁移改动。

项目包含构建时生成代码的 Gradle 插件，以及可选的 Android Studio 插件。Studio 插件还提供 Parcelize、Java/Kotlin ButterKnife、`R.id switch` 和系统返回键迁移工具；这些迁移工具可独立使用，无需接入 synthetic Gradle 插件。版本变化见 [CHANGELOG.md](CHANGELOG.md)。

<!-- Plugin description -->
Keep existing kotlinx.android.synthetic imports with generated Kotlin view extensions.
The companion Gradle plugin handles generation and source registration.
This optional IDE plugin updates generated files after layout changes.
Apply the Gradle plugin and build once before using IDE updates.
<!-- Plugin description end -->

保留原来的写法：

```kotlin
import kotlinx.android.synthetic.main.activity_main.*

message.text = "Hello"
```

普通控件只出现在部分语言、横竖屏等布局变体中时，生成属性为可空类型，例如 `optionalTitle: TextView?`，使用安全调用或先判空。这类控件会按名称查找资源 ID，资源被过滤或当前布局没有该控件时返回 `null`；布局共有的控件继续使用直接 `R.id` 引用，保留非空属性类型，但不做运行时非空检查，实际结果可能为 `null`。升级这一行为时，需同步项目中的 `generator` 源码和 Studio 插件，再重新运行 `:app:generateSyntheticViews`。

上述规则适用于所有控件类型，包括 Barrier 等布局辅助控件，没有单独的类型特判。所有属性均不生成 `checkNotNull` 检查。

## 1. 复制到 Android 项目

将本仓库的两个目录一起复制，保持相邻：

```text
你的 Android 项目/
├── app/
├── tools/synthetic-compat/
│   ├── generator/
│   └── synthetic-gradle-plugin/
└── settings.gradle
```

**只有一个 Gradle 插件。** `generator/` 是解析 XML、生成 Kotlin 代码的公共模块，由插件自动依赖，不需要单独安装或在 app 中配置。两个目录可随业务项目一起提交，供团队和 CI 使用。

## 2. 接入 Gradle 插件

在项目 `settings.gradle` 最前面添加以下配置；已有 `pluginManagement` 则合并进去。路径相对于 `settings.gradle`：

```groovy
pluginManagement {
    includeBuild('tools/synthetic-compat/synthetic-gradle-plugin')
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

在需要 synthetic 的模块 `build.gradle` 中添加以下 `plugins` 块；已有则只追加插件 ID。放在 `apply plugin:` 等普通语句之前（如有 `buildscript {}`，放在它之后）：

```groovy
plugins {
    // 保留原有插件
    id 'com.github.kevin58cmd.synthetic-compat'
}
```

不要使用 `apply plugin: 'com.github.kevin58cmd.synthetic-compat'` 代替上述配置：`pluginManagement.includeBuild` 需要通过 `plugins {}` 解析插件，仅使用 `apply plugin:` 会报 `UnknownPluginException`。原有 Android 和 Kotlin 的 `apply plugin:` 行可以保留在上述块之后。

无需版本号、发布到 Maven 或手写 `sourceSets`。

插件源码自身的 `.gradle.kts` 文件无需修改，可以直接被 Groovy 项目引用。

使用 Kotlin DSL 的项目，在 `settings.gradle.kts` 中配置：

```kotlin
pluginManagement {
    includeBuild("tools/synthetic-compat/synthetic-gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

在需要 synthetic 的模块 `build.gradle.kts` 的现有 `plugins` 块中追加：

```kotlin
plugins {
    id("com.github.kevin58cmd.synthetic-compat")
}
```

每个使用 synthetic 的 Android 模块都需要应用此插件，并保留 Android 和 Kotlin Android 支持。当前接入 application、library 和 dynamic-feature 模块；生成时使用各模块自己的 `android.namespace`。

## 3. 首次生成

移除旧的 `kotlin-android-extensions` 插件和手写的 `build/generated/synthetic-view-gen` 源码目录配置。使用旧版 IDE 插件的项目先升级该插件，再执行一次 `clean`；`@Parcelize` 改用独立的 `kotlin-parcelize`。

旧版 Parcelize 批量修复：打开 **Tools → Synthetic Compatibility → Migrate Legacy Parcelize (Whole Project)...**，直接扫描整个项目，无需选择目录，预览后统一应用。将 `kotlinx.android.parcel` 引用迁移到 `kotlinx.parcelize`，覆盖 `Parcelize`、`RawValue`、`Parceler` 等导入、别名、通配导入及全限定引用；包含测试源码，跳过构建和生成目录，不修改注释及字符串文本，支持统一 Undo。

此操作同时预览并修改对应模块的 `build.gradle` / `build.gradle.kts`：Groovy 模块存在 `apply plugin: 'com.android.application'` 时，将 `apply plugin: 'kotlin-parcelize'` 放在它之后，并调整已有直接声明的位置；其他脚本替换直接声明的 `kotlin-android-extensions`，或在 `plugins` 块中补上 `kotlin-parcelize`，源码和配置支持统一 Undo。迁移后执行 Gradle Sync 并编译。已经迁移包名但尚未启用插件的源码也会触发配置检查。无法确定模块、`apply false` 声明、版本目录插件别名、需保留显式插件版本或无法可靠解析的 Groovy 字面量等配置会显示跳过原因，并保持该模块的源码和配置不变；这类配置需在对应版本目录或 convention plugin 中处理。

执行一次正常构建，或：

```shell
./gradlew :app:generateSyntheticViews
```

首次不能只依赖 Sync。之后每次构建会自动生成，命令行和 CI 不需要安装 Studio 插件。Windows 可使用 `gradlew.bat`。

`:app:generateSyntheticViews` 会生成 app 的所有 Android 变体；只需 debug 时可运行 `:app:generateDebugSyntheticViews`，其他变体对应 `generate<变体名首字母大写>SyntheticViews`。示例中的 `:app` 请替换成实际模块路径。生成源码位于模块 `build/generated/` 下并由插件自动注册；IDE 模型位于 Android 项目根目录 `.gradle/synthetic-compat/`，不应提交生成产物或手工修改它们。

默认生成 AndroidX Fragment 扩展。模块没有对应依赖时关闭：

```groovy
syntheticCompat {
    androidX.set(false)    // 没有 AndroidX Fragment 时
}
```

ViewHolder 与旧 Kotlin Android Extensions 保持一致：实现 `LayoutContainer` 后使用 `main.<layout>.*` 直接访问控件；普通 ViewHolder 使用 `.view.*` 和 `itemView.<控件 ID>`。不再生成独立的 RecyclerView.ViewHolder 扩展，以免与 LayoutContainer 冲突。旧 `viewHolders` 配置仍可保留，但不再影响生成结果。

### 配置项

支持 XML 中的内部类名称，例如 `<view class="androidx.appcompat.widget.SearchView$SearchAutoComplete" .../>`；生成的 Kotlin 类型为 `androidx.appcompat.widget.SearchView.SearchAutoComplete`，无需手动配置 `typeOverrides`。升级时请更新 Android 项目中复制的 `generator/` 源码；仅安装 Studio 插件 ZIP 不会更新 Gradle 构建使用的生成器。

以下选项放在模块的 `syntheticCompat { ... }` 中，Groovy 和 Kotlin DSL 均可使用 `.set(...)` / `.put(...)`：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `androidX` | `true` | 生成 AndroidX Fragment 扩展，需要模块已有 `androidx.fragment` 依赖；设为 `false` 后仍生成 Activity、Dialog 和 View 扩展。 |
| `platformFragments` | `false` | 设为 `true` 时生成旧 `android.app.Fragment` 扩展。 |
| `typeOverrides` | 空映射 | 用 XML 控件类名映射到完整 Kotlin 类型，优先于自动识别，见“泛型控件”。 |
| `viewHolders` | `false` | 兼容旧配置，当前不影响生成结果。 |

## 4. 可选：安装 Android Studio 插件

它让保存布局后的生成代码及时更新；Gradle 插件负责构建时生成。**两者共用 generator，Studio 插件 ZIP 已包含它，无需额外配置路径。**

在本仓库使用 JDK 21 执行：

```shell
./gradlew buildPlugin
```

在 Studio 的 Settings → Plugins → Install Plugin from Disk 中选择 `build/distributions/` 下的 ZIP，按提示启用。

当前 IDE 插件版本为 **0.1.34**，声明支持 IDE build **242–252.***，支持 Kotlin K1/K2 模式；实际安装以 Studio 的 build number 和插件兼容性检查为准。IDE 插件构建使用 JDK 21；Gradle 插件和 generator 使用 JDK 17 工具链，两者不要混淆。

完成首次生成后，保存 XML 会后台更新并刷新 IDE，无需因布局修改重启。正常 Build 会自动运行生成任务，无需手动菜单操作。修改 Gradle 插件配置后需重新构建或运行生成任务。

## Java/Kotlin：迁移 ButterKnife

安装新版 Studio 插件后，打开 **Tools → Synthetic Compatibility → Migrate ButterKnife to findViewById (Whole Project)...**，直接扫描整个项目，不再选择目录。Java/Kotlin 基类、关联子类及绑定调用方一起规划。预览每个文件的差异和跳过原因，再点击应用；支持统一 Undo。0.1.33 起移除单文件迁移菜单，Tools 中所有迁移选项都针对整个项目。

- 扫描项目内非测试 Java/Kotlin 源文件，排除构建产物；请先完成 Gradle Sync 和索引。
- 控件：`@BindView`、`@BindViews`，支持 `@Nullable`、`@Optional` 和 `R2` → `R`。
- 资源：`@BindString`、`@BindBool`、`@BindInt`、`@BindArray`、`@BindDimen`、`@BindFloat`、`@BindColor`、`@BindDrawable`、`@BindBitmap`、`@BindAnim`、`@BindFont`。字体和 Drawable tint 需要项目已有 AndroidX Core 或 support-compat。
- 事件：`@OnClick`、`@OnLongClick`、`@OnTouch`、`@OnFocusChange`、`@OnCheckedChanged`、`@OnEditorAction`、`@OnItemClick`、`@OnItemLongClick`、`@OnItemSelected`、`@OnTextChanged`、`@OnPageChange`，转为普通监听器，不再自动防抖。
- 支持命名内部类/ViewHolder、多个类及多个绑定调用。父子类按继承关系一起规划，包含整个项目的基类和调用方。
- `Unbinder` 字段/局部变量转为 `Runnable`，支持判空、赋 null、`EMPTY`；`unbind()` 转为 `run()`，解绑时清空控件、移除监听器并释放引用。
- 无可解析绑定入口、匿名绑定类、跨文件 Unbinder API、未知注解等会显示具体原因。继承链中有无法转换的类时，相关类一起跳过，其他文件仍可处理。

迁移后编译并检查界面行为。工具不自动删除 Gradle 依赖，支持 Java/Kotlin 混合项目；仅当整个项目没有残留使用时再清理依赖。此功能独立于 synthetic 的 Gradle 插件。

### Kotlin 迁移

迁移 Kotlin 绑定属性时同时移除其 `@JvmField`（包含别名和全限定注解），无关属性上的注解保留；Java 调用方需通过 Kotlin 生成的 getter/setter 访问迁移后的属性。支持 Kotlin 的 @field:BindView、可空属性、lateinit 控件、常用资源和事件注解，以及 ButterKnife.bind(this)/bind(this, root)。Kotlin 与 Java 使用同一依赖规划，支持 Kotlin 基类 + Java 子类和反向继承。lateinit 控件转换为带可空存储的属性，解绑时释放 View；未初始化或解绑后读取仍抛出 UninitializedPropertyAccessException。Unbinder 转为 Runnable，保留安全调用和绑定/清理位置。自定义属性访问器、委托属性、匿名绑定类、复杂跨文件 Unbinder API 会整组跳过并提示原因。先完成 Gradle Sync 和索引，再查看每个关联文件的差异。

## 当前范围

- 已验证：AGP 8.7.2、Kotlin 2.1.20、Gradle 8.13、JDK 17；AGP 9 尚未验证。
- 支持 `main.<layout>.*` 和 `.view.*`，按构建变体处理资源覆盖及 `layout-*`；不生成旧 debug/flavor 包名。
- 布局共有的属性为非空类型，部分布局才有的属性为可空类型；不缓存 View。读取属性不做非空检查，找不到控件或 Fragment View 不可用时可返回 `null`，后续直接调用可能抛空指针异常。同名 ID 类型冲突时降为 `View`。
- 支持实现 `kotlinx.android.extensions.LayoutContainer` 的自定义 Popup/ViewHolder，使用 `main.<layout>.*` 导入并直接访问控件 ID；通过当前 `containerView` 查找，不缓存。保留提供该接口的运行时依赖（例如原有 `kotlin-android-extensions-runtime`），生成器检测到接口后自动生成扩展；`.view.*` 仍用于 View 接收者。升级时同步 `generator/` 和 IDE 插件，再运行 `:app:generateSyntheticViews`。
- include 与旧 synthetic 一致：父布局包只暴露自身 XML 的 ID，不递归导出子布局控件；子布局控件需导入其自己的布局包。include 自身的 ID 使用 View 类型；已知 merge 根布局不生成不存在的 include 容器 ID。可引用依赖模块布局，但不会为依赖布局生成控件扩展。
- 暂不支持 `<fragment>` ID、旧缓存 API，以及直接导航 XML、跨 XML/Kotlin 重命名。
- 依赖库中的泛型 View 自动生成星投影类型；`typeOverrides` 可显式覆盖类型，见下方示例。旧平台 Fragment 可用 `platformFragments.set(true)` 启用。
- IDE 当前读取打开的 Gradle 根目录；多 Gradle 根目录同窗口未覆盖。移除模块后若有过期配置，可删除目标工程 `.gradle/synthetic-compat/` 并重新生成。

完整示例：[samples/compat-app](samples/compat-app)。真实业务项目和 Studio 交互仍需验收。

### 泛型控件

生成器读取当前 Android 变体编译依赖的 class 泛型声明，自动将 `MZBannerView<T>` 生成为 `MZBannerView<*>`，两个参数生成 `<*, *>`。支持 class 目录、JAR 和 AAR；同一依赖类路径写入 IDE 模型，用于 Studio 重新生成，不加载或执行控件类。

0.1.12 还支持当前模块静态 Java 源码目录中的泛型控件，直接解析 Java 类声明，无需先编译 app。源码目录及依赖类路径通过 IDE 模型格式 3 导出，Java 源码修改会触发重新生成。升级时同时更新 Studio 插件、项目中的 `generator` 和 `synthetic-gradle-plugin`，然后重新运行 `:app:generateSyntheticViews`；版本不匹配时会提示更新并停止 IDE 生成。

当前模块中的 Kotlin 泛型源码、生成任务产生的 Java 源码仍需显式配置；Java 声明解析需要运行环境含 JDK 编译器。`typeOverrides` 始终优先于自动识别。在使用 synthetic 的模块 `build.gradle` 中配置，例如：

```groovy
syntheticCompat {
    typeOverrides.put('com.zhouwei.mzbanner.MZBannerView',
                      'com.zhouwei.mzbanner.MZBannerView<*>')
}
```

重新运行 `:app:generateSyntheticViews` 后，属性类型及 `findViewById` 类型参数都会使用 `MZBannerView<*>`。有两个泛型参数的控件可配置为 `YourView<*, *>`。配置同时写入 IDE 模型，供 Studio 后续生成使用；不要直接修改生成文件。

## R.id switch 批量转换

安装插件后使用 **Tools → Synthetic Compatibility → Convert R.id Switches to If/Else (Whole Project)...**，无需选择目录。扫描全项目非测试 Java 源码，预览后统一应用，可通过 Undo 撤销。仅处理可解析到非编译期常量 `R.id` 的 switch，条件只计算一次；支持普通分支、连续 case 标签以及末尾 default。已有方法体或其他代码块时不会再添加一层括号；单语句 if、循环或带标签的 switch 仍保留必要的代码块。同一入口也会预览清理旧版生成的多余代码块，必要时重命名缓存变量以避免作用域扩大造成重名。支持 case 下直接声明局部变量，只要变量的引用均在同一分支内，例如 SeekBar 回调中的 rgb、color。复杂贯穿、跨分支变量引用、无法解析的变量引用、直接声明局部类、条件 break、嵌套 switch 的外层和箭头分支会跳过并显示位置。生成目录和测试源码不参与扫描；请先完成 Gradle Sync。

## 系统返回键迁移

0.1.34 将未重写返回的后代类也纳入业务方法名称冲突检查；多类文件中无法迁移的 Activity 会阻止相关继承链部分迁移。其他方法中的 `super.onBackPressed()` 及 Java `super::onBackPressed` 会通过带回调禁用及异常恢复保护的辅助方法执行，避免重新进入当前类的返回业务。

全项目入口扫描非测试 Java/Kotlin 源码，并排除生成和构建目录；先完成 Gradle Sync 和索引。0.1.33 起移除单文件迁移菜单。

Activity 自动迁移要求能解析为 `androidx.activity.ComponentActivity` 的子类（包括相应的 AppCompatActivity），且能解析 `OnBackPressedCallback`。仅继承平台 `android.app.Activity` 的类会提示人工处理。

使用 **Tools → Synthetic Compatibility → Migrate Legacy System Back (Whole Project)...**，预览后统一应用并支持 Undo。0.1.28 支持在顶层 `super.onCreate()` 前初始化、Activity 父子类返回转发、Java `onKeyDown` 中独立的 BACK 分支、Java Dialog 按键监听、Java View 的 `onKeyPreIme` / `dispatchKeyEventPreIme`，以及 Kotlin 列表绑定中的 View 按键监听。Activity 回调带重入保护。

普通 Activity 的 `onBackPressed()` 重写会改为私有业务方法 `syntheticCompatHandleSystemBack()`（当前文件或继承层级重名时自动加后缀），同时移除 Java `@Override` / Kotlin `override`。新回调调用业务方法；旧系统由继承的 AndroidX 返回入口进入 dispatcher，不需要限定 API。类内直接调用及 Java 方法引用会同步改名，保留注册回调前的调用行为；类外原有调用仍可通过继承的返回入口执行。Kotlin 指向原函数的 `return@onBackPressed` 会同步更新，嵌套 lambda 自己的标签保持原义。默认返回保留保护，避免递归或重复执行。

0.1.32 起，有子类或父类返回重写的 Java/Kotlin Activity 也会重命名返回业务方法并移除旧重写。继承链一起预览、应用，共用避开整条继承链重名的业务方法名，使用可重写的 `protected` 方法（Kotlin 父类使用 `open`、子类使用 `override`）。子类的 `super.onBackPressed()` 同步改为调用父类业务方法，父类内直接返回调用继续派发到子类，保留注册前的调用及条件返回行为。继承链中存在无法安全提取的文件时，一起进入人工处理报告。提取 Java `onKeyDown` BACK 分支时仍保留旧重写作为兼容入口。重新扫描可将已识别的旧 Activity 桥接升级为独立业务方法；已编辑的回调会进入人工处理报告。

同文件的 Activity 返回重写和 View/Dialog 监听会一起迁移；提取的 `onKeyDown` BACK 分支仅在新 dispatcher 回调中执行，旧按键路径和直接 `onBackPressed()` 调用保留原有行为。监听内部重新绑定时，已注销的旧回调不会再次注册。重新扫描可升级 0.1.25 生成的回调模板；被手工修改、无法确认的旧模板会列入人工处理报告。

View/Dialog 新回调使用 Android 13 窗口 API，需要目标模块 `compileSdk >= 33` 和 AndroidX Activity 1.6+。PreIme 使用 `PRIORITY_OVERLAY`，未消费时先让键盘关闭，再转发默认返回；普通监听使用默认优先级。回调仅在 View 挂载且有焦点时注册，脱离窗口时注销，重复绑定清理旧回调；Android 12 及以下保留原按键入口。仍需在真机验证键盘、焦点、弹窗和返回动画。

仅排除 BACK 的判断（如 NestedScrollView 中的 `!= KEYCODE_BACK`）和 Fragment 的 Boolean `onBackPressed()` 自定义接口不再误报为 Activity 迁移。无法独立提取的按键分支、显式再次派发返回、提前返回或条件调用 `super.onCreate()`、缺失 SDK/AndroidX 类型仍给出具体人工处理原因；插件不会猜测并强行改写。

需要人工处理时，工具在项目根目录 `.synthetic-compat/reports/system-back-<时间>-<UUID>.txt` 保存报告并打开，记录文件路径、行号、代码片段及跳过原因。全项目扫描在差异预览前保存报告，因此取消预览或全部跳过时仍可查看；报告表示扫描结果，不表示源码已经修改。

## 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| 找不到 `com.github.kevin58cmd.synthetic-compat` 插件 | 检查 `pluginManagement.includeBuild` 的相对路径、两个源码目录是否相邻，以及模块是否通过 `plugins {}` 声明插件。 |
| synthetic 导入标红或保存 XML 后没有更新 | 先运行模块生成任务，确认项目根目录 `.gradle/synthetic-compat/` 有模型文件；在 Studio 中信任项目，确认 IDE 插件已启用，并以对应 Gradle 根目录打开项目。 |
| 生成代码中的 AndroidX Fragment 无法解析 | 添加项目所需的 AndroidX Fragment 依赖，或配置 `androidX.set(false)` 后重新生成。 |
| 提示 `Unsupported synthetic IDE model` | 同步更新项目中的两个源码目录和 IDE 插件，然后重新运行生成任务。 |
| 使用控件时发生空指针异常 | 在 Activity `setContentView` 后或 Fragment View 生命周期内访问；确认当前根 View 使用对应布局。生成属性不缓存 View，也不做非空检查。 |
| 迁移入口不可用或出现无法解析的跳过原因 | 确认选择了对应 Java/Kotlin 文件、项目已完成 Gradle Sync 和索引，并按差异预览或人工报告中的原因处理依赖和代码。 |

## 开发验证

在本仓库根目录执行；Windows 将 `./gradlew` 替换为 `./gradlew.bat`：

```shell
# IDE 插件测试（包含各迁移工具）及安装 ZIP，使用 JDK 21
./gradlew :test buildPlugin

# generator 单元测试、Gradle 插件集成测试及插件声明检查，使用 JDK 17 工具链
./gradlew -p synthetic-gradle-plugin :generator:test test validatePlugins

# 示例 Android 应用编译
./gradlew -p samples/compat-app :app:assembleDebug
```

Gradle 插件 Android 集成测试通过 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）读取 SDK，且需要 `platforms/android-35/android.jar`；未满足时该集成测试会跳过。示例应用可通过上述环境变量或 `samples/compat-app/local.properties` 中的 `sdk.dir` 配置 SDK；缺少 SDK 时示例编译会失败，不会跳过。集成测试跳过不能视为 Android 构建验证通过。

IDE 项目基于 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)。
