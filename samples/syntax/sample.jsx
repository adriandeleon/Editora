// A small JSX sample (JavaScript + JSX, via the TSX grammar): elements, props,
// expressions, a fragment, spread attributes, and an event handler.

const TONES = { info: "badge-info", warn: "badge-warn" };

export function Badge({ label, count, tone = "info" }) {
  return (
    <span className={`badge ${TONES[tone]}`} title={label}>
      {label}: <strong>{count}</strong>
    </span>
  );
}

export const ItemList = ({ items, onRemove, ...rest }) => (
  <ul {...rest}>
    {items.length === 0 && <li className="empty">Nothing here</li>}
    {items.map((item) => (
      <li key={item.id}>
        {item.name}
        <button type="button" onClick={() => onRemove(item.id)} aria-label={`Remove ${item.name}`}>
          &times;
        </button>
      </li>
    ))}
  </ul>
);

export default function Shelf({ items }) {
  const heavy = items.filter((item) => item.weightKg > 10);
  return (
    <>
      {/* A JSX comment */}
      <h2>Shelf</h2>
      <Badge label="Heavy" count={heavy.length} tone={heavy.length > 0 ? "warn" : "info"} />
      <ItemList items={items} onRemove={(id) => console.log("remove", id)} data-testid="items" />
    </>
  );
}
