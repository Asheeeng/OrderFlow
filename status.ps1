[CmdletBinding(DefaultParameterSetName='Status')]
param(
    [string]$ProjectName,
    [string]$EnvFile,
    [Parameter(Mandatory, ParameterSetName='Password')]
    [ValidateSet('demo', 'navicat')][string]$Password,
    [Parameter(Mandatory, ParameterSetName='Logs')][switch]$InitLogs
)

. (Join-Path $PSScriptRoot 'scripts/orderflow-docker.ps1')
$deployment = Get-OrderFlowDeployment $ProjectName $EnvFile
if ($Password) {
    $key = if ($Password -eq 'demo') { 'DEMO_PASSWORD' } else { 'DB_INSPECT_PASSWORD' }
    Get-OrderFlowSetting $deployment.EnvFile $key
    return
}

Assert-OrderFlowDocker
if ($InitLogs) {
    Invoke-OrderFlowCompose $deployment @('logs', '--tail=80', 'init')
} else {
    Invoke-OrderFlowCompose $deployment @('ps', '-a')
}
