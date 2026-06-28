cmake_minimum_required(VERSION 3.20)
project(native C)

set(CMAKE_C_STANDARD 11)

include_directories("C:/Program Files/Java/jdk-17/include")
include_directories("C:/Program Files/Java/jdk-17/include/win32")

add_library(gtocutcorners_native SHARED patcher.c)
add_library(gtocutcorners_trace SHARED trace_only.c)
