function Get-Norm2 {
    param([int]$X, [int]$Y)
    return ($X * $X) + ($Y * $Y)
}

Write-Output (Get-Norm2 -X 3 -Y 4)
