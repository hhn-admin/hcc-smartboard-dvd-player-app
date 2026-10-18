cmake_minimum_required(VERSION 3.22.1)
project(uaciso C)

add_library(uaciso SHARED uac_iso.c)

find_library(log-lib log)
target_link_libraries(uaciso ${log-lib})

# 16 KB page alignment, same requirement the bundled libuvc already meets.
target_link_options(uaciso PRIVATE "-Wl,-z,max-page-size=16384")
