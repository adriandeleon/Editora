# A doctest project for Test Results, the test gutter ▶ and Run/Debug Test at Caret: open shelf_test.cpp.
# Editora configures a Debug build in build/editora-debug (CMake downloads doctest on the first configure,
# so that one needs the network), builds it, and runs the shelf_test program with --test-case.
# Outside Editora: cmake -S . -B build && cmake --build build && ./build/shelf_test
cmake_minimum_required(VERSION 3.16)
project(shelf_doctest CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW) # extracted files get the time they were extracted
endif()

include(FetchContent)
FetchContent_Declare(
  doctest
  URL https://github.com/doctest/doctest/archive/refs/tags/v2.4.12.tar.gz
  URL_HASH SHA256=73381c7aa4dee704bd935609668cf41880ea7f19fa0504a200e13b74999c2d70)
FetchContent_MakeAvailable(doctest) # its own CMakeLists asks for CMake 3.5: a deprecation warning, harmless

add_executable(shelf_test shelf_test.cpp)
target_link_libraries(shelf_test PRIVATE doctest::doctest)
