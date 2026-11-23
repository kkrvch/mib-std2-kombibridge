# build.ps1 - Windows launcher with the same pipeline, arguments and outputs as build.sh.
# Needs Docker Desktop with Linux containers: when it is missing, it offers to install it with
# winget (after asking); when its engine is not running, it starts Docker Desktop and waits.
# Started through build.bat:
#
#   .\build.bat <MIBHMI.jxe> <stock libext.google.gal.receiver.so> [outdir=dist]
#   .\build.bat <MIBHMI.jxe> - [outdir]      jar only (keep the libgal already on the unit)
#
# Started by double-click, it asks for both files in standard Open dialogs and opens the output
# folder when done. Dropping both files onto build.bat works too, in either order.
#
# The same environment switches as build.sh are passed to the build container: SKIP_TESTS,
# CLUSTER_SINK, CLUSTER_DEFINES, VIDEO_PROBE, VIDEO_PROBE_DEEP, ALLOW_SHADOW_MISMATCH
# (in PowerShell: $env:SKIP_TESTS = "1" before the call).
#
# Everything that build.sh decides with sh/awk (the AA-map switch in Config.java, the converter
# fingerprint of the MIBHMI.jar cache, the build configuration record) is run with the same
# scripts inside the toolchain container, so the two launchers cannot drift apart. Keep this file
# ASCII: Windows PowerShell 5.1 reads a script without a BOM in the ANSI code page.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$image = 'aatokombi-build'
$work = $PSScriptRoot

function Select-InputFile([string]$title, [string]$filter) {
    Write-Host " $title"
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $dialog = New-Object System.Windows.Forms.OpenFileDialog
        $dialog.Title = $title
        $dialog.Filter = "$filter|All files (*.*)|*.*"
        # A hidden topmost owner keeps the dialog in front of the console window.
        $owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true }
        $result = $dialog.ShowDialog($owner)
        $owner.Dispose()
    } catch {
        # No GUI available: drag-and-drop into the console pastes the path, quoted when it has spaces.
        return (Read-Host ' Drag the file here, then press Enter').Trim().Trim('"')
    }
    if ($result -ne [System.Windows.Forms.DialogResult]::OK) { throw 'No file selected.' }
    return $dialog.FileName
}

# Native tools write progress to stderr; keep Windows PowerShell from turning it into errors.
function Invoke-Native([scriptblock]$command) {
    $ErrorActionPreference = 'Continue'
    # Out-Host keeps the tool's output out of this function's return value.
    & $command | Out-Host
    $exit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    return $exit
}

# One sh command in the toolchain container with the repository at /work; returns its stdout.
# No double quotes in $script: Windows PowerShell 5.1 does not escape them for native programs.
function Invoke-InImage([string]$script, $envArgs = @()) {
    if ($script.Contains('"')) { throw "internal: double quote in a container command: $script" }
    $envArgs = @($envArgs | Where-Object { $_ })
    $ErrorActionPreference = 'Continue'
    $output = & docker run --rm -v "${work}:/work" -w /work -e HOME=/tmp @envArgs $image sh -c $script
    $exit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exit -ne 0) { throw "Command in the build container failed: $script" }
    return $output
}

# The build.sh environment switches that are set, as docker run -e arguments (callers wrap the
# result in @(): a function returning an empty array returns $null).
function Get-SwitchArgs([string[]]$names) {
    $list = @()
    foreach ($name in $names) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($null -ne $value -and $value -ne '') { $list += @('-e', "$name=$value") }
    }
    return $list
}

function Copy-Output([string]$from, [string]$to) {
    Copy-Item -LiteralPath $from -Destination (Join-Path $out $to) -Force
}

# Where the machine-wide Docker Desktop of the winget package (Docker.DockerDesktop) lives.
$dockerBin = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin'
$dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'

# Is docker.exe there? One installed after this console was opened is not on its PATH yet.
function Find-Docker {
    if (Get-Command docker -ErrorAction SilentlyContinue) { return $true }
    if (Test-Path -LiteralPath (Join-Path $dockerBin 'docker.exe')) {
        $env:Path += ";$dockerBin"
        return $true
    }
    return $false
}

# The engine's OS type ('linux' with Linux containers), empty when no engine answers.
function Get-DockerOsType {
    $ErrorActionPreference = 'Continue'
    $os = & docker info --format '{{.OSType}}' 2>$null
    $exit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exit -ne 0) { return '' }
    return "$os".Trim()
}

# Start Docker Desktop and wait for its engine; the first start after an install takes a while.
function Wait-DockerEngine([int]$seconds) {
    if (Test-Path -LiteralPath $dockerDesktop) {
        Write-Host '    starting Docker Desktop and waiting for its engine'
        Start-Process -FilePath $dockerDesktop
    } else {
        Write-Host '    waiting for the Docker engine'
    }
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $os = Get-DockerOsType
        if ($os) { Write-Host ''; return $os }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 5
    }
    Write-Host ''
    return ''
}

try {
    $jxe, $gal, $out = $args
    $interactive = $args.Count -eq 0
    # Explorer passes dropped files in no particular order.
    if ($jxe -like '*.so' -and $gal -like '*.jxe') { $jxe, $gal = $gal, $jxe }
    if (-not $jxe) {
        $jxe = Select-InputFile 'Select MIBHMI.jxe dumped from the unit' 'MIBHMI.jxe|*.jxe'
    }
    if (-not $gal) {
        $gal = Select-InputFile 'Select the STOCK libext.google.gal.receiver.so dumped from the unit' `
            'libext.google.gal.receiver.so|*.so'
    }
    if (-not $out) { $out = 'dist' }
    $jarOnly = $gal -eq '-'
    if (-not (Test-Path -LiteralPath $jxe -PathType Leaf)) { throw "MIBHMI.jxe not found: $jxe" }
    if (-not $jarOnly -and -not (Test-Path -LiteralPath $gal -PathType Leaf)) {
        throw "libext.google.gal.receiver.so not found: $gal"
    }
    $jxe = (Resolve-Path -LiteralPath $jxe).Path
    if (-not $jarOnly) { $gal = (Resolve-Path -LiteralPath $gal).Path }
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    $out = (Resolve-Path -LiteralPath $out).Path

    if (-not (Find-Docker)) {
        Write-Host 'Docker is not installed: the build runs in the Linux containers of Docker Desktop.'
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            throw 'Docker is required. Install Docker Desktop (https://docs.docker.com/desktop/), then try again.'
        }
        Write-Host ' Docker Desktop can be installed now with winget (package Docker.DockerDesktop). This asks'
        Write-Host ' for administrator rights, accepts the Docker Subscription Service Agreement (free for'
        Write-Host ' personal use and small businesses) and enables WSL 2, which may need a reboot.'
        $answer = Read-Host ' Install Docker Desktop now? [y/N]'
        if ($answer -notmatch '^(y|yes)$') {
            throw 'Docker is required. Install Docker Desktop, then try again.'
        }
        $exit = Invoke-Native { winget install --id Docker.DockerDesktop --exact `
            --accept-package-agreements --accept-source-agreements }
        if (-not (Find-Docker)) {
            throw "winget did not install Docker Desktop (exit code $exit). Install it by hand, then try again."
        }
        Write-Host 'Docker Desktop is installed.'
    }
    $osType = Get-DockerOsType
    if (-not $osType) {
        Write-Host 'The Docker engine is not running.'
        $osType = Wait-DockerEngine 300
    }
    if (-not $osType) {
        throw ('Docker is not accessible. Start Docker Desktop (right after an install: reboot first ' +
            'if Windows asks to), wait until it is ready, then try again.')
    }
    if ($osType -ne 'linux') {
        throw 'Switch Docker Desktop to Linux containers, then try again.'
    }
    # Scripts run inside Linux containers; a CRLF checkout (core.autocrlf) breaks them.
    foreach ($script in 'docker/entrypoint.sh', 'tools/config-get.sh', 'tools/build-config.sh') {
        if ((Get-Content -LiteralPath $script -Raw) -match "`r") {
            throw 'Shell scripts have Windows line endings. Re-clone with "git clone -c core.autocrlf=false" or use the source ZIP.'
        }
    }

    # 0) the toolchain image (cached after the first run) - it also runs the jxe2jar converter
    Write-Host "==> [1/4] building toolchain image '$image' (first run downloads the base image)"
    if ((Invoke-Native { docker build -t $image -f docker/Dockerfile . }) -ne 0) {
        throw 'Could not build the toolchain image. Check the output above and your internet connection.'
    }

    # 1) MIBHMI.jxe -> MIBHMI.jar with the vendored converter (tools/jxe2jar), cached in the same
    #    stamp file as build.sh: "<sha256 of the .jxe> <sha256 of the converter sources>".
    New-Item -ItemType Directory -Path 'jar/lib', 'shim/lib' -Force | Out-Null
    $jxeId = (Get-FileHash -LiteralPath $jxe -Algorithm SHA256).Hash.ToLowerInvariant()
    $convId = ((Invoke-InImage 'cat tools/jxe2jar/*.py | sha256sum | cut -c1-64') | Out-String).Trim()
    $stampValue = "$jxeId $convId"
    $stamp = 'jar/lib/.MIBHMI.jxe.sha256'
    $cached = if (Test-Path -LiteralPath $stamp) { (Get-Content -LiteralPath $stamp -Raw).Trim() } else { '' }
    if ((Test-Path -LiteralPath 'jar/lib/MIBHMI.jar') -and $cached -eq $stampValue) {
        Write-Host '==> [2/4] jxe2jar: cached jar/lib/MIBHMI.jar matches this .jxe and converter - skipping'
    } else {
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ('aatokombi-' + [Guid]::NewGuid())
        New-Item -ItemType Directory -Path $tmp | Out-Null
        try {
            Copy-Item -LiteralPath $jxe -Destination (Join-Path $tmp 'MIBHMI.jxe')
            Write-Host '==> [2/4] jxe2jar: MIBHMI.jxe -> MIBHMI.jar  (tools/jxe2jar, several minutes, once per firmware)'
            $exit = Invoke-Native { docker run --rm -v "${work}:/work" -v "${tmp}:/data" -e HOME=/tmp -w /data `
                $image python3 -B /work/tools/jxe2jar/jxe2jar.py /data/MIBHMI.jxe /data/MIBHMI.jar }
            $jar = Join-Path $tmp 'MIBHMI.jar'
            if ($exit -ne 0 -or -not (Test-Path -LiteralPath $jar)) { throw 'jxe2jar produced no MIBHMI.jar' }
            Copy-Item -LiteralPath $jar -Destination 'jar/lib/MIBHMI.jar' -Force
            # LF without BOM, so build.sh on macOS/Linux reads the same stamp.
            [IO.File]::WriteAllText((Join-Path $work $stamp), "$stampValue`n")
            Write-Host "    MIBHMI.jar: $((Get-Item 'jar/lib/MIBHMI.jar').Length) bytes (cached for next time)"
        } finally {
            Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # 2) stage the stock libgal into the build's drop location (skip if the argument already IS that file)
    if (-not $jarOnly) {
        $staged = Join-Path $work 'shim/lib/libext.google.gal.receiver.so'
        if ($gal -eq $staged) {
            Write-Host "    (already in place: $staged)"
        } else {
            Copy-Item -LiteralPath $gal -Destination $staged -Force
        }
    }

    # The AA map on the AID (CLUSTER_SINK libgal + libkb_dm.so): Config.ClusterMap.ENABLED, unless
    # CLUSTER_SINK=0/1 is given explicitly - decided by tools/config-get.sh, as in build.sh.
    $choice = ((Invoke-InImage ". tools/config-get.sh; cluster_sink_choice; echo `$CLUSTER_SINK'|'`$CLUSTER_SINK_FROM" `
        @(Get-SwitchArgs @('CLUSTER_SINK'))) | Out-String).Trim()
    $clusterSink, $clusterSinkFrom = $choice -split '\|', 2
    Write-Host "  AA map on the AID ............... CLUSTER_SINK=$clusterSink (from $clusterSinkFrom)"

    # 3) build all artifacts in the toolchain container; the host tests run there too
    Write-Host '==> [3/4] running build in container'
    $buildEnv = @('-e', "CLUSTER_SINK=$clusterSink", '-e', "JAR_ONLY=$(if ($jarOnly) { '1' } else { '0' })") +
        @(Get-SwitchArgs @('VIDEO_PROBE', 'VIDEO_PROBE_DEEP', 'SKIP_TESTS', 'CLUSTER_DEFINES', 'ALLOW_SHADOW_MISMATCH'))
    if ((Invoke-Native { docker run --rm -v "${work}:/work" -w /work -e HOME=/tmp @buildEnv $image `
            sh /work/docker/entrypoint.sh }) -ne 0) {
        throw 'Build failed. Check the output above.'
    }

    # 4) collect the deployables
    Write-Host "==> [4/4] collect -> $out"
    Copy-Output 'jar/build/AAtoKombi.jar' 'AAtoKombi.jar'
    if ($jarOnly) {
        if (Test-Path -LiteralPath (Join-Path $out 'libext.google.gal.receiver.so')) {
            Write-Host "  (the libext.google.gal.receiver.so already in $out is from an EARLIER build, not this one)"
        }
    } else {
        Copy-Output 'shim/build/libext.google.gal.receiver.so' 'libext.google.gal.receiver.so'
    }
    # The toolbox's Enable AAtoKombi installs libkb_dm.so; the telnet scripts are not copied any more.
    foreach ($f in 'install_dm_hook.sh', 'uninstall_dm_hook.sh') {
        $old = Join-Path $out $f
        if (Test-Path -LiteralPath $old) {
            Remove-Item -LiteralPath $old -Force
            Write-Host "  - $f removed from $out (the toolbox installs libkb_dm.so now)"
        }
    }
    # libkb_dm.so must come from the same build as the libgal (they share the cluster video memory
    # and its version): one from an earlier CLUSTER_SINK build is taken out rather than left there.
    $dm = $clusterSink -eq '1' -and -not $jarOnly -and (Test-Path -LiteralPath 'native/build/libkb_dm.so')
    if ($dm) {
        Copy-Output 'native/build/libkb_dm.so' 'libkb_dm.so'
        Write-Host '  + libkb_dm.so (DisplayManager hook for the AA cluster map)'
    } elseif (Test-Path -LiteralPath (Join-Path $out 'libkb_dm.so')) {
        Remove-Item -LiteralPath (Join-Path $out 'libkb_dm.so') -Force
        Write-Host "  - libkb_dm.so removed from $out (left from an earlier CLUSTER_SINK build)"
    }

    Write-Host ''
    Write-Host "OK. Ready in $out :" -ForegroundColor Green
    Write-Host '  AAtoKombi.jar                  -> SD custom/java/AAtoKombi.jar'
    if (-not $jarOnly) { Write-Host '  libext.google.gal.receiver.so  -> SD custom/sal/libext.google.gal.receiver.so' }
    if ($dm) { Write-Host '  libkb_dm.so                    -> SD custom/sal/libkb_dm.so  (next to the libgal)' }
    Write-Host 'Then on the unit: Toolbox -> Customisation -> Navi -> Enable AAtoKombi -> reboot.'

    # What this build actually IS: the same record as build.sh prints, by the same script.
    Write-Host ''
    Invoke-InImage 'sh tools/build-config.sh' | Out-Host

    # Started by double-click: show the results right away.
    if ($interactive) { Invoke-Item -LiteralPath $out }
    exit 0
} catch {
    Write-Host "`nBuild failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
