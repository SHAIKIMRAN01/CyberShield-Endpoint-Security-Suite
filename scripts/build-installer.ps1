<#
.SYNOPSIS
    Builds CyberShield's Windows installer (an .msi) with jpackage.

.DESCRIPTION
    1. Maven builds the app jar and copies its libraries to target\libs (the tests run
       too, unless -SkipTests).
    2. jlink makes a small Java runtime with only the modules CyberShield needs.
    3. jpackage makes the app folder: target\installer\app-image\CyberShield, with
       CyberShield.exe and its own Java. It runs without installing.
    4. jpackage packs that same folder into target\installer\CyberShield-<version>.msi:
       a per-user install (no administrator needed) into
       %LOCALAPPDATA%\CyberShield App, with Start menu and desktop shortcuts and an
       entry in Windows' installed apps to remove it.

    The app keeps its data in %LOCALAPPDATA%\CyberShield (see config/DataFolders.java),
    a folder of its own, so removing or upgrading the app leaves the data alone, and
    deleting a leftover app folder can never take the data with it.

.PARAMETER Jdk
    A JDK 21 or newer with jpackage. The installer bundles a runtime made from it.
    Defaults to JAVA_HOME.

.PARAMETER WixDir
    The folder holding WiX Toolset 3.14 (candle.exe, light.exe), which jpackage needs
    for the .msi. Not needed with -AppImageOnly.

.PARAMETER AppImageOnly
    Stop after the app folder: no .msi, no WiX needed.

.PARAMETER SkipTests
    Don't run the tests while building (they take a few minutes).

.EXAMPLE
    .\scripts\build-installer.ps1 -Jdk "C:\Program Files\Java\jdk-25.0.2" -WixDir E:\project\tools\wix314
#>

param(
    [string] $Jdk = $env:JAVA_HOME,
    [string] $WixDir = "",
    [switch] $AppImageOnly,
    [switch] $SkipTests
)

$ErrorActionPreference = "Stop"

# Run from the project folder, wherever the script was started from. The folder, JAVA_HOME
# and PATH are put back at the end, so the PowerShell window that ran it is left as it was.
$project = Split-Path -Parent $PSScriptRoot
$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:PATH
Push-Location $project

try {

    function Invoke-Tool([string] $tool, [string[]] $arguments) {
        & $tool @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$([System.IO.Path]::GetFileName($tool)) failed with exit code $LASTEXITCODE."
        }
    }

    if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk "bin\jpackage.exe"))) {
        throw "No JDK with jpackage found. Pass -Jdk with a JDK 21 or newer (JAVA_HOME is '$Jdk')."
    }

    # jlink reads the JDK's modules from its jmods folder. JDKs since 24 may come without
    # one and link from themselves instead (JEP 493); jlink says so if it can't.
    $linkFrom = @()
    if (Test-Path (Join-Path $Jdk "jmods")) {
        $linkFrom = @("--module-path", (Join-Path $Jdk "jmods"))
    }

    $jlink = Join-Path $Jdk "bin\jlink.exe"
    $jpackage = Join-Path $Jdk "bin\jpackage.exe"

    if (-not $AppImageOnly) {
        if (-not $WixDir -or -not (Test-Path (Join-Path $WixDir "candle.exe")) -or -not (Test-Path (Join-Path $WixDir "light.exe"))) {
            throw "jpackage needs WiX Toolset 3.14 for the .msi: pass -WixDir with the folder holding candle.exe and light.exe, or use -AppImageOnly."
        }
        $env:PATH = (Resolve-Path $WixDir).Path + ";" + $env:PATH
    }

    $version = ([xml](Get-Content (Join-Path $project "pom.xml"))).project.version
    $jar = "CyberShield-$version.jar"

    $target = Join-Path $project "target"
    $inputDir = Join-Path $target "installer-input"
    $runtime = Join-Path $target "installer-runtime"
    $out = Join-Path $target "installer"
    $imageRoot = Join-Path $out "app-image"
    $image = Join-Path $imageRoot "CyberShield"

    Write-Host "CyberShield $version" -ForegroundColor Cyan

    # ------------------------------------------------------------------ 1. the jar and its libraries

    Write-Host "1/4 Building with Maven" -ForegroundColor Cyan

    $mavenArguments = @("--batch-mode", "--no-transfer-progress", "package")
    if ($SkipTests) {
        $mavenArguments += "-DskipTests"
    }

    $env:JAVA_HOME = $Jdk
    Invoke-Tool "mvn.cmd" $mavenArguments

    foreach ($folder in @($inputDir, $runtime, $out)) {
        if (Test-Path $folder) {
            Remove-Item -Recurse -Force $folder
        }
    }

    New-Item -ItemType Directory -Force $inputDir | Out-Null
    Copy-Item (Join-Path $target $jar) $inputDir
    Copy-Item (Join-Path $target "libs\*.jar") $inputDir

    # ------------------------------------------------------------------ 2. the Java runtime

    Write-Host "2/4 Linking the Java runtime" -ForegroundColor Cyan

    # What the app and its libraries use, found with jdeps (java.desktop, java.sql and so on),
    # plus locale data for dates in every language, extra character sets, elliptic-curve
    # crypto, and java.management, which the test runner needs when the tests run on this
    # runtime (see the README).
    $modules = @(
        "java.base", "java.desktop", "java.sql", "java.logging", "java.scripting", "java.xml",
        "jdk.unsupported", "jdk.localedata", "jdk.charsets", "jdk.crypto.ec", "java.management"
    ) -join ","

    Invoke-Tool $jlink ($linkFrom + @(
        "--add-modules", $modules,
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--output", $runtime
    ))

    # ------------------------------------------------------------------ 3. the app folder

    Write-Host "3/4 Making the app folder" -ForegroundColor Cyan

    $common = @(
        "--name", "CyberShield",
        "--app-version", $version,
        "--vendor", "Imran",
        "--description", "CyberShield Endpoint Security Suite: passwords and files kept private on a shared PC",
        "--copyright", "Copyright (c) 2026 Imran. All rights reserved.",
        "--icon", (Join-Path $project "src\main\packaging\CyberShield.ico")
    )

    # JavaFX runs from the class path here, as in the tests, so the viewer reaches JavaFX's
    # media cache without the --add-exports that mvn javafx:run (module path) needs.
    Invoke-Tool $jpackage ($common + @(
        "--type", "app-image",
        "--input", $inputDir,
        "--main-jar", $jar,
        "--main-class", "com.cybershield.app.Main",
        "--runtime-image", $runtime,
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--dest", $imageRoot
    ))

    Write-Host "    $image\CyberShield.exe"

    if ($AppImageOnly) {
        Write-Host "Done: the app folder is ready (no installer asked for)." -ForegroundColor Green
        return
    }

    # ------------------------------------------------------------------ 4. the installer

    Write-Host "4/4 Packing the installer" -ForegroundColor Cyan

    Invoke-Tool $jpackage ($common + @(
        "--type", "msi",
        "--app-image", $image,
        "--win-per-user-install",
        # One folder level only: Windows Installer refuses (ICE64) a deeper path in the user's
        # profile, such as Programs\CyberShield, whose middle folder it wouldn't remove.
        "--install-dir", "CyberShield App",
        "--win-menu",
        "--win-menu-group", "CyberShield",
        "--win-shortcut",
        # The same for every version, so a newer installer replaces the older one.
        "--win-upgrade-uuid", "b5379a6d-9fdc-4e70-9841-469fe2c79c69",
        "--dest", $out
    ))

    $msi = Join-Path $out "CyberShield-$version.msi"

    Write-Host ("Done: {0} ({1:N0} MB)" -f $msi, ((Get-Item $msi).Length / 1MB)) -ForegroundColor Green

} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:PATH = $savedPath
    Pop-Location
}
