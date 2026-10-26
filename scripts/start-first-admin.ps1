# Run only for the initial administrator, after configuring the three DB_* variables.
$firstAdminSettings = @('ADMIN_BOOTSTRAP_ENABLED', 'ADMIN_BOOTSTRAP_USERNAME', 'ADMIN_BOOTSTRAP_PASSWORD', 'ADMIN_BOOTSTRAP_DISPLAY_NAME')
$priorAdminSettings = @{}
foreach ($settingName in $firstAdminSettings) {
    $priorAdminSettings[$settingName] = [Environment]::GetEnvironmentVariable($settingName, 'Process')
}
$firstAdminPasswordInput = $null
try {
    $adminMavenCommand = Get-Command mvn.cmd -CommandType Application -ErrorAction Stop
    $firstAdminUsername = Read-Host 'New administrator username (6-32 letters, digits or underscores)'
    $firstAdminDisplayName = Read-Host 'Administrator display name (1-64 characters)'
    $firstAdminPasswordInput = Read-Host 'New administrator password (8-64 characters; input hidden)' -AsSecureString
    $firstAdminPasswordValue = [System.Net.NetworkCredential]::new('', $firstAdminPasswordInput).Password
    [Environment]::SetEnvironmentVariable('ADMIN_BOOTSTRAP_ENABLED', 'true', 'Process')
    [Environment]::SetEnvironmentVariable('ADMIN_BOOTSTRAP_USERNAME', $firstAdminUsername, 'Process')
    [Environment]::SetEnvironmentVariable('ADMIN_BOOTSTRAP_DISPLAY_NAME', $firstAdminDisplayName, 'Process')
    [Environment]::SetEnvironmentVariable('ADMIN_BOOTSTRAP_PASSWORD', $firstAdminPasswordValue, 'Process')
    Write-Output 'Starting first-administrator initialization. No credential values are printed or saved by this script.'
    Write-Output 'Initialization rejects existing administrators or occupied usernames. After success, use normal backend startup.'
    $adminPomPath = Join-Path $PSScriptRoot '..\backend\pom.xml'
    & $adminMavenCommand.Source -f $adminPomPath spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed. Check the safe error message; do not post credentials.' }
} finally {
    foreach ($settingName in $firstAdminSettings) {
        [Environment]::SetEnvironmentVariable($settingName, $priorAdminSettings[$settingName], 'Process')
    }
    if ($null -ne $firstAdminPasswordInput) { $firstAdminPasswordInput.Dispose() }
    $firstAdminPasswordValue = $null
}
