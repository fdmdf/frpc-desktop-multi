; ============================================================================
;  Frpc-Android-Multi.iss  ——  Windows 安装程序（Inno Setup 6）
;
;  ⚠ 编码必须是 UTF-8 with BOM（首 3 字节 EF BB BF）。
;    ISCC 在非中文区域（GitHub runner 是 en-US）下按 ANSI 读 .iss，没有 BOM 中文会整片
;    乱码。ci.yml 的「Release manifest consistency」job 会校验这 3 个字节，别删。
;
;  ⚠ 只声明一种语言 chinesesimplified。旧方案（jpackage + WiX）是按 JDK 的
;    Locale.getDefault() 选语言文件，runner 是 en-US 所以整套向导是英文；现在与系统
;    区域、JDK locale 完全无关。
;
;  ⚠ .iss 里不写死任何绝对路径：版本、打包源、图标、输出目录全部由 ISCC 的 /D 传入。
;    下面给了相对 .iss 所在目录的默认值，方便从仓库根目录直接复现。
;
;  用法：
;    ISCC.exe packaging\windows\Frpc-Android-Multi.iss ^
;      /DMyAppVersion=1.3.1 ^
;      /DMySourceDir=D:\a\...\desktopApp\build\compose\binaries\main\app\Frpc-Android-Multi ^
;      /DMyIconFile=D:\a\...\desktopApp\icon.ico ^
;      /DMyOutputDir=D:\a\...\desktopApp\build\inno
;    再追加 /DMyMachineInstall=1 即产出机器级包（默认产出用户级包）。
;
;  两个变体共用本脚本（同一份图标、同一份中文文案、同一批文件）：
;    用户级（默认）  PrivilegesRequired=lowest + {autopf}
;                    → 免 UAC，装到 %LOCALAPPDATA%\Programs\Frpc-Android-Multi
;    机器级          /DMyMachineInstall=1 → PrivilegesRequired=admin + {pf}
;                    → 需要 UAC 提权，装到 C:\Program Files\Frpc-Android-Multi
;
;  产物命名遵循 PRODUCT-版本-系统-架构[-变体].扩展名：
;    Frpc-Android-Multi-<V>-windows-x64-setup.exe          用户级
;    Frpc-Android-Multi-<V>-windows-x64-setup-machine.exe  机器级
;
;  ⚠ 上一版是 jpackage/WiX 产出的 MSI，与本安装程序的卸载表不兼容：
;    老用户无法原地升级，必须先卸载旧版（Release notes 与 CHANGELOG 均已写明）。
; ============================================================================

; 产品名必须与 desktopApp/build.gradle.kts 的 packageName、release.yml 的 PRODUCT 一致，
; 否则 {app} 下的 exe 名对不上，快捷方式会指向不存在的文件。
#define MyAppName     "Frpc-Android-Multi"
#define MyAppExeName  "Frpc-Android-Multi.exe"

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif

#ifndef MyArch
  #define MyArch "x64"
#endif

; 与 desktopApp/build.gradle.kts 的 windows.upgradeUuid 同源：去掉花括号后逐字符一致
; （含大小写），改一处就要改另一处。
#define MyAppUUID "9f2b6f7c-0f4a-4a3f-9d1c-8f5b1d2a6c31"

; 变体相关定义。用户级与机器级各写各的卸载表（HKCU / HKLM），AppId 必须区分开，
; 否则 Inno 会把两种安装模式认成同一个「已安装版本」，升级/卸载时互相踩。
; -machine 后缀只影响卸载表的键名，不影响安装内容。
#ifdef MyMachineInstall
  #define MyAppId          "{{" + MyAppUUID + "}}"
  #define MyPrivileges     "admin"
  #define MyDefaultDirName "{pf}\" + MyAppName
  #define MyOutputSubDir   "machine"
  #define MyVariantSuffix  "-machine"
  #define MyModeBevel      "机器级安装（管理员权限）"
#else
  #define MyAppId          "{{" + MyAppUUID + "-user}}"
  #define MyPrivileges     "lowest"
  #define MyDefaultDirName "{autopf}\" + MyAppName
  #define MyOutputSubDir   "user"
  #define MyVariantSuffix  ""
  #define MyModeBevel      "用户级安装（无需管理员权限）"
#endif

; 三个与机器相关的路径：默认值相对 .iss 所在目录，CI 用 /D 覆盖为绝对 Windows 路径
#ifndef MySourceDir
  #define MySourceDir "..\..\desktopApp\build\compose\binaries\main\app\" + MyAppName
#endif
#ifndef MyIconFile
  #define MyIconFile "..\..\desktopApp\icon.ico"
#endif
#ifndef MyOutputDir
  #define MyOutputDir "..\..\desktopApp\build\inno"
#endif

; 两个变体各写各的子目录，避免发布流水线里 find '*.exe' -print -quit 抓错文件
#define MyFullOutputDir      MyOutputDir + "\" + MyOutputSubDir
#define MyOutputBaseFilename MyAppName + "-" + MyAppVersion + "-windows-" + MyArch + "-setup" + MyVariantSuffix

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}

; 下面几行进 PE 版本资源 / 「添加或删除程序」，保持纯 ASCII：
; Windows GBK 下非 ASCII 会抛 Input length = 1（与 jpackage 的 description/copyright 同理）
AppPublisher=Frpc-Android-Multi contributors
AppPublisherURL=https://github.com/fdmdf/frpc-desktop-multi
AppSupportURL=https://github.com/fdmdf/frpc-desktop-multi/issues
AppUpdatesURL=https://github.com/fdmdf/frpc-desktop-multi/releases
AppCopyright=(c) 2026 Frpc-Android-Multi contributors
VersionInfoVersion={#MyAppVersion}
VersionInfoProductName={#MyAppName}
VersionInfoCompany=Frpc-Android-Multi contributors
VersionInfoDescription=Frpc-Android-Multi - frp client (desktop)

DefaultDirName={#MyDefaultDirName}
DefaultGroupName={#MyAppName}
; 不单独问「开始菜单文件夹」，但快捷方式照建（见 [Icons]）
DisableProgramGroupPage=yes

PrivilegesRequired={#MyPrivileges}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

; ← 「安装包 exe 没有图标」的直接修复：旧方案 jpackage 只把 --icon 写进 app-image 的
;   launcher，installer 里一个图标字节都没有（本机实测 9 档 PNG 0/9 命中）
SetupIconFile={#MyIconFile}
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName} {#MyAppVersion}

WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes

OutputDir={#MyFullOutputDir}
OutputBaseFilename={#MyOutputBaseFilename}

; 覆盖安装前先关掉正在跑的主程序与 frpc 子进程，避免文件被占用导致安装失败
CloseApplications=yes
CloseApplicationsFilter=frpc.exe,{#MyAppExeName}
RestartApplications=no

; 唯一语言：简体中文。MessagesFile 用 ISCC 自带的 ChineseSimplified.isl，
; 下面的 [Messages] 再逐条覆盖，不留给英文默认值任何机会。
[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Messages]
; ⚠ 转义规则（别再改回去）：
;     %n  换行（段间空一行用 %n%n）—— Inno 的 .isl 里换行只用 %n，不用双 & 符号
;     %%  字面 %
;     &X  加速键（(&N) 里的 N 就是 Alt+N），单个 & 不要动
; ---------------------------------------------------------------- 按钮
ButtonBack=< 上一步(&B)
ButtonNext=下一步(&N) >
ButtonInstall=安装(&I)
ButtonFinish=完成(&F)
ButtonCancel=取消
ButtonYes=是(&Y)
ButtonNo=否(&N)
ButtonOK=确定
ButtonBrowse=浏览(&R)...
ButtonWizardBrowse=浏览(&R)...
ButtonNewFolder=新建文件夹(&W)

; ---------------------------------------------------------------- 向导页标题
WizardSelectDir=选择安装位置
WizardSelectComponents=选择组件
WizardSelectProgramGroup=选择“开始”菜单文件夹
WizardSelectTasks=选择附加任务
WizardReady=准备安装
WizardPreparing=正在准备安装
WizardInstalling=正在安装

; ---------------------------------------------------------------- 向导页说明文字
WelcomeLabel1=欢迎使用“%1”安装向导
WelcomeLabel2=本向导将把 %1 安装到你的电脑。%n%n建议在开始安装之前关闭其它正在运行的程序。
SelectDirLabel3=安装程序将把 %1 安装到下面的文件夹。%n%n单击“下一步”继续。如果你想换一个文件夹，单击“浏览”。
SelectDirBrowseLabel=单击“下一步”继续。如果你想换一个文件夹，单击“浏览”。
SelectStartMenuFolderLabel3=安装程序将在下面的“开始”菜单文件夹中创建程序的快捷方式。%n%n单击“下一步”继续。
SelectStartMenuFolderBrowseLabel=单击“下一步”继续。如果你想换一个文件夹，单击“浏览”。
SelectTasksLabel2=选择安装期间需要执行的附加任务，然后单击“下一步”。
ReadyLabel1=安装程序已经准备好，现在可以开始把 %1 安装到你的电脑。
ReadyLabel2a=单击“安装”继续安装；如果你想查看或修改任何设置，单击“上一步”。
ReadyLabel2b=单击“安装”继续安装。
ReadyMemoDir=目标位置：
ReadyMemoGroup=“开始”菜单文件夹：
ReadyMemoTasks=附加任务：
ReadyMemoComponents=选定的组件：
; 官方 ID 是 PreparingDesc（不是 PreparingLabel），且它不接 %1 参数 —— 这里直接用
; ISPP 把产品名展开进去，避免参数个数不确定时显示成字面 %1
PreparingDesc=安装程序正在准备把 {#MyAppName} 安装到你的电脑。%n%n请稍候……
InstallingLabel=安装程序正在把 %1 安装到你的电脑。%n%n请稍候……
FinishedHeadingLabel=正在完成“%1”安装向导
FinishedLabel=安装程序已经在你的电脑上完成 %1 的安装。可以通过已创建的快捷方式启动它。%n%n单击“完成”退出安装程序。
ClickFinish=单击“完成”退出安装程序。
LaunchProgram=运行 %1

; ---------------------------------------------------------------- 退出 / 关于
ExitSetupTitle=退出安装
ExitSetupMessage=安装尚未完成。如果现在退出，%1 将不会被安装。%n%n你可以以后再次运行安装程序来完成安装。%n%n确定要退出安装程序吗？
AboutSetupMenuItem=关于安装程序(&A)...
AboutSetupTitle=关于安装程序
BeveledLabel={#MyModeBevel}

; ---------------------------------------------------------------- 占用提示
SetupAppRunningError=安装程序检测到 %1 正在运行。%n%n请先关闭它的所有窗口，然后单击“确定”继续，或单击“取消”退出。

; ---------------------------------------------------------------- 卸载
UninstallAppFullTitle=%1 卸载
UninstallAppTitle=卸载
; 官方 ID 是 StatusUninstalling（不是 UninstallingLabel）
StatusUninstalling=正在从你的电脑卸载 {#MyAppName}……%n%n请稍候……
UninstalledAll=已成功从你的电脑中移除 %1。
UninstallAppRunningError=卸载程序检测到 %1 正在运行。%n%n请先关闭它的所有窗口，然后单击“确定”继续，或单击“取消”退出。

[Tasks]
; 桌面快捷方式默认不勾选；开始菜单快捷方式无条件创建
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: unchecked

[Files]
; 打包源 = :desktopApp:createDistributable 产出的 app-image（自带 JRE 的整个目录）
Source: "{#MySourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
; 装完即跑（完成页复选框，默认勾选）；不等待退出，静默安装时不勾
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
