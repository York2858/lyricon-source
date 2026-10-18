$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Invoke-RepositoryGit([string[]]$GitArguments) {
    $result = & git -C $repoRoot @GitArguments
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArguments -join ' ') failed" }
    return ($result -join "`n")
}

try {
    # Base the increment on HEAD so a cancelled/failed commit can be retried
    # without incrementing the already prepared version a second time.
    $null = Invoke-RepositoryGit @('rev-parse', '--verify', 'HEAD')
    $versionTracked = Invoke-RepositoryGit @('ls-tree', '--name-only', 'HEAD', '--', 'version.properties')
    if ($versionTracked) {
        $baseline = Invoke-RepositoryGit @('show', 'HEAD:version.properties')
        $code = [regex]::Match($baseline, '(?m)^versionCode=(\d+)\r?$')
        $name = [regex]::Match($baseline, '(?m)^versionName=([^\r\n]+)\r?$')
    } else {
        # Migration from versions previously declared directly in the root build script.
        $baseline = Invoke-RepositoryGit @('show', 'HEAD:build.gradle.kts')
        $code = [regex]::Match($baseline, 'extra\["appVersionCode"\]\s*=\s*(\d+)')
        $name = [regex]::Match($baseline, 'extra\["appVersionName"\]\s*=\s*"([^"]+)"')
    }
    if (!$code.Success -or !$name.Success) { throw 'Cannot read the committed application version.' }
    $nextCode = [long]$code.Groups[1].Value + 1
    if ($nextCode -gt 2100000000) { throw 'versionCode exceeds the Android application version limit.' }
    $semantic = [regex]::Match($name.Groups[1].Value, '^(\d+)\.(\d+)\.(\d+)([-+.].*)?$')
    if (!$semantic.Success) { throw 'versionName must have a major.minor.patch version, with an optional suffix.' }
    $nextPatch = [long]$semantic.Groups[3].Value + 1
    $nextName = '{0}.{1}.{2}{3}' -f $semantic.Groups[1].Value, $semantic.Groups[2].Value, $nextPatch, $semantic.Groups[4].Value

    $versionFile = Join-Path $repoRoot 'version.properties'
    $current = [System.IO.File]::ReadAllText($versionFile)
    if ([regex]::Matches($current, '(?m)^versionCode=\d+\r?$').Count -ne 1 -or
        [regex]::Matches($current, '(?m)^versionName=[^\r\n]+\r?$').Count -ne 1) {
        throw 'version.properties must contain exactly one versionCode and versionName.'
    }
    $updated = [regex]::Replace($current, '(?m)^versionCode=\d+(?=\r?$)', "versionCode=$nextCode")
    $updated = [regex]::Replace($updated, '(?m)^versionName=[^\r\n]+', "versionName=$nextName")
    [System.IO.File]::WriteAllText($versionFile, $updated, (New-Object System.Text.UTF8Encoding($false)))
    # Respect GIT_INDEX_FILE, including the temporary index of git commit --only.
    # Never stage the root build script or any other pending application changes.
    $null = Invoke-RepositoryGit @('add', '--', 'version.properties')
    Write-Host "[version] $nextName (versionCode $nextCode)"
} catch {
    [Console]::Error.WriteLine("[version] " + $_.Exception.Message)
    exit 1
}
