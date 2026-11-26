[CmdletBinding()]
param([string]$ProjectName, [string]$EnvFile)

. (Join-Path $PSScriptRoot 'scripts/orderflow-docker.ps1')
$deployment = Get-OrderFlowDeployment $ProjectName $EnvFile
Assert-OrderFlowDocker
Invoke-OrderFlowCompose $deployment @('stop')
