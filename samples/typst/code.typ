// Code — Typst highlights fenced blocks and inline raw out of the box (no package).
#set page(paper: "a5", margin: 1.5cm)
#set heading(numbering: "1.")
#show raw.where(block: true): it => block(
  fill: luma(245), inset: 8pt, radius: 4pt, width: 100%, it,
)

= Code

Inline raw like `let x = 42` sits in the prose. Fenced blocks are syntax-highlighted
by language:

```rust
fn main() {
    let items = vec![1, 2, 3];
    for i in &items {
        println!("item = {i}");
    }
}
```

```python
def greet(name: str) -> str:
    return f"Hello, {name}!"

print(greet("Typst"))
```

```typst
#set text(font: "New Computer Modern")
#let greeting = "Hello from Typst"
```
