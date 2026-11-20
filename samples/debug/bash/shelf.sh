#!/usr/bin/env bash
# The shelf, in Bash: open this file and press F5 (Debug). The script runs under bashdb (bundled with the Bash
# debug adapter, Settings → Debugging → Bash), which stops at your breakpoints. Every suggested spot is a
# "break here" comment. `bash shelf.sh` runs it outside Editora; `bash shelf.sh 3` totals three items,
# `bash shelf.sh crash` fails with an unset variable.
#
# To read a variable, evaluate or watch its bare name (`total`, not `$total`); Editora strips a leading $ for you.
set -uo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=pricing.sh
source "$here/pricing.sh"

# What is on the shelf: sku|name|quantity|price in cents.
ITEMS=(
  "A-1|Pencil|12|40"
  "A-2|Notebook|3|275"
  "B-1|Stapler|1|890"
  "B-2|Tape|4|120"
  "C-1|Marker|10|105"
  "C-2|Eraser|25|30"
  "C-3|Ruler|2|150"
  "D-1|Glue|6|210"
)

# Formats cents as 12.34.
money() {
  printf '%d.%02d' $(($1 / 100)) $(($1 % 100))
}

# Totals the first $1 items: total, lines and by_prefix change on every pass.
checkout() {
  local limit=$1
  local -a lines=()
  local -A by_prefix=()
  local total=0 i sku name quantity price discount subtotal
  for ((i = 0; i < limit && i < ${#ITEMS[@]}; i++)); do
    IFS='|' read -r sku name quantity price <<<"${ITEMS[i]}"
    discount=$(discount_for "$sku" "$quantity") # break here: Step Into lands in pricing.sh
    price=$(apply_discount "$discount" "$price")
    subtotal=$((price * quantity))
    lines+=("$(printf '%-4s %-9s %3d x %6s = %8s' "$sku" "$name" "$quantity" "$(money "$price")" "$(money "$subtotal")")")
    by_prefix[${sku:0:1}]=$((${by_prefix[${sku:0:1}]:-0} + quantity))
    total=$((total + subtotal)) # break here: a watch on `total`, or a logpoint printing {name}
  done
  printf '%s\n' "${lines[@]}"
  local prefix summary=""
  for prefix in "${!by_prefix[@]}"; do
    summary+="$prefix=${by_prefix[$prefix]} " # break here: evaluate `by_prefix[A]`
  done
  echo "by prefix: $summary"
  CHECKOUT_TOTAL=$total
}

# Turns "12" into 12; a word is an error the caller reports.
parse_quantity() {
  if [[ $1 =~ ^[0-9]+$ ]]; then
    echo "$1"
  else
    echo "not a quantity: $1" >&2 # break here: `1` in the evaluate field shows the argument
    return 1
  fi
}

# Counts down a restock in three rounds, so Step Over has a nested loop to walk.
restock() {
  local rounds=$1 round added=0 item
  for ((round = 1; round <= rounds; round++)); do
    for item in "${ITEMS[@]:0:3}"; do
      added=$((added + round)) # break here: hit count 4 stops on the fourth pass
    done
  done
  echo "$added"
}

limit=6 # break here: a first plain breakpoint; Step Over, then Step Into checkout
if [[ ${1:-} == crash ]]; then
  echo "about to read an unset variable"
  echo "$missing_variable" # a set -u error ends the script here
elif [[ -n ${1:-} ]]; then
  if parsed=$(parse_quantity "$1"); then
    limit=$parsed
  fi
fi
checkout "$limit"
echo "total $(money "$CHECKOUT_TOTAL")"
echo "restocked $(restock 3)"
