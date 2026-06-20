struct Point {
    x: i32,
    y: i32,
}

impl Point {
    fn norm2(&self) -> i32 {
        self.x * self.x + self.y * self.y
    }
}

fn main() {
    println!("{}", Point { x: 3, y: 4 }.norm2());
}
