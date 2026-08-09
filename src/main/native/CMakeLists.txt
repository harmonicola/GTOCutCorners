cmake_minimum_required(VERSION 3.20)
project(gtocutcorners_native C)

set(CMAKE_C_STANDARD 11)

# JDK include paths — override with -DJAVA_HOME=... if needed
if(NOT DEFINED JAVA_HOME)
    if(WIN32)
        set(JAVA_HOME "C:/Program Files/Java/jdk-21")
    elseif(APPLE)
        set(JAVA_HOME "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home")
    else()
        set(JAVA_HOME "/usr/lib/jvm/java-21-openjdk")
    endif()
endif()

message(STATUS "JAVA_HOME = ${JAVA_HOME}")

if(WIN32)
    include_directories("${JAVA_HOME}/include")
    include_directories("${JAVA_HOME}/include/win32")
    set(OUTPUT_NAME "libgtocutcorners_native")
elseif(APPLE)
    include_directories("${JAVA_HOME}/include")
    include_directories("${JAVA_HOME}/include/darwin")
    set(OUTPUT_NAME "libgtocutcorners_native")
else()
    include_directories("${JAVA_HOME}/include")
    include_directories("${JAVA_HOME}/include/linux")
    set(OUTPUT_NAME "libgtocutcorners_native")
endif()

add_library(${OUTPUT_NAME} SHARED patcher.c)
target_sources(${OUTPUT_NAME} PRIVATE jvmti_patch.c)
set_source_files_properties(jvmti_patch.c PROPERTIES HEADER_FILE_ONLY TRUE)

# Optional: standalone trace DLL for debugging (excluded from main build)
# add_library(gtocutcorners_trace SHARED trace_only.c)
