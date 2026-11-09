# The shelf, in C++: open src/main.cpp and press F5 (Debug). Editora configures a Debug build in
# build/editora-debug, builds it in the Debug console, then CodeLLDB runs it. Every suggested spot is a
# "break here" comment. Outside Editora: cmake -S . -B build && cmake --build build && ./build/shelf
cmake_minimum_required(VERSION 3.16)
project(shelf CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Threads REQUIRED)
add_executable(shelf src/main.cpp src/shelf.cpp src/pricing.cpp src/restock.cpp)
target_link_libraries(shelf PRIVATE Threads::Threads)
