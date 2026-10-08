#Requires -Version 5.1
<#
.SYNOPSIS
    书源调试（BookSourceDebug）JS 源码报错渲染的免点击端到端验证脚本。

.DESCRIPTION
    全程不碰 UI 点击，靠三条 adb 能力把「注入受控坏 JS 书源 → 直接拉起调试页 → 键盘提交关键词
    → 从 logcat 读回增强错误块 → 清理」串成一条命令：
      1) run-as 直写 App 私有 Room 库（debug 包才可 run-as）；
      2) am start -n 直接启动已导出的调试 Activity（依赖 app/src/debug/AndroidManifest.xml 里 exported=true）；
      3) adb input text + keyevent 把关键词喂进调试页自动聚焦的搜索框，触发一次真实搜索。

    验证目标：出错脚本被渲染成「源码位置（第 N 行）+ 上下文 + → 指针 + ^ 列指示」的增强消息
    （移植自 legadoT 的 RhinoScriptEngine.createScriptException 机制）。结果直接从 logcat tag
    sourceDebug 读，不截图、不 OCR。

.PARAMETER AdbPath
    adb.exe 路径。留空则依次尝试 PATH、local.properties 里的 sdk.dir、常见 SDK 位置。

.PARAMETER Serial
    设备 serial。留空则取 `adb devices` 中唯一一台在线设备。

.PARAMETER Package
    被测 debug 包名。默认 appMax debug（io.legado.app.yuedu.debug）。

.PARAMETER Keyword
    提交给搜索框的关键词。仅 ASCII，避免 input text 转义问题。

.EXAMPLE
    pwsh -File scripts/book-source-debug-e2e.ps1
    pwsh -File scripts/book-source-debug-e2e.ps1 -Serial emulator-5554 -Keyword mytest

.NOTES
    前置：安装的是带 app/src/debug/AndroidManifest.xml（调试页 exported=true）的 debug 包。
    失败回退：若键盘提交未命中搜索框（logcat 无「开始搜索」），本脚本会打印 uiautomator dump
    取 bounds 后手动点击的兜底指引——那是唯一需要退回 UI 操作的情形。
    详见 docs/architecture/Web服务端到端测试方法.md 第十节。
#>
[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$Package = 'io.legado.app.yuedu.debug',
    [string]$Keyword = 'ceshi'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 关键：让 PowerShell 以 UTF-8 解码 adb 子进程 stdout，否则重定向到文件时中文日志会乱码。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [System.Text.Encoding]::UTF8

# ---- 常量 ----
$Activity  = 'io.legado.app.ui.book.source.debug.BookSourceDebugActivity'
$DbRel     = 'databases/legado.db'
$TmpDir    = '/data/local/tmp'
$BadUrl    = 'https://jserr.local.test'
$LogTag    = 'sourceDebug'
$Marker    = '源码位置'   # 增强错误消息的标志性小标题

# 多行坏 JS 书源：第 4 行调用不存在方法，触发带源码块的报错。
$BadSearchUrl = @'
@js:
var kw = key;
var base = "https://example.com/s";
var doc = java.undefinedMethod(base + "?q=" + kw, 15000);
return doc;
'@

# ---- adb 定位 ----
function Resolve-Adb {
    if ($AdbPath -and (Test-Path $AdbPath)) { return (Resolve-Path $AdbPath).Path }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @()
    $localProps = Join-Path (Get-Location) 'local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':'
            $candidates += (Join-Path $sdk 'platform-tools\adb.exe')
        }
    }
    $candidates += "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    $candidates += 'G:\software\Android\SDK\platform-tools\adb.exe'
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path } }
    throw '找不到 adb.exe，请用 -AdbPath 指定完整路径。'
}
$ADB = Resolve-Adb

# 统一入口：adb [-s serial] <args...>，返回标准输出字符串数组。
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    if ($Serial) { & $ADB '-s' $Serial @AdbArgs } else { & $ADB @AdbArgs }
}
# 在设备 shell 里跑一条命令（引号交由 adb shell 处理）。
function Invoke-Shell {
    param([Parameter(Mandatory = $true)][string]$Cmd)
    Invoke-Adb @('shell', $Cmd)
}

# ---- serial 解析 ----
if (-not $Serial) {
    $devLines = Invoke-Adb @('devices') | Where-Object { $_ -match '\sdevice$' }
    if ($devLines.Count -lt 1) { throw '没有在线设备，请先启动模拟器/连接真机。' }
    if ($devLines.Count -gt 1) { throw '检测到多台在线设备，请用 -Serial 指定其一。' }
    $Serial = ($devLines[0] -split '\s+')[0]
    Write-Host "自动选用设备: $Serial"
}

# ---- 工作文件 ----
$workDir = Join-Path (Get-Location) '.tmp-e2e'
if (-not (Test-Path $workDir)) { New-Item -ItemType Directory -Path $workDir | Out-Null }
$injectLocal = Join-Path $workDir 'e2e-inject.sql'
$verifyLocal = Join-Path $workDir 'e2e-verify.sql'
$capLog      = Join-Path $workDir 'e2e-cap.log'

function Write-SqlFile {
    param([string]$Path, [string]$SearchUrlLiteral)
    # 幂等：先删同名行，再插入受控坏 JS 源。NOT NULL 列必须给全。
    $sql = @"
DELETE FROM book_sources WHERE bookSourceUrl='$BadUrl';
INSERT INTO book_sources
  (bookSourceUrl, bookSourceName, bookSourceType, lastUpdateTime, respondTime, weight, enabled, searchUrl)
VALUES
  ('$BadUrl', 'JS报错渲染验证源', 0, 1, 0, 0, 1,
'$SearchUrlLiteral');
"@
    Set-Content -Path $Path -Value $sql -Encoding UTF8
}

$ok = $false
try {
    Write-SqlFile -Path $injectLocal -SearchUrlLiteral $BadSearchUrl

    # 1) 停 App 后注入（避免 Room/WAL 与外部写冲突）。
    Invoke-Shell "am force-stop $Package" | Out-Null
    Invoke-Adb @('push', $injectLocal, "$TmpDir/e2e-inject.sql") | Out-Null
    # 设备 sqlite3 用 `< 文件` 方式执行，绕开内联 SQL 的引号地狱。
    $injectOut = Invoke-Shell "run-as $Package sh -c 'sqlite3 $DbRel < $TmpDir/e2e-inject.sql'"
    if ($injectOut -match 'error|Error') { throw "注入失败: $injectOut" }

    # 2) 校验插入行数（应为 1）。
    Set-Content -Path $verifyLocal -Value "SELECT COUNT(*) FROM book_sources WHERE bookSourceUrl='$BadUrl';" -Encoding UTF8
    Invoke-Adb @('push', $verifyLocal, "$TmpDir/e2e-verify.sql") | Out-Null
    $cnt = (Invoke-Shell "run-as $Package sh -c 'sqlite3 $DbRel < $TmpDir/e2e-verify.sql'") | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1
    if ($cnt -ne '1') { throw "书源注入后行数异常: '$cnt'" }
    Write-Host "[1/5] 已注入受控坏 JS 书源 $BadUrl" -ForegroundColor Green

    # 3) 直接拉起调试页（依赖 debug manifest 的 exported=true）。
    Invoke-Shell "am start -n $Package/$Activity --es key $BadUrl" | Out-Null
    Start-Sleep -Seconds 4
    $top = Invoke-Shell "dumpsys activity activities | grep -i topResumedActivity"
    if ($top -notmatch [regex]::Escape($Activity)) {
        throw "am start 未能把调试页带到前台（可能被 SecurityException 拦截：请确认装的是含 debug/AndroidManifest.xml exported=true 的包）。原始输出: $top"
    }
    Write-Host "[2/5] 调试页已免点击拉起并在前台" -ForegroundColor Green

    # 4) 清一次缓冲区（把源加载噪声挡在触发之前），再键盘提交关键词。
    #    搜索框在 Activity 创建时自动聚焦，input text 直接落进去，无需点击。
    Invoke-Adb @('logcat', '-c') | Out-Null
    Invoke-Shell "input text $Keyword" | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-Shell "input keyevent 66" | Out-Null
    Write-Host "[3/5] 已用键盘提交关键词 '$Keyword' 触发搜索" -ForegroundColor Green

    # 5) 轮询同步 dump logcat（主会话已设 UTF-8，中文不乱码），断言增强错误块出现。
    #    不用后台 Job：Job 子宿主不继承 [Console]::OutputEncoding，写出的文件会乱码。
    $deadline = (Get-Date).AddSeconds(25)
    $content = ''
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $dump = Invoke-Adb @('logcat', '-d', '-v', 'brief', "${LogTag}:V", 'AndroidRuntime:E', '*:S')
        $content = ($dump -join "`n")
        if ($content -match [regex]::Escape($Marker)) { break }
    }
    if ($content) { Set-Content -Path $capLog -Value $content -Encoding UTF8 }

    Write-Host "[4/5] logcat 抓取结果：" -ForegroundColor Green
    if ($content -match [regex]::Escape($Marker)) {
        # 打印从 ScriptException 行到「in <script」结尾的那一段，最贴近 legadoT 效果展示。
        $lines = $content -split "`r?`n" | Where-Object { $_ -match $LogTag -or $_ -match 'RhinoScriptEngine|AnalyzeUrl' }
        ($lines | Select-Object -First 40) -join [Environment]::NewLine | Write-Host
        Write-Host "`n==> 命中『$Marker』增强错误块，JS 源码报错渲染验证通过。" -ForegroundColor Cyan
        $ok = $true
    } else {
        Write-Host "!! 未在 logcat 看到『$Marker』。可能键盘提交未命中搜索框。" -ForegroundColor Yellow
        Write-Host @"
失败回退（唯一需要退到 UI 的情形）：
  1) 取搜索框真实坐标：adb -s $Serial exec-out uiautomator dump /dev/tty
     找到 search_view / 输入框节点的 bounds=[l,t][r,b]，中心 = ((l+r)/2,(t+b)/2)。
  2) 先点一下再提交：adb -s $Serial shell input tap <cx> <cy>
     adb -s $Serial shell input text $Keyword
     adb -s $Serial shell input keyevent 66
  注意截图像素常不等于设备像素，务必以 uiautomator bounds 为准。
"@ -ForegroundColor Yellow
    }
}
finally {
    # 清理：删测试源、删设备/本地临时文件、停 App。
    Write-Host '[5/5] 清理测试环境…' -ForegroundColor Green
    $cleanupSql = "DELETE FROM book_sources WHERE bookSourceUrl='$BadUrl';"
    Set-Content -Path $verifyLocal -Value $cleanupSql -Encoding UTF8
    Invoke-Adb @('push', $verifyLocal, "$TmpDir/e2e-verify.sql") | Out-Null
    Invoke-Shell "run-as $Package sh -c 'sqlite3 $DbRel < $TmpDir/e2e-verify.sql'" | Out-Null
    Invoke-Shell "rm -f $TmpDir/e2e-inject.sql $TmpDir/e2e-verify.sql" | Out-Null
    Invoke-Shell "am force-stop $Package" | Out-Null
    Remove-Item $injectLocal, $verifyLocal, $capLog -Force -ErrorAction SilentlyContinue
}

if ($ok) { exit 0 } else { exit 1 }
