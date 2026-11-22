// A shelf of items: a checkout loop, an order as JSON, and a report built with eval.
const items = [
  { sku: "A-1", name: "Pencil", price: 0.4, qty: 12 },
  { sku: "A-2", name: "Notebook", price: 2.75, qty: 3 },
  { sku: "B-1", name: "Stapler", price: 8.9, qty: 1 },
  { sku: "C-1", name: "Marker", price: 1.05, qty: 10 },
];

function checkout(list) {
  let total = 0;
  for (const item of list) {
    const subtotal = item.price * item.qty;
    total += subtotal;
  }
  return total;
}

const cheap = items.filter((item) => item.price < 1.0).map((item) => item.name);
const total = checkout(items);
const order = JSON.stringify({ customer: "Ada", lines: items.map((i) => ({ sku: i.sku, qty: i.qty })), total });
const report = eval(`(function report(total) {
  const line = "Total: " + total.toFixed(2);
  debugger;
  return line;
})`);
console.log(report(total), cheap, order.length);
