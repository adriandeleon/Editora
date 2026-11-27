# A GoogleTest project for Test Results, the test gutter ▶ and Run/Debug Test at Caret: open shelf_test.cpp.
# Editora configures a Debug build in build/editora-debug (CMake downloads GoogleTest on the first
# configure, so that one needs the network), builds it, and runs the shelf_test program with
# --gtest_filter. Outside Editora: cmake -S . -B build && cmake --build build && ./build/shelf_test
cmake_minimum_required(VERSION 3.16)
project(shelf_googletest CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW) # extracted files get the time they were extracted
endif()

include(FetchContent)
FetchContent_Declare(
  googletest
  URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.tar.gz
  URL_HASH SHA256=65fab701d9829d38cb77c14acdc431d2108bfdbf8979e40eb8ae567edf10b27c)
set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE) # MSVC: the same runtime as this project
FetchContent_MakeAvailable(googletest)

add_executable(shelf_test shelf_test.cpp)
target_link_libraries(shelf_test PRIVATE GTest::gtest_main)
