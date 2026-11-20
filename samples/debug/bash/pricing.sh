# Discounts for the shelf, sourced by shelf.sh: Step Into a call from there lands in this file.
# Prices are whole cents (Bash has no floating point), so 0.40 is 40.

# Prints the discount in percent for one item: a case hit once per item.
discount_for() {
  local sku=$1 quantity=$2
  local percent=0
  case $sku in
    A-*) percent=5 ;;
    C-*) percent=10 ;; # break here: a conditional breakpoint, $quantity -gt 5
  esac
  if ((quantity >= 20)); then
    percent=$((percent + 5)) # break here: only the eraser gets this far
  fi
  echo "$percent"
}

# Prints price minus percent, rounded down to a whole cent.
apply_discount() {
  local percent=$1 price=$2
  local cut=$((price * percent / 100))
  echo $((price - cut)) # break here: hover `cut`, or evaluate `price`
}
