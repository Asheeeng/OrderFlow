[CmdletBinding()]
param(
    [ValidateSet('small', 'million')][string]$Mode,
    [string]$ProjectName,
    [string]$EnvFile,
    [switch]$PrepareOnly
)

. (Join-Path $PSScriptRoot 'scripts/orderflow-docker.ps1')
$deployment = Get-OrderFlowDeployment $ProjectName $EnvFile
$modeInUse = New-OrderFlowEnvironment $deployment.EnvFile $Mode
if ($PrepareOnly) {
    Write-Output "Prepared $($deployment.EnvFile) (mode: $modeInUse)"
    return
}

Assert-OrderFlowDocker
Write-Output "Starting $($deployment.Project) (mode: $modeInUse). First import can take several minutes."
try {
    Invoke-OrderFlowCompose $deployment @('up', '--build', '-d', '--wait', '--wait-timeout', '3600')
} catch {
    try { Invoke-OrderFlowCompose $deployment @('ps', '-a') } catch { Write-Warning $_.Exception.Message }
    Write-Warning 'Run status.ps1 -InitLogs with the same environment and project options.'
    throw
}
$port = Get-OrderFlowSetting $deployment.EnvFile 'WEB_PORT'
Write-Output "OrderFlow http://127.0.0.1:$port/"
