# The shelf, in PowerShell: open this file and press F5 (Debug). The script runs under PowerShell Editor
# Services, which stops at your breakpoints. Every suggested spot is a "break here" comment.
# `pwsh shelf.ps1` runs it outside Editora, `pwsh shelf.ps1 3` totals three items, `pwsh shelf.ps1 many`
# reports a bad quantity, and `pwsh shelf.ps1 crash` ends on an error nobody catches.
param([string] $Argument)

# No Set-StrictMode here: under it, PowerShell Editor Services' hit-count breakpoints never stop.
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Pricing.psm1') -Force

# What is on the shelf, in the Script scope of Variables; expand one to see its properties.
$Items = @(
    [pscustomobject]@{ Sku = 'A-1'; Name = 'Pencil'; Quantity = 12; Price = 0.40 }
    [pscustomobject]@{ Sku = 'A-2'; Name = 'Notebook'; Quantity = 3; Price = 2.75 }
    [pscustomobject]@{ Sku = 'B-1'; Name = 'Stapler'; Quantity = 1; Price = 8.90 }
    [pscustomobject]@{ Sku = 'B-2'; Name = 'Tape'; Quantity = 4; Price = 1.20 }
    [pscustomobject]@{ Sku = 'C-1'; Name = 'Marker'; Quantity = 10; Price = 1.05 }
    [pscustomobject]@{ Sku = 'C-2'; Name = 'Eraser'; Quantity = 25; Price = 0.30 }
    [pscustomobject]@{ Sku = 'C-3'; Name = 'Ruler'; Quantity = 2; Price = 1.50 }
    [pscustomobject]@{ Sku = 'D-1'; Name = 'Glue'; Quantity = 6; Price = 2.10 }
)

# Totals up to Limit items: $total, $lines and $byPrefix change on every pass.
function Invoke-Checkout {
    param([object[]] $Shelf, [int] $Limit)

    $lines = [System.Collections.Generic.List[string]]::new()
    $byPrefix = [ordered]@{}
    $total = 0.0
    foreach ($item in $Shelf | Select-Object -First $Limit) {
        $discount = Get-Discount -Item $item # break here: Step Into lands in Pricing.psm1
        $price = Invoke-Discount -Discount $discount -Price $item.Price
        $subtotal = $price * $item.Quantity
        $lines.Add(('{0,-4} {1,-9} {2,3} x {3,5:N2} = {4,7:N2}' -f $item.Sku, $item.Name, $item.Quantity, $price, $subtotal))
        $prefix = $item.Sku.Substring(0, 1)
        if (-not $byPrefix.Contains($prefix)) { $byPrefix[$prefix] = 0 }
        $byPrefix[$prefix] += $item.Quantity
        $total += $subtotal # break here: a watch on $total, or a logpoint: $($item.Name) total = $total
    }
    $cheap = foreach ($item in $Shelf) {
        if ($item.Price -lt 1.0) { # break here: a conditional breakpoint, $item.Sku -eq 'C-2'
            $item.Name
        }
    }
    $counts = ($byPrefix.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }) -join ' '
    $lines.Add("cheap: $(($cheap | Sort-Object) -join ', ')  by prefix: $counts")
    return $lines, $total
}

# Turns "12" into 12; a word throws, and the caller catches it.
function ConvertTo-Quantity {
    param([string] $Text)

    try {
        return [int]::Parse($Text)
    } catch {
        throw "not a quantity: $Text" # break here: $_ in Variables holds the parse error
    }
}

# Three workers, restock-1 to restock-3, each count up their share of 160.
function Invoke-Restock {
    param([int] $Workers)

    $byWorker = [ordered]@{}
    foreach ($worker in 1..$Workers) {
        $name = "restock-$worker"
        $byWorker[$name] = 0
        foreach ($round in 1..160) {
            $byWorker[$name]++ # break here: a hit-count breakpoint (this line runs 480 times); evaluate $name
        }
    }
    return ($byWorker.Values | Measure-Object -Sum).Sum
}

$limit = 6 # break here: a first plain breakpoint; Step Over, Set Value on $limit, then Step Into Invoke-Checkout
if ($Argument -eq 'crash') {
    $missing = $null
    $missing.Boom() # an error nobody catches: the script stops, and the Debug console shows it
} elseif ($Argument) {
    try {
        $limit = ConvertTo-Quantity -Text $Argument
    } catch {
        Write-Warning $_
    }
}
$lines, $total = Invoke-Checkout -Shelf $Items -Limit $limit
$lines | Write-Output
'total {0:N2}' -f $total
"restocked $(Invoke-Restock -Workers 3)"
