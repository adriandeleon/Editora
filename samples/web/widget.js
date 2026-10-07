// The script for widget.html: a counter with three buttons.
const display = document.getElementById("count");
let count = 0;

function render() {
  display.textContent = String(count);
  display.classList.toggle("negative", count < 0);
}

document.querySelectorAll("button[data-step]").forEach((button) => {
  button.addEventListener("click", () => {
    count += Number(button.dataset.step);
    render();
  });
});

document.querySelector("button[data-reset]").addEventListener("click", () => {
  count = 0;
  render();
});

render();
