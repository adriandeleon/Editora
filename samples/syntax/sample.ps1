<#
.SYNOPSIS
    A small PowerShell sample for syntax highlighting and folding.
.DESCRIPTION
    Covers comment-based help, an advanced function with parameter attributes,
    a class, a hashtable, the pipeline, a switch, here-strings, and try/catch.
#>

Set-StrictMode -Version Latest
$MaxItems = 0x40 # hex literal

class Item {
    [string] $Name
    [double] $WeightKg

    Item([string] $name, [double] $weightKg) {
        $this.Name = $name
        $this.WeightKg = $weightKg
    }

    [string] Describe() {
        return "{0} ({1:N1} kg)" -f $this.Name, $this.WeightKg
    }
}

function Get-ItemKind {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, ValueFromPipeline)]
        [Item] $Item,

        [ValidateRange(1, 100)]
        [int] $HeavyOver = 10
    )
    process {
        switch ($Item.WeightKg) {
            { $_ -gt $HeavyOver } { "heavy: $($Item.Name)"; break }
            { $_ -le 0 } { throw "Invalid weight for '$($Item.Name)'" }
            default { "light: $($Item.Name)" }
        }
    }
}

$banner = @"
Inventory (max $MaxItems)
---------
"@

$shelf = @(
    [Item]::new('Hammer', 0.6)
    [Item]::new('Anvil', 45)
)
$byName = @{}
foreach ($item in $shelf) {
    $byName[$item.Name] = $item
}

try {
    Write-Output $banner
    $shelf | Sort-Object -Property WeightKg -Descending | ForEach-Object { $_.Describe() }
    $shelf | Get-ItemKind -HeavyOver 20
    Write-Output ('Total: {0}' -f ($shelf | Measure-Object -Property WeightKg -Sum).Sum)
}
catch {
    Write-Error "Failed: $($_.Exception.Message)"
}
finally {
    Write-Verbose 'Done.'
}
