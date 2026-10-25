param(
    [ValidateSet('User', 'Process')]
    [string]$Scope = 'User',
    [ValidateSet('deepseek-flash', 'deepseek-v4-pro')]
    [string]$Model = 'deepseek-flash',
    [ValidateSet('model', 'agent')]
    [string]$ProcessorMode = 'model'
)

$modelKeyInput = $null
try {
    $modelKeyInput = Read-Host 'DeepSeek API key (input hidden)' -AsSecureString
    $modelKeyValue = [System.Net.NetworkCredential]::new('', $modelKeyInput).Password
    if ([string]::IsNullOrWhiteSpace($modelKeyValue)) {
        throw 'A DeepSeek API key is required. No configuration was changed.'
    }
    if ($modelKeyValue -notmatch '^[A-Za-z0-9._-]{1,512}$') {
        throw 'Invalid API key format. No configuration was changed.'
    }
    [Environment]::SetEnvironmentVariable('MODEL_API_KEY', $modelKeyValue, $Scope)
    [Environment]::SetEnvironmentVariable('MODEL_PROVIDER', 'deepseek', $Scope)
    [Environment]::SetEnvironmentVariable('MODEL_NAME', $Model, $Scope)
    [Environment]::SetEnvironmentVariable('TASK_PROCESSOR_MODE', $ProcessorMode, $Scope)
    Write-Output "DeepSeek model environment configured for $Scope. Credential values were not printed."
    Write-Output 'Enable TASK_WORKER_ENABLED=true only when ready to process consented tasks.'
    if ($Scope -eq 'User') { Write-Output 'Restart the backend terminal to use the new environment.' }
} finally {
    if ($null -ne $modelKeyInput) { $modelKeyInput.Dispose() }
    $modelKeyValue = $null
}
