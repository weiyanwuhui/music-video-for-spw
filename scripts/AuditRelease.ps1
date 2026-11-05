param([string]$BuildLog = "$PSScriptRoot/../build-verified.log", [switch]$PackageOnly)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot))
$log = Get-Content -LiteralPath $BuildLog
if ($log[0] -notmatch '^Build stage: (.+)$') { throw 'Build stage missing.' }
$stage = [IO.Path]::GetFullPath($Matches[1])
if (!$stage.StartsWith((Join-Path $workspace 'build') + [IO.Path]::DirectorySeparatorChar)) { throw 'Unexpected build stage.' }
if (!($log -match '^PASS: [0-9]+ MV checks;')) { throw 'Self-check success is missing.' }
$lifecycleLog = Get-Content (Join-Path $workspace 'lifecycle-verified.log')
if ($lifecycleLog[0] -ne "Lifecycle test stage: $stage" -or !($lifecycleLog -match '^PASS: actual native package lifecycle, getter hooks and enabled-update-reload transactions$')) { throw 'Native enabled-update lifecycle evidence for this stage is missing.' }
if (@($lifecycleLog -match '^PASS: actual outer SaltWindow survives repeated MV entry and return with package getter hooks and native taskbar presence$').Count -ne 3) { throw 'Actual outer SaltWindow with package getter hooks regression is missing.' }
if (@($lifecycleLog -match '^PASS: native resized host restores original bounds before the return fold reveals the retained page$').Count -ne 6) { throw 'Native pre-fold bounds restore regression is missing.' }
if (@($lifecycleLog -match '^PASS: native registered sidebar method preserves Songs MV Genre ordering enabled=true$').Count -ne 3 -or !($lifecycleLog -contains 'PASS: native registered sidebar method preserves Songs MV Genre ordering enabled=false')) { throw 'Native registered sidebar insertion and unhook evidence is missing.' }
if (@($lifecycleLog -match '^PASS: actual fourteen-item native sidebar including leading divider preserves all keys enabled=true$').Count -ne 3 -or !($lifecycleLog -contains 'PASS: actual fourteen-item native sidebar including leading divider preserves all keys enabled=false')) { throw 'Complete native sidebar defaults with leading divider were not checked.' }
$redesignLog = Get-Content (Join-Path $workspace 'redesign-verified.log')
if ($redesignLog[0] -ne "Runtime test stage: $stage") { throw 'Redesign regression does not match the current build stage.' }
foreach ($evidence in @('compact toolbar uses small transport controls and thin timeline', 'volume follows continuous forward and reverse pointer drags and native backend without recoil', 'MV playlist navigation and EOF order never invoke song Next or Previous', 'single-click play/pause and double-click fullscreen do not trigger each other', 'complete root More, transport and Back remain clickable above original input shield', 'version candidate and cancel accept real pointer clicks in complete root', 'in-app search, pointer selection and confirmation persist exclusive MV ownership', 'library browser lists and searches videos with pointer-selectable actions', 'native sidebar content hook and pointer navigation open the MV library and remove its route on disable', 'redesigned MV backend and full-root pointer regression; physical monitor fullscreen and OS cursor are not tested')) {
    if (!($redesignLog -contains "PASS: $evidence")) { throw "Missing redesign evidence: $evidence" }
}
if (@($redesignLog -match '^PASS: libmpv accepts and persists aspect ').Count -ne 7) { throw 'Seven backend picture modes have not passed.' }
foreach ($evidence in @('same-title artist discrimination works through controller library matching', 'immersive controls disappear and pointer movement restores them')) {
    if (!($redesignLog -contains "PASS: $evidence")) { throw "Missing interaction evidence: $evidence" }
}
foreach ($evidence in @('real Baby featuring title repairs the old automatic link and assigns the credited artist', 'fullscreen return restores host placement under the opaque MV before the fold reveals the song page', 'library rows display real cached cover images', 'light SPW library renders all cover rows', 'artwork prefers embedded song covers, falls back to video frames and caches bounded thumbnails')) {
    if (!($redesignLog -contains "PASS: $evidence")) { throw "Missing 0.7.2 evidence: $evidence" }
}
foreach ($evidence in @('in-app folder navigation cancel and confirm preserve native window placement and mounted playback', 'list and grid share pointer-reactive foil covers with spaced square thumbnails and persistent layout switching', 'foil animation settles after pointer exit and idle frames remain pixel-stable', 'actual native sidebar builder inserts MV between Songs and Genre while preserving native item keys')) {
    if (!($redesignLog -contains "PASS: $evidence")) { throw "Missing 0.7.2 evidence: $evidence" }
}
if (!($redesignLog -contains 'PASS: complete native sidebar defaults retain the leading divider and place MV immediately after Songs')) { throw 'Native default sidebar insertion evidence is missing.' }
foreach ($evidence in @('one pointer click on a grid cover opens and plays MV','one pointer click on a list row opens and plays that MV','configuration hides the native MV entry and leaves its library route without a restart','configuration restores the clickable native MV entry without a restart')) {
    if (!($redesignLog -contains "PASS: $evidence")) { throw "Missing sidebar interaction evidence: $evidence" }
}
if (@($lifecycleLog -match '^PASS: native sidebar preference survives enabled update and restores without restart cycle=').Count -ne 3) { throw 'Native sidebar preference persistence regression is missing.' }
if (!$PackageOnly) {
$runtimeLog = Get-Content (Join-Path $workspace 'runtime-verified.log')
if (!($runtimeLog -match '^PASS: hot MV root enable/disable preserves native message callbacks after garbage collection$')) { throw 'Native window callback lifetime regression is missing.' }
if ($runtimeLog[0] -ne "Runtime test stage: $stage" -or !($runtimeLog -match '^PASS: isolated real Runtime \+ plugin controller integration;')) { throw 'Native integration success for this exact build stage is missing.' }
foreach ($evidence in @('^MOTION_PAGE:', '^MOTION_TWEEN:', '^PASS: genie shader bends silhouette and texture and ends at the exact viewport', '^PASS: button container expands in the host renderer without another window or native resize', '^PASS: retained closed MV page does not block the original entry', '^PASS: reveal starts black, then exposes the real decoded frame', '^PASS: real host fullscreen covers monitor and removes every external gap', '^PASS: rapid arrows accumulate without revealing controls or progress text', '^PASS: back animation returns to the retained original page', '^PASS: reverse genie motion retains the real video frame until the page is hidden', '^PASS: pause/play feedback is immediate and released timeline never recoils while backend is delayed', '^PASS: backward seek rejoins real playing progress without freezing at the target', '^PASS: first-use consent, persistent link, cancel and unlink exclusion', '^PASS: caption restore after MV returns the exact original size and position', '^PASS: return restores native maximized state without a stuck fullscreen flag', '^PASS: caption restore after maximized/fullscreen MV returns original bounds', '^PASS: native playback order controls EOF behavior')) {
    if (!($runtimeLog -match $evidence)) { throw "Missing motion or window evidence: $evidence" }
}
$windowLog = Get-Content (Join-Path $workspace 'window-verified.log')
if (!($runtimeLog -match '^PASS: native MV tooltip renders without an inherited popup haze provider')) { throw 'Missing-provider tooltip regression is missing.' }
if (!($runtimeLog -match '^PASS: full MV overlay accepts repeated native More and Back pointer clicks above the original page input shield')) { throw 'Original-page input shield regression is missing.' }
foreach ($evidence in @('^PASS: new and renamed One Last Kiss auto-matches without a file picker', '^PASS: neutral unfilled transport and compact settings submenus are interactive', '^PASS: native SPW link dialog follows light and dark themes and native cancel preserves the song', '^PASS: native SPW candidate selection and confirmation bind the selected MV', '^PASS: thin vertical volume slider preserves continuous drag, progress semantics and mute restore')) {
    if (!($runtimeLog -match $evidence)) { throw "Missing matching or design evidence: $evidence" }
}
if ($windowLog[0] -ne "Runtime test stage: $stage" -or !($windowLog -match '^Runtime scenario: --window-only; vsync=true$')) { throw 'Default-sync window regression for this stage is missing.' }
foreach ($evidence in @('^PASS: caption restore after MV returns the exact original size and position', '^PASS: return restores native maximized state without a stuck fullscreen flag', '^PASS: caption restore after maximized/fullscreen MV returns original bounds', '^PASS: isolated real Runtime \+ plugin controller integration;')) {
    if (!($windowLog -match $evidence)) { throw "Missing default-sync evidence: $evidence" }
}
}
$sources = Get-Content (Join-Path $stage 'source-hashes.json') -Raw | ConvertFrom-Json -AsHashtable
$biliLog = Get-Content (Join-Path $workspace 'bilibili-network-user.log')
if ($biliLog[0] -ne "Runtime test stage: $stage") { throw 'Bilibili network smoke does not match the current build.' }
foreach ($evidence in @('^PASS: live Bilibili metadata and authorized DASH qualities', '^PASS: live protobuf danmaku', '^PASS: live native QR generation, pending scan and cancellation; no login or credential written', '^PASS: live Bilibili DASH decoded first frame in', '^PASS: live Bilibili seek keeps the external audio stream attached')) {
    if (!($biliLog -match $evidence)) { throw "Missing Bilibili integration evidence: $evidence" }
}
foreach ($evidence in @('PASS: native Bilibili link prompt cancel is clickable without a network request', 'PASS: native QR login modal renders and closes above original page', 'PASS: native danmaku renderer draws scrolling top and bottom comments on a paused media clock')) {
    if (!($redesignLog -contains $evidence)) { throw "Missing Bilibili UI evidence: $evidence" }
}
foreach ($path in $sources.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $workspace $path)).Hash -ne $sources[$path]) { throw "Source changed after tested build: $path" }
}
$packageLine = $log | Where-Object { $_ -match '^Package: (.+) \([0-9]+ bytes\)$' } | Select-Object -Last 1
if (!$packageLine -or $packageLine -notmatch '^Package: (.+) \([0-9]+ bytes\)$') { throw 'Built package missing.' }
$package = $Matches[1]
$archive = [IO.Compression.ZipFile]::OpenRead($package)
try {
    $expected = @(Get-ChildItem (Join-Path $stage 'classes'),(Join-Path $stage 'lib') -Recurse -File)
    if ($archive.Entries.Count -ne $expected.Count) { throw 'Unexpected package entries.' }
    if (@($archive.Entries.FullName | Select-Object -Unique).Count -ne $archive.Entries.Count) { throw 'Duplicate package entries.' }
    foreach ($file in $expected) {
        $name = [IO.Path]::GetRelativePath($stage, $file.FullName).Replace('\','/')
        $entry = $archive.GetEntry($name)
        if (!$entry) { throw "Missing package file: $name" }
        $stream = $entry.Open()
        try { $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
        if ($hash -ne (Get-FileHash $file.FullName).Hash) { throw "Package differs from tested stage: $name" }
    }
    if ($archive.Entries.FullName -match 'SelfTest|TestPreferences|RuntimeSmoke|^classes/kotlin/(?!metadata)|^classes/androidx/|^native/') { throw 'Unexpected test, host or native files.' }
    $reader = [IO.StreamReader]::new($archive.GetEntry('classes/META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    foreach ($value in @('Plugin-Class: local.spw.mv.MvPlugin','Plugin-Id: local.spw.mv','Plugin-Name: Music Video for SPW','Plugin-Has-Config: true','Plugin-Permissions: library-read,class-transform')) {
        if (!$manifest.Contains($value)) { throw "Manifest missing: $value" }
    }
    $libraries = @($archive.Entries.FullName | Where-Object { $_.StartsWith('lib/') })
    if ($libraries.Count -ne 6) { throw 'Expected three media SDK dependencies and two serialization dependencies.' }
    if (!$archive.GetEntry('classes/META-INF/extensions.idx') -or !$archive.GetEntry('classes/local/spw/mv/MvPlayback.class')) { throw 'Playback observer missing.' }
    if (!$archive.GetEntry('classes/preference_config.json')) { throw 'Plugin configuration missing.' }
    foreach ($notice in @('LICENSE','NOTICE')) {
        if (!$archive.GetEntry("classes/META-INF/third-party/zxing/$notice")) { throw "ZXing $notice is missing." }
    }
    $reader = [IO.StreamReader]::new($archive.GetEntry('classes/preference_config.json').Open())
    try { $config = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($config.configs[0].title -ne 'Music Video for SPW') { throw 'Configuration title differs from plugin display name.' }
    if ($archive.GetEntry('classes/local/spw/mv/MvFrameHook.class')) { throw 'Unsafe native frame replacement must not be packaged.' }
} finally { $archive.Dispose() }
$runtime = Join-Path $workspace 'release/spw-media-runtime-0.2.0.spmod'
if ((Get-FileHash $runtime).Hash -ne '141F5C2BDABA675FF1DAB2AABEF72699A49FA59F916E5FA56AEED683AF47146E') { throw 'Runtime differs from upstream v0.2.0 asset digest.' }
Write-Output "PASS: package matches tested stage, source hashes, manifest, dependencies and upstream Runtime."
if ($PackageOnly) { Write-Output 'NOT VERIFIED: physical monitor fullscreen, OS cursor and installed-player acceptance; current-stage redesigned real backend/pointer, outer SaltWindow and native update regressions are mandatory.' }
Get-FileHash $package,$runtime | Select-Object Path,Hash
