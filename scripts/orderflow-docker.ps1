Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:OrderFlowRoot = Split-Path -Parent $PSScriptRoot
$script:OrderFlowPasswords = @(
    'DB_ROOT_PASSWORD', 'DB_APP_PASSWORD', 'DB_INSPECT_PASSWORD',
    'DB_REPL_PASSWORD', 'REDIS_PASSWORD', 'DEMO_PASSWORD'
)

function Get-OrderFlowDeployment {
    param([string]$ProjectName, [string]$EnvFile)

    if (-not $ProjectName) { $ProjectName = $env:ORDERFLOW_PROJECT }
    if (-not $ProjectName) { $ProjectName = 'orderflow-docker' }
    if ($ProjectName -cnotmatch '^[a-z0-9][a-z0-9_-]*$' -or $ProjectName -eq 'orderflow') {
        throw 'Use a distinct lowercase Compose project name. orderflow is reserved for the host deployment.'
    }

    if (-not $EnvFile) { $EnvFile = $env:ORDERFLOW_ENV_FILE }
    if (-not $EnvFile) { $EnvFile = '.env.docker' }
    if (-not [IO.Path]::IsPathRooted($EnvFile)) {
        $EnvFile = Join-Path $script:OrderFlowRoot $EnvFile
    }
    $EnvFile = [IO.Path]::GetFullPath($EnvFile)
    if ([IO.Path]::GetFileName($EnvFile) -eq '.env') {
        throw 'The host .env is reserved. Use .env.docker or a different Docker environment file.'
    }
    [pscustomobject]@{
        Project = $ProjectName
        EnvFile = $EnvFile
        ComposeFile = Join-Path $script:OrderFlowRoot 'compose.docker.yml'
    }
}

function Get-OrderFlowSetting {
    param([string]$Path, [string]$Name)
    if (-not [IO.File]::Exists($Path)) { throw "No $Path. Run start.ps1 first." }
    $prefix = "$Name="
    $entries = @([IO.File]::ReadAllLines($Path) | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) })
    if ($entries.Count -ne 1) { throw "Expected one $Name setting in $Path." }
    $entries[0].Substring($prefix.Length)
}

function New-OrderFlowEnvironment {
    param([string]$Path, [string]$Mode)

    if (-not [IO.File]::Exists($Path)) {
        $selectedMode = if ($Mode) { $Mode } else { 'million' }
        $template = Join-Path $script:OrderFlowRoot '.env.docker.example'
        $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None).Dispose()
            if ($env:OS -eq 'Windows_NT') {
                $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
                $acl = [Security.AccessControl.FileSecurity]::new()
                $acl.SetAccessRuleProtection($true, $false)
                $acl.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
                    $sid, [Security.AccessControl.FileSystemRights]::FullControl,
                    [Security.AccessControl.AccessControlType]::Allow))
                Set-Acl -LiteralPath $temporary -AclObject $acl
            } else {
                & chmod 600 $temporary
                if ($LASTEXITCODE -ne 0) { throw 'Could not restrict Docker environment file access.' }
            }

            $lines = foreach ($line in [IO.File]::ReadAllLines($template)) {
                if ($line.StartsWith('SEED_MODE=')) {
                    "SEED_MODE=$selectedMode"
                } elseif ($line -match '^[A-Z_]+_PASSWORD=$') {
                    $size = if ($line -eq 'DB_REPL_PASSWORD=') { 16 } else { 18 }
                    $bytes = New-Object byte[] $size
                    $random.GetBytes($bytes)
                    $line + [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
                } else {
                    $line
                }
            }
            [IO.File]::WriteAllLines($temporary, [string[]]$lines, [Text.UTF8Encoding]::new($false))
            try {
                [IO.File]::Move($temporary, $Path)
            } catch [IO.IOException] {
                if (-not [IO.File]::Exists($Path)) { throw }
            }
        } finally {
            $random.Dispose()
            if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
        }
    }

    $existing = Get-OrderFlowSetting $Path 'SEED_MODE'
    if ($existing -cnotmatch '^(small|million)$') { throw "Invalid SEED_MODE in $Path." }
    if ($Mode -and $Mode -cne $existing) {
        throw "This environment uses $existing. Use another project name and environment file to select $Mode."
    }
    $existing
}

function Assert-OrderFlowDocker {
    if (-not (Get-Command docker -CommandType Application -ErrorAction SilentlyContinue)) {
        throw 'Install and start Docker Desktop first.'
    }
    $engine = & docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0 -or $engine -ne 'linux') {
        throw 'Start Docker Desktop and select the Linux container engine.'
    }
}

function Invoke-OrderFlowCompose {
    param($Deployment, [string[]]$DockerArguments)
    if (-not [IO.File]::Exists($Deployment.EnvFile)) {
        throw "No $($Deployment.EnvFile). Run start.ps1 first."
    }

    $options = @('--project-name', $Deployment.Project, '--file', $Deployment.ComposeFile,
        '--env-file', $Deployment.EnvFile)
    $saved = @{}
    try {
        $names = @('SEED_MODE', 'WEB_PORT', 'MYSQL_PRIMARY_PORT', 'MYSQL_REPLICA_PORT') + $script:OrderFlowPasswords
        foreach ($name in $names) {
            $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
        & docker compose @options @DockerArguments
        if ($LASTEXITCODE -ne 0) { throw "Docker Compose exited with code $LASTEXITCODE." }
    } finally {
        foreach ($name in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
    }
}
