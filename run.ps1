# 启动网易云标签刮削器（桌面版）
# 需要 JDK 21+：SaltUI 的 desktop 产物用 Java 21 编译（class 65）
# 用法：右键 → 使用 PowerShell 运行
#   或：pwsh -File E:\App\NeteaseTagger-KMP\run.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root "out\NeteaseTagger.jar"

if (-not (Test-Path $jar)) {
    Write-Host "找不到 $jar，先构建一次…" -ForegroundColor Yellow
    $env:JAVA_HOME = "D:\jdk21"
    $env:PATH = "D:\jdk21\bin;$env:PATH"
    $env:GRADLE_USER_HOME = "D:\gradle-home"
    $env:TEMP = "D:\dsh-temp"; $env:TMP = "D:\dsh-temp"
    Push-Location $root
    try {
        & "D:\gradle813\bin\gradle.bat" :desktopApp:fatJar --console=plain
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $jar)) {
        $jar = Join-Path $root "desktopApp\build\libs\NeteaseTagger.jar"
    }
}

$java = "D:\jdk21\bin\java.exe"
if (-not (Test-Path $java)) { $java = "java" }

Write-Host "启动：$jar" -ForegroundColor Green
& $java -jar $jar
