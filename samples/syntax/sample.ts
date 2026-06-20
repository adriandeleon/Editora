interface User {
  id: number;
  name: string;
}

export function greet(u: User): string {
  return `Hello, ${u.name} (#${u.id})`;
}

const admin: User = { id: 1, name: "root" };
console.log(greet(admin));
