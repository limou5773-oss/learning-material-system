param(
    [ValidateSet('User', 'Process')]
    [string]$Scope = 'User'
)

$ocrIdInput = $null
$ocrKeyInput = $null
try {
    $ocrIdInput = Read-Host 'Tencent Cloud OCR SecretId (input hidden)' -AsSecureString
    $ocrKeyInput = Read-Host 'Tencent Cloud OCR SecretKey (input hidden)' -AsSecureString
    $ocrIdValue = [System.Net.NetworkCredential]::new('', $ocrIdInput).Password
    $ocrKeyValue = [System.Net.NetworkCredential]::new('', $ocrKeyInput).Password
    if ([string]::IsNullOrWhiteSpace($ocrIdValue) -or [string]::IsNullOrWhiteSpace($ocrKeyValue)) {
        throw 'SecretId and SecretKey must both be provided. No configuration was changed.'
    }
    [Environment]::SetEnvironmentVariable('OCR_SECRET_ID', $ocrIdValue, $Scope)
    [Environment]::SetEnvironmentVariable('OCR_SECRET_KEY', $ocrKeyValue, $Scope)
    [Environment]::SetEnvironmentVariable('OCR_PROVIDER', 'tencent', $Scope)
    [Environment]::SetEnvironmentVariable('OCR_ENABLED', 'true', $Scope)
    Write-Output "Tencent OCR environment configured for $Scope. Credential values were not printed."
    if ($Scope -eq 'User') { Write-Output 'Restart your backend terminal to use the new environment.' }
} finally {
    if ($null -ne $ocrIdInput) { $ocrIdInput.Dispose() }
    if ($null -ne $ocrKeyInput) { $ocrKeyInput.Dispose() }
    $ocrIdValue = $null; $ocrKeyValue = $null
}
