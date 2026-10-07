// A small TSX sample: JSX elements, props, expressions, and an event handler.
// It declares its own minimal JSX types instead of importing React, so a language server shows
// no "cannot find module" error when the sample is opened without a node_modules folder.

declare global {
  namespace JSX {
    interface Element {}
    interface IntrinsicElements {
      [tag: string]: Record<string, unknown>;
    }
  }
}

type BadgeProps = { label: string; count: number; tone?: "info" | "warn" };

export const Badge = ({ label, count, tone = "info" }: BadgeProps) => (
  <span className={`badge badge-${tone}`} title={label}>
    {label}: <strong>{count}</strong>
  </span>
);

type ListProps<T> = { items: T[]; render: (item: T) => JSX.Element };

/* A generic component: the trailing comma keeps <T,> from parsing as a tag. */
export const List = <T,>({ items, render }: ListProps<T>) => (
  <ul>
    {items.length === 0 ? (
      <li className="empty">Nothing here</li>
    ) : (
      items.map((item) => <li>{render(item)}</li>)
    )}
  </ul>
);

export function Toolbar({ onSave }: { onSave: () => void }) {
  const unsaved: number = 3;
  return (
    <header>
      {/* A JSX comment */}
      <button type="button" onClick={onSave} disabled={unsaved === 0}>
        Save
      </button>
      <Badge label="Unsaved" count={unsaved} tone="warn" />
    </header>
  );
}
