param(
    [string]$HostJar = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll',
    [string]$ToolsRoot = "$PSScriptRoot/../../开发资料/tools",
    [string]$JdkHome = "$ToolsRoot/jdk25/jdk-25.0.4.1",
    [switch]$MotionOnly,
    [switch]$WindowOnly,
    [switch]$Redesign,
    [switch]$CompileOnly,
    [switch]$SidebarOnly,
    [switch]$ArtworkOnly,
    [switch]$Bilibili,
    [switch]$DanmakuBenchmark,
    [string]$Stage,
    [string]$RuntimeArchive = "$PSScriptRoot/../release/spw-media-runtime-0.2.0.spmod",
    [string]$RuntimeRoot = "$PSScriptRoot/../build/runtime"
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot
$ToolsRoot = (Resolve-Path $ToolsRoot).Path
$JdkHome = (Resolve-Path $JdkHome).Path
Push-Location $workspace
try {
    if (!$Stage) {
        $stageLine = Get-Content 'build-verified.log' -TotalCount 1
        if ($stageLine -notmatch '^Build stage: (.+)$') { throw 'Build successfully into build-verified.log first.' }
        $Stage = $Matches[1]
    }
    $stage = (Resolve-Path -LiteralPath $Stage).Path
    Write-Output "Runtime test stage: $stage"
    $runtime = [IO.Path]::GetFullPath($RuntimeRoot)
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $RuntimeArchive).Path)
    try {
        foreach ($entry in $archive.Entries | Where-Object { $_.Name -and ($_.FullName.StartsWith('classes/') -or $_.FullName.StartsWith('lib/') -or $_.FullName.StartsWith('native/windows-x64/')) }) {
            $target = [IO.Path]::GetFullPath((Join-Path $runtime $entry.FullName))
            if (!$target.StartsWith($runtime + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe archive entry.' }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            if (!(Test-Path -LiteralPath $target)) { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $false) }
        }
    } finally { $archive.Dispose() }
    if (!$Bilibili) {
    $duration = if ($DanmakuBenchmark) { 35 } else { 12 }
    $size = if ($DanmakuBenchmark) { '1920x1080' } else { '640x360' }
    & "$runtime/native/windows-x64/ffmpeg.exe" -hide_banner -loglevel error -f lavfi -i "testsrc2=size=$size`:rate=30" -f lavfi -i 'anullsrc=r=48000:cl=stereo' -t $duration -c:v mpeg4 -q:v 5 -c:a aac -y 'build/synthetic-mv.mp4'
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic test video generation failed.' }
    }
    if ($Redesign -or $SidebarOnly -or $ArtworkOnly -or $DanmakuBenchmark) {
        & "$runtime/native/windows-x64/ffmpeg.exe" -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=1' -f lavfi -i 'color=c=0x2266aa:s=256x256:r=1' -map 0:a -map 1:v -c:a libmp3lame -c:v png -frames:v 1 -t 1 -disposition:v attached_pic -id3v2_version 3 -y 'build/synthetic-artwork.mp3'
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic embedded artwork generation failed.' }
    }
    $libraries = ((Get-ChildItem "$runtime/lib" -Filter '*.jar').FullName + (Get-ChildItem "$stage/lib" -Filter '*.jar').FullName | Select-Object -Unique) -join ';'
    $classpath = "$stage/classes;$stage/tests;$libraries;$ToolsRoot/kotlin-stdlib-2.4.20.jar;$ToolsRoot/spw-host.jar"
    $tests = Join-Path $workspace 'build/runtime-tests'
    New-Item -ItemType Directory -Path $tests -Force | Out-Null
    $testName = if ($Bilibili) { 'BiliProbe' } elseif ($Redesign -or $SidebarOnly -or $ArtworkOnly -or $DanmakuBenchmark) { 'RedesignSmoke' } elseif ($MotionOnly) { 'GenieBenchmark' } else { 'RuntimeSmoke' }
    & "$JdkHome/bin/java.exe" -cp "$ToolsRoot/kotlin-compiler-embeddable.jar;$ToolsRoot/kotlin-stdlib-2.4.20.jar;$HostJar;$ToolsRoot/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $classpath "-Xplugin=$ToolsRoot/kotlin-compose-compiler-plugin-embeddable.jar" -d $tests "src/testRuntime/kotlin/local/spw/mv/$testName.kt"
    if ($LASTEXITCODE -ne 0) { throw 'Runtime test compilation failed.' }
    if ($CompileOnly) { Write-Output "PASS: $testName compiles against the current build stage; not executed."; return }
    $hostRuntime = Join-Path (Split-Path (Split-Path $HostJar)) 'runtime'
    $moduleLine = Get-Content (Join-Path $hostRuntime 'release') | Where-Object { $_ -match '^MODULES="([^"]+)"$' }
    if (!$moduleLine) { throw 'Cannot determine host runtime modules.' }
    $modules = ($moduleLine -replace '^MODULES="|"$', '') -replace ' ', ','
    $testArgs = @(if ($WindowOnly) { '--window-only' }; if ($SidebarOnly) { '--sidebar-only' }; if ($ArtworkOnly) { '--artwork-only' }; if ($DanmakuBenchmark) { '--danmaku-benchmark' })
    Write-Output "Runtime mode: motion=$MotionOnly; window=$WindowOnly; args=$testArgs"
    & "$JdkHome/bin/java.exe" -ea --limit-modules $modules --enable-native-access=ALL-UNNAMED '-Djava.util.prefs.PreferencesFactory=local.spw.mv.TestPreferencesFactory' "-Dspw.test.javaHome=$hostRuntime" "-Dspw.test.runtime=$runtime" "-Dskiko.library.path=$(Split-Path $HostJar)" -cp "$tests;$stage/tests;$stage/classes;$libraries;$HostJar" "local.spw.mv.${testName}Kt" @testArgs
    if ($LASTEXITCODE -ne 0) { throw 'Runtime integration failed.' }
} finally { Pop-Location }
