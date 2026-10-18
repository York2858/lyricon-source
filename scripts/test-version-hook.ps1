$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $sourceRoot ('build/version-hook-tests/' + [guid]::NewGuid().ToString('N'))
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-FixtureFile([string]$Path, [string]$Text) {
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

function Invoke-FixtureGit([string]$Directory, [string[]]$GitArguments, [switch]$ExpectFailure) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & git -C $Directory @GitArguments 2>&1
        $commandExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($ExpectFailure -and $commandExit -eq 0) { throw 'Expected git commit to fail.' }
    if (!$ExpectFailure -and $commandExit -ne 0) { throw ($output | Out-String) }
    return (($output | Out-String).Trim())
}

function Assert-Equal($Expected, $Actual, [string]$Description) {
    if ($Expected -cne $Actual) { throw "$Description -- expected [$Expected], received [$Actual]" }
}

function Assert-Version([string]$Directory, [int]$Code, [string]$Name) {
    $committed = Invoke-FixtureGit $Directory @('show', 'HEAD:version.properties')
    Assert-Equal $true ($committed -match "(?m)^versionCode=$Code`r?$") 'Committed version code'
    Assert-Equal $true ($committed -match ('(?m)^versionName=' + [regex]::Escape($Name) + '\r?$')) 'Committed version name'
}

function New-Fixture([string]$Name, [switch]$Legacy) {
    $directory = Join-Path $testRoot $Name
    $null = New-Item -ItemType Directory -Force -Path $directory
    $null = Invoke-FixtureGit $directory @('init', '--quiet')
    $null = Invoke-FixtureGit $directory @('config', 'user.name', 'Version hook test')
    $null = Invoke-FixtureGit $directory @('config', 'user.email', 'version-hook-test@example.invalid')
    $null = Invoke-FixtureGit $directory @('config', 'commit.gpgsign', 'false')
    $null = Invoke-FixtureGit $directory @('config', 'core.autocrlf', 'false')
    Write-FixtureFile (Join-Path $directory 'tracked.txt') "initial`n"
    Write-FixtureFile (Join-Path $directory 'unrelated.txt') "original`n"
    if ($Legacy) {
        Write-FixtureFile (Join-Path $directory 'build.gradle.kts') "extra[`"appVersionCode`"] = 51`nextra[`"appVersionName`"] = `"1.0.40.rc`"`n"
    } else {
        Write-FixtureFile (Join-Path $directory 'version.properties') "versionCode=51`nversionName=1.0.40.rc`n"
    }
    foreach ($relative in @('.githooks/pre-commit', '.githooks/post-commit', 'scripts/bump-version.ps1')) {
        Write-FixtureFile (Join-Path $directory $relative) ([System.IO.File]::ReadAllText((Join-Path $sourceRoot $relative)).Replace("`r`n", "`n"))
    }
    $null = Invoke-FixtureGit $directory @('add', '.')
    $null = Invoke-FixtureGit $directory @('-c', 'core.hooksPath=.disabled-hooks', 'commit', '-m', 'fixture baseline')
    $null = Invoke-FixtureGit $directory @('config', 'core.hooksPath', '.githooks')
    return $directory
}

$repo = New-Fixture 'normal'
Write-FixtureFile (Join-Path $repo 'unrelated.txt') "keep this unstaged`n"
Write-FixtureFile (Join-Path $repo 'tracked.txt') "first change`n"
$null = Invoke-FixtureGit $repo @('add', 'tracked.txt')
$null = Invoke-FixtureGit $repo @('commit', '-m', 'first version increment')
Assert-Version $repo 52 '1.0.41.rc'
Assert-Equal 'original' (Invoke-FixtureGit $repo @('show', 'HEAD:unrelated.txt')) 'Unstaged file stays out of commit'
Write-Host 'PASS: normal commit increments both versions and preserves unstaged files'

Write-FixtureFile (Join-Path $repo '.githooks/commit-msg') "#!/bin/sh`nexit 1`n"
Write-FixtureFile (Join-Path $repo 'tracked.txt') "retry change`n"
$null = Invoke-FixtureGit $repo @('add', 'tracked.txt')
$null = Invoke-FixtureGit $repo @('commit', '-m', 'rejected commit') -ExpectFailure
Assert-Version $repo 52 '1.0.41.rc'
Write-FixtureFile (Join-Path $repo '.githooks/commit-msg') "#!/bin/sh`nexit 0`n"
$null = Invoke-FixtureGit $repo @('commit', '-m', 'successful retry')
Assert-Version $repo 53 '1.0.42.rc'
Write-Host 'PASS: rejected commit and retry increment only once'

$null = Invoke-FixtureGit $repo @('commit', '--amend', '--no-edit')
Assert-Version $repo 54 '1.0.43.rc'
Write-Host 'PASS: amend creates the next version'

$null = Invoke-FixtureGit $repo @('add', 'unrelated.txt')
Write-FixtureFile (Join-Path $repo 'tracked.txt') "partial commit`n"
$null = Invoke-FixtureGit $repo @('commit', '--only', 'tracked.txt', '-m', 'partial commit')
Assert-Version $repo 55 '1.0.44.rc'
Assert-Equal 'original' (Invoke-FixtureGit $repo @('show', 'HEAD:unrelated.txt')) 'Partial commit excludes other staged files'
Assert-Equal 'unrelated.txt' (Invoke-FixtureGit $repo @('diff', '--cached', '--name-only')) 'Partial commit preserves staged selection'
Write-Host 'PASS: partial commit keeps unrelated staged changes intact'

$legacy = New-Fixture 'legacy' -Legacy
Write-FixtureFile (Join-Path $legacy 'version.properties') "versionCode=51`nversionName=1.0.40.rc`n"
Write-FixtureFile (Join-Path $legacy 'tracked.txt') "migration`n"
$null = Invoke-FixtureGit $legacy @('add', 'tracked.txt')
$null = Invoke-FixtureGit $legacy @('commit', '-m', 'migrate version configuration')
Assert-Version $legacy 52 '1.0.41.rc'
Write-Host 'PASS: first commit migrates the committed Gradle version correctly'
Write-Host "All version hook checks passed. Isolated repositories: $testRoot"
