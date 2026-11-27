# A Catch2 3 project for Test Results, the test gutter ▶ and Run/Debug Test at Caret: open shelf_test.cpp.
# Editora configures a Debug build in build/editora-debug (CMake downloads Catch2 on the first configure,
# so that one needs the network), builds it, and runs the shelf_test program with the test's name as a
# test spec. Outside Editora: cmake -S . -B build && cmake --build build && ./build/shelf_test
cmake_minimum_required(VERSION 3.16)
project(shelf_catch2 CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW) # extracted files get the time they were extracted
endif()

include(FetchContent)
FetchContent_Declare(
  Catch2
  URL https://github.com/catchorg/Catch2/archive/refs/tags/v3.8.1.tar.gz
  URL_HASH SHA256=18b3f70ac80fccc340d8c6ff0f339b2ae64944782f8d2fca2bd705cf47cadb79)
FetchContent_MakeAvailable(Catch2)

add_executable(shelf_test shelf_test.cpp)
target_link_libraries(shelf_test PRIVATE Catch2::Catch2WithMain)
