# Windows launcher for build.sh. Docker Desktop must be running with Linux containers.
# Same pipeline and arguments as build.sh:
#
#   .\build.bat <MIBHMI.jxe> <stock libext.google.gal.receiver.so> [outdir=dist]
#
# Started by double-click, it asks for both files in standard Open dialogs and opens the output
# folder when done. Dropping both files onto build.bat works too, in either order.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$image = 'aatokombi-build'
$jxe2jarImage = if ($env:JXE2JAR_IMAGE) { $env:JXE2JAR_IMAGE } else { 'ghcr.io/adi961/jxe2jar:latest' }

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
    if (-not (Test-Path -LiteralPath $jxe -PathType Leaf)) { throw "MIBHMI.jxe not found: $jxe" }
    if (-not (Test-Path -LiteralPath $gal -PathType Leaf)) { throw "libext.google.gal.receiver.so not found: $gal" }
    $jxe = (Resolve-Path -LiteralPath $jxe).Path
    $gal = (Resolve-Path -LiteralPath $gal).Path
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    $out = (Resolve-Path -LiteralPath $out).Path

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker is required. Install and start Docker Desktop, then try again.'
    }
    $ErrorActionPreference = 'Continue'
    $osType = & docker info --format '{{.OSType}}' 2>$null
    $dockerExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($dockerExit -ne 0) {
        throw 'Docker is not accessible. Start Docker Desktop, wait until it is ready, then try again.'
    }
    if ($osType -ne 'linux') {
        throw 'Switch Docker Desktop to Linux containers, then try again.'
    }
    # Scripts run inside Linux containers; a CRLF checkout (core.autocrlf) breaks them.
    if ((Get-Content -LiteralPath 'docker/entrypoint.sh' -Raw) -match "`r") {
        throw 'Shell scripts have Windows line endings. Re-clone with "git clone -c core.autocrlf=false" or use the source ZIP.'
    }

    # 1) MIBHMI.jxe -> MIBHMI.jar, cached per firmware exactly like build.sh (same stamp file).
    New-Item -ItemType Directory -Path 'jar/lib', 'shim/lib' -Force | Out-Null
    $jxeId = (Get-FileHash -LiteralPath $jxe -Algorithm SHA256).Hash.ToLowerInvariant()
    $stamp = 'jar/lib/.MIBHMI.jxe.sha256'
    $cached = if (Test-Path -LiteralPath $stamp) { (Get-Content -LiteralPath $stamp -Raw).Trim() } else { '' }
    if ((Test-Path -LiteralPath 'jar/lib/MIBHMI.jar') -and $cached -eq $jxeId) {
        Write-Host '==> [1/4] jxe2jar: cached jar/lib/MIBHMI.jar matches this .jxe - skipping conversion'
    } else {
        $work = Join-Path ([IO.Path]::GetTempPath()) ('aatokombi-' + [Guid]::NewGuid())
        New-Item -ItemType Directory -Path $work | Out-Null
        try {
            Copy-Item -LiteralPath $jxe -Destination (Join-Path $work 'MIBHMI.jxe')
            Write-Host "==> [1/4] jxe2jar: MIBHMI.jxe -> MIBHMI.jar  ($jxe2jarImage)  [one-time per firmware]"
            $exit = Invoke-Native { docker run --rm --platform linux/amd64 -v "${work}:/data" $jxe2jarImage `
                python2 JXE2JAR.py /data/MIBHMI.jxe /data/MIBHMI.jar }
            $jar = Join-Path $work 'MIBHMI.jar'
            if ($exit -ne 0 -or -not (Test-Path -LiteralPath $jar)) { throw 'jxe2jar produced no MIBHMI.jar' }
            Copy-Item -LiteralPath $jar -Destination 'jar/lib/MIBHMI.jar' -Force
            # LF without BOM, so build.sh on macOS/Linux reads the same stamp.
            [IO.File]::WriteAllText((Join-Path $PSScriptRoot $stamp), "$jxeId`n")
            Write-Host "    MIBHMI.jar: $((Get-Item 'jar/lib/MIBHMI.jar').Length) bytes (cached for next time)"
        } finally {
            Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # 2) stage the stock libgal (skip if the argument already IS that file)
    $staged = Join-Path $PSScriptRoot 'shim/lib/libext.google.gal.receiver.so'
    if ($gal -eq $staged) {
        Write-Host "    (already in place: $staged)"
    } else {
        Copy-Item -LiteralPath $gal -Destination $staged -Force
    }

    # 3) build both artifacts in the toolchain container (image cached after the first run)
    Write-Host "==> [2/4] building toolchain image '$image' (first run downloads the base image)"
    if ((Invoke-Native { docker build -t $image -f docker/Dockerfile . }) -ne 0) {
        throw 'Could not build the toolchain image. Check the output above and your internet connection.'
    }
    Write-Host '==> [3/4] running build in container'
    if ((Invoke-Native { docker run --rm -v "${PSScriptRoot}:/work" -w /work -e HOME=/tmp $image `
            sh /work/docker/entrypoint.sh }) -ne 0) {
        throw 'Build failed. Check the output above.'
    }

    # 4) collect the two deployables
    Write-Host "==> [4/4] collect -> $out"
    Copy-Item -LiteralPath 'jar/build/AAtoKombi.jar' -Destination (Join-Path $out 'AAtoKombi.jar') -Force
    Copy-Item -LiteralPath 'shim/build/libext.google.gal.receiver.so' `
        -Destination (Join-Path $out 'libext.google.gal.receiver.so') -Force

    Write-Host ''
    Write-Host "OK. Two files ready in $out :" -ForegroundColor Green
    Write-Host '  AAtoKombi.jar                  -> SD custom/java/AAtoKombi.jar'
    Write-Host '  libext.google.gal.receiver.so  -> SD custom/sal/libext.google.gal.receiver.so'
    Write-Host 'Then on the unit: Toolbox -> Customisation -> Navi -> Enable AAtoKombi -> reboot.'

    # Same record of baked-in compile-time constants as build.sh prints.
    Write-Host ''
    Write-Host 'Build configuration:'
    Write-Host '  AA protocol advertised .......... 1.7 (NavigationState: manoeuvres, lane guidance,'
    Write-Host '                                    destination distance / travel time, rerouting notice)'
    $config = 'jar/src/de/aatokombi/Config.java'
    $pattern = '^\s*public static final [A-Za-z][A-Za-z0-9_]*\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]*);'
    $constants = if (Test-Path -LiteralPath $config) {
        Get-Content -LiteralPath $config | Select-String -Pattern $pattern -CaseSensitive
    }
    if ($constants) {
        Write-Host '  Config.java:'
        foreach ($match in $constants) {
            $groups = $match.Matches[0].Groups
            Write-Host ('    {0,-32} {1}' -f $groups[1].Value, $groups[2].Value)
        }
    } else {
        Write-Host '  Config.java: could not read the constants (declaration style changed? see build.ps1)'
    }
    # Started by double-click: show the results right away.
    if ($interactive) { Invoke-Item -LiteralPath $out }
    exit 0
} catch {
    Write-Host "`nBuild failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
