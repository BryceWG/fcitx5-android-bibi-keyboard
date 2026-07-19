cmake_minimum_required(VERSION 3.18)

project(fcitx5-android-lib-fcitx5-chinese-addons VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(ECM MODULE)
find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)

find_package(libime REQUIRED CONFIG)
get_target_property(LIBIME_CMAKE_MODULES libime::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${LIBIME_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(LibIMECore MODULE)
find_package(LibIMEPinyin MODULE)
find_package(LibIMETable MODULE)

find_package(fcitx5-lua REQUIRED CONFIG)
get_target_property(FCITX5_LUA_CMAKE_MODULES fcitx5-lua::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_LUA_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(Fcitx5ModuleLuaAddonLoader MODULE)

# dummy target to export src/main/cpp/cmake
add_custom_target(cmake)

# prebuilt boost
list(APPEND CMAKE_FIND_ROOT_PATH "${PREBUILT_DIR}/boost/${ANDROID_ABI}/lib/cmake")
find_package(Boost 1.87.0 REQUIRED COMPONENTS iostreams CONFIG)
add_compile_definitions("BOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS")

# Temporary fallback for Windows when the ABI-specific Boost include
# is a broken symlink/file (common on repos cloned without symlink support).
set(_BOOST_ABI_INCLUDE "${PREBUILT_DIR}/boost/${ANDROID_ABI}/include")
if(NOT IS_DIRECTORY "${_BOOST_ABI_INCLUDE}")
    message(WARNING "Boost ABI include '${_BOOST_ABI_INCLUDE}' is not a directory; "
                    "falling back to '${PREBUILT_DIR}/boost/include'.")
    include_directories(BEFORE "${PREBUILT_DIR}/boost/include")
    set(Boost_INCLUDE_DIRS "${PREBUILT_DIR}/boost/include" CACHE PATH "" FORCE)
endif()

# prebuilt marisa-tire, OpenCC needs it
set(Marisa_DIR "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/lib/cmake/Marisa")
find_package(Marisa)

# prebuilt opencc
set(OpenCC_DIR "${PREBUILT_DIR}/opencc/${ANDROID_ABI}/lib/cmake/opencc")
find_package(OpenCC)

option(ENABLE_TEST "" OFF)
option(ENABLE_GUI "" OFF)
option(ENABLE_BROWSER "" OFF)
option(USE_WEBKIT "" OFF)
option(ENABLE_CLOUDPINYIN "" OFF)
# prefer OpenCC_DIR rather than fcitx5-chinese-addons/cmake/FindOpenCC.cmake
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG ON)
add_subdirectory(fcitx5-chinese-addons)
# rename to include executable in apk
set_target_properties(scel2org5 PROPERTIES OUTPUT_NAME libscel2org5.so)
