# Discounts for shelf.ps1, as a module it imports: Step Into from the script lands in this file.

<#
.SYNOPSIS
    An item's discount: a percentage off, possibly stacked on another discount (a big quantity gets one).
#>
function Get-Discount {
    param([Parameter(Mandatory)] [pscustomobject] $Item)

    if ($Item.Quantity -ge 10) {
        return @{ Percent = 10; Then = @{ Percent = 5; Then = $null } } # break here: hit once per big item
    }
    if ($Item.Price -gt 5) {
        return @{ Percent = 15; Then = $null }
    }
    return $null
}

<#
.SYNOPSIS
    Recurses through a stacked discount: the call stack shows two Invoke-Discount frames.
    A function breakpoint on Invoke-Discount stops on every call, the recursive ones too.
#>
function Invoke-Discount {
    param([hashtable] $Discount, [double] $Price)

    if ($null -eq $Discount) {
        return $Price
    }
    $Price = $Price * (100 - $Discount.Percent) / 100 # break here: Step Out, or look at the frames below
    return Invoke-Discount -Discount $Discount.Then -Price $Price
}

Export-ModuleMember -Function Get-Discount, Invoke-Discount
