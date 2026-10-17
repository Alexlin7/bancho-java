<#
.SYNOPSIS
    以 jpackage 把 bancho-shell 的可執行 jar 包成內含 Java runtime 的 Windows 資料夾，再壓成 zip。

.EXAMPLE
    pwsh bancho-shell/src/dist/package-windows.ps1 -Version 0.1.2 -Jar bancho-shell/target/bancho-shell-0.1.2-SNAPSHOT.jar -OutDir dist

    產生 dist/bancho-shell-0.1.2-windows.zip，解壓縮後最上層是 bancho-shell/（bancho-shell.exe、app/、runtime/、使用說明.txt）。
#>
param(
    [Parameter(Mandatory)] [string] $Version,
    [Parameter(Mandatory)] [string] $Jar,
    [Parameter(Mandatory)] [string] $OutDir
)

$ErrorActionPreference = 'Stop'

# jpackage 的 --app-version 只接受數字
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "版本格式必須是 X.Y.Z：$Version"
}
$jarFile = Get-Item -LiteralPath $Jar
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = (Resolve-Path -LiteralPath $OutDir).Path

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("bancho-jpackage-" + [guid]::NewGuid())
$inputDir = Join-Path $work 'input'
$image = Join-Path $work 'image'
New-Item -ItemType Directory -Force -Path $inputDir, $image | Out-Null
try {
    # --input 的內容會全部複製進 app/，只放這個 jar
    Copy-Item -LiteralPath $jarFile.FullName -Destination $inputDir

    # $APPDIR 由 launcher 在執行時展開成 app/ 的路徑，log 與指令歷史因此落在解壓縮後的資料夾內，不受啟動位置影響
    & jpackage --type app-image `
        --name bancho-shell `
        --input $inputDir `
        --main-jar $jarFile.Name `
        --app-version $Version `
        --win-console `
        --dest $image `
        --java-options '--enable-native-access=ALL-UNNAMED' `
        --java-options '-Dlogging.file.name=$APPDIR/../logs/bancho.log' `
        --java-options '-Dspring.shell.history.name=$APPDIR/../bancho-history'
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage 失敗（exit $LASTEXITCODE）"
    }

    $appImage = Join-Path $image 'bancho-shell'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '使用說明.txt') -Destination $appImage

    $zip = Join-Path $out "bancho-shell-$Version-windows.zip"
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path $appImage -DestinationPath $zip
    Write-Output $zip
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
