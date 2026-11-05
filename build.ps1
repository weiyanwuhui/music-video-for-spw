param(
    [string]$HostJar = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll',
    [string]$ToolsRoot = "$PSScriptRoot/../开发资料/tools",
    [string]$JdkHome = "$ToolsRoot/jdk25/jdk-25.0.4.1"
)
$ErrorActionPreference = 'Stop'
$HostJar = (Resolve-Path -LiteralPath $HostJar).Path
$ToolsRoot = (Resolve-Path -LiteralPath $ToolsRoot).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
Push-Location -LiteralPath $PSScriptRoot
try {
    $stage = Join-Path $PSScriptRoot ('build/staging-' + [guid]::NewGuid().ToString('N'))
    $classes = Join-Path $stage 'classes'
    $tests = Join-Path $stage 'tests'
    $lib = Join-Path $stage 'lib'
    New-Item -ItemType Directory -Force -Path $classes,$tests,$lib | Out-Null
    Write-Output "Build stage: $stage"
    $java = Join-Path $JdkHome 'bin/java.exe'
    $stdlib = Join-Path $ToolsRoot 'kotlin-stdlib-2.4.20.jar'
    $compiler = Join-Path $ToolsRoot 'kotlin-compiler-embeddable.jar'
    $compose = Join-Path $ToolsRoot 'kotlin-compose-compiler-plugin-embeddable.jar'
    $sdk = @('spw-media-sdk','spw-media-protocol','spw-media-sdk-compose')
    foreach ($name in $sdk) {
        Copy-Item -LiteralPath "$PSScriptRoot/vendor/sdk/io/github/gaboron/spwmedia/$name/0.2.0/$name-0.2.0.jar" -Destination $lib
    }
    Get-ChildItem -LiteralPath "$PSScriptRoot/vendor/runtime-lib" -Filter '*.jar' | Copy-Item -Destination $lib
    if ((Get-ChildItem $lib -Filter '*.jar').Count -ne 6) { throw 'Expected SDK, protocol, Compose surface two serialization JARs and ZXing.' }
    $dependencies = Get-Content "$PSScriptRoot/vendor/dependencies.json" -Raw | ConvertFrom-Json -AsHashtable
    foreach ($file in Get-ChildItem $lib -Filter '*.jar') {
        if ((Get-FileHash $file.FullName).Hash -ne $dependencies[$file.Name]) { throw "Dependency hash mismatch: $($file.Name)" }
    }
    $libraries = (Get-ChildItem $lib -Filter '*.jar').FullName -join ';'
    & "$PSScriptRoot/shared/build-host-resolver.ps1" -ToolsRoot $ToolsRoot -Classes $classes -JdkHome $JdkHome
    $classpath = "$classes;$stdlib;$ToolsRoot/spw-host.jar;$libraries"
    $sources = @((Get-ChildItem "$PSScriptRoot/src/main/kotlin/local/spw/mv" -Filter '*.kt').FullName)
    & $java -cp "$compiler;$stdlib;$HostJar;$ToolsRoot/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $classpath "-Xplugin=$compose" -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'MV plugin compilation failed.' }
    Copy-Item -Path "$PSScriptRoot/src/main/resources/*" -Destination $classes -Recurse -Force
    $testSources = @((Get-ChildItem "$PSScriptRoot/src/test/kotlin/local/spw/mv" -Filter '*.kt').FullName)
    if (!$testSources) { throw 'MV self-checks missing.' }
    & "$JdkHome/bin/javac.exe" -proc:none --release 25 -cp "$classes;$HostJar" -d $tests "$PSScriptRoot/shared/src/test/java/local/spw/host/HookInternals.java"
    if ($LASTEXITCODE -ne 0) { throw 'Host hook self-check compilation failed.' }
    & $java -cp "$compiler;$stdlib;$HostJar;$ToolsRoot/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$tests;$classpath" "-Xplugin=$compose" -d $tests @testSources
    if ($LASTEXITCODE -ne 0) { throw 'MV self-check compilation failed.' }
    $runtimeRelease = Join-Path (Split-Path (Split-Path $HostJar)) 'runtime/release'
    $moduleLine = Get-Content -LiteralPath $runtimeRelease | Where-Object { $_ -match '^MODULES="([^"]+)"$' }
    if (!$moduleLine) { throw 'Cannot determine host runtime modules.' }
    $modules = ($moduleLine -replace '^MODULES="|"$', '') -replace ' ', ','
    & $java -ea --limit-modules $modules --enable-native-access=ALL-UNNAMED '-Djava.util.prefs.PreferencesFactory=local.spw.mv.TestPreferencesFactory' "-Dskiko.library.path=$(Split-Path $HostJar)" -cp "$tests;$classes;$libraries;$HostJar" local.spw.mv.MvSelfTestKt
    if ($LASTEXITCODE -ne 0) { throw 'MV self-check failed; package not updated.' }
    $meta = Join-Path $classes 'META-INF'
    New-Item -ItemType Directory -Force -Path $meta | Out-Null
    $manifest = @(
        'Manifest-Version: 1.0'
        'Plugin-Class: local.spw.mv.MvPlugin'
        'Plugin-Id: local.spw.mv'
        'Plugin-Version: 0.8.14'
        'Plugin-Provider: Local'
        'Plugin-Name: Music Video for SPW'
        'Plugin-Description: 本地与 B 站 MV、弹幕、画质和链接备份'
        'Plugin-Has-Config: true'
        'Plugin-Permissions: library-read,class-transform'
        ''
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText((Join-Path $meta 'MANIFEST.MF'), $manifest, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $meta 'extensions.idx'), "local.spw.mv.MvPlayback`n", [Text.UTF8Encoding]::new($false))
    $sourceHashes = @{}
    foreach ($file in @(Get-ChildItem "$PSScriptRoot/src","$PSScriptRoot/shared" -Recurse -File) + @(Get-Item "$PSScriptRoot/build.ps1","$PSScriptRoot/vendor/dependencies.json")) {
        $sourceHashes[[IO.Path]::GetRelativePath($PSScriptRoot, $file.FullName)] = (Get-FileHash $file.FullName).Hash
    }
    $sourceHashes | ConvertTo-Json | Set-Content (Join-Path $stage 'source-hashes.json') -Encoding utf8
    $output = Join-Path $PSScriptRoot 'build/libs/music-video-for-spw-0.8.14.spmod'
    New-Item -ItemType Directory -Force -Path (Split-Path $output) | Out-Null
    $archive = [IO.Compression.ZipArchive]::new([IO.File]::Open($output, [IO.FileMode]::Create), [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($directory in @('classes','lib')) {
            foreach ($file in Get-ChildItem (Join-Path $stage $directory) -Recurse -File) {
                $relative = [IO.Path]::GetRelativePath($stage, $file.FullName).Replace('\','/')
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative) | Out-Null
            }
        }
    } finally { $archive.Dispose() }
    Write-Output "Package: $output ($((Get-Item $output).Length) bytes)"
    Write-Output "SHA256: $((Get-FileHash $output).Hash)"
} finally { Pop-Location }









