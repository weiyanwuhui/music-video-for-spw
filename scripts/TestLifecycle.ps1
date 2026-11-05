param([string]$HostJar = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot
$tools = [IO.Path]::GetFullPath((Join-Path $workspace '../开发资料/tools'))
$jdk = Join-Path $tools 'jdk25/jdk-25.0.4.1/bin'
$firstLine = Get-Content (Join-Path $workspace 'build-verified.log') -TotalCount 1
if ($firstLine -notmatch '^Build stage: (.+)$') { throw 'Build stage missing.' }
$stage = $Matches[1]
$packageLine = Get-Content (Join-Path $workspace 'build-verified.log') | Where-Object { $_ -match '^Package: (.+) \([0-9]+ bytes\)$' } | Select-Object -Last 1
if (!$packageLine -or $packageLine -notmatch '^Package: (.+) \([0-9]+ bytes\)$') { throw 'Built package missing.' }
$package = $Matches[1]
Write-Output "Lifecycle test stage: $stage"
$testRoot = Join-Path $workspace ('build/lifecycle-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $testRoot 'classes'
# The JVM Attach protocol on this runtime cannot load an agent from a Chinese path.
$profile = Join-Path $env:TEMP ('spw-mv-lifecycle-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes,$profile | Out-Null
$support = Join-Path $testRoot 'support'
New-Item -ItemType Directory -Force -Path "$support/local/spw/host","$support/local/spw/mv" | Out-Null
Get-ChildItem -LiteralPath "$stage/classes/local/spw/host" -Filter '*.class' | Copy-Item -Destination "$support/local/spw/host"
foreach ($name in @('TestPreferencesFactory','MemoryPreferences')) {
    Copy-Item -LiteralPath "$stage/tests/local/spw/mv/$name.class" -Destination "$support/local/spw/mv"
}
$classpath = "$support;$tools/kotlin-metadata-jvm-2.4.20.jar;$HostJar"
& "$jdk/java.exe" -cp "$tools/kotlin-compiler-embeddable.jar;$tools/kotlin-stdlib-2.4.20.jar;$HostJar;$tools/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$support;$tools/kotlin-stdlib-2.4.20.jar;$tools/kotlin-metadata-jvm-2.4.20.jar;$tools/spw-host.jar" "-Xplugin=$tools/kotlin-compose-compiler-plugin-embeddable.jar" -d $classes (Join-Path $workspace 'src/testRuntime/kotlin/local/spw/mv/NativeWindowProbe.kt')
if ($LASTEXITCODE -ne 0) { throw 'Outer SaltWindow probe compilation failed.' }
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp "$classes;$classpath" -d $classes (Join-Path $workspace 'src/testRuntime/java/local/spw/mv/PluginLifecycleProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle probe compilation failed.' }
$priorAppData = $env:APPDATA
try {
    $env:APPDATA = $profile
    & "$jdk/java.exe" -ea --enable-native-access=ALL-UNNAMED '-Djdk.attach.allowAttachSelf=true' `
        '-Djava.util.prefs.PreferencesFactory=local.spw.mv.TestPreferencesFactory' "-Djava.io.tmpdir=$testRoot" `
        "-Dskiko.library.path=$(Split-Path $HostJar)" -cp "$classes;$classpath" local.spw.mv.PluginLifecycleProbe `
        $profile $package
    if ($LASTEXITCODE -ne 0) { throw 'Native plugin lifecycle regression failed.' }
} finally { $env:APPDATA = $priorAppData }
