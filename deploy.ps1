$pass = 'KdL(4Tm1/1&14oJd'
$securePass = ConvertTo-SecureString $pass -AsPlainText -Force
$cred = New-Object System.Management.Automation.PSCredential("root", $securePass)

# Try using ssh with expect-like approach
# First test SSH connection
Write-Host "Conectando al VPS..."
ssh -o StrictHostKeyChecking=no root@179.197.229.89 "cd /root/whatsapp-MVP && git pull origin master"
