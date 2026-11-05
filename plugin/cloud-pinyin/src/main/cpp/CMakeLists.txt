cmake_minimum_required(VERSION 3.18)

project(fcitx5-android-plugin-cloud-pinyin VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(Fcitx5Core MODULE)

# The pinned cloudpinyin addon header is not installed by the submodule build
# (ENABLE_CLOUDPINYIN is OFF on Android), so use the source tree directly to keep the
# ABI in sync instead of vendoring a copy.
set(CLOUDPINYIN_SOURCE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/modules/cloudpinyin")
if (NOT EXISTS "${CLOUDPINYIN_SOURCE_DIR}/cloudpinyin_public.h")
    message(FATAL_ERROR
            "cloudpinyin_public.h not found under '${CLOUDPINYIN_SOURCE_DIR}'. "
            "The fcitx5-chinese-addons submodule is missing or its layout changed; "
            "refusing to build an ABI-mismatched cloudpinyin addon.")
endif()

set(ANDROID_IPC_BRIDGE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../app/src/main/cpp/androidipcbridge")
if (NOT EXISTS "${ANDROID_IPC_BRIDGE_DIR}/androidipcbridge.h")
    message(FATAL_ERROR
            "androidipcbridge.h not found under '${ANDROID_IPC_BRIDGE_DIR}'. "
            "The host addon interface changed; refusing to build cloudpinyin.")
endif()

add_library(cloudpinyin MODULE cloudpinyin.cpp)
target_include_directories(cloudpinyin PRIVATE
        "${CLOUDPINYIN_SOURCE_DIR}"
        "${ANDROID_IPC_BRIDGE_DIR}")
target_compile_features(cloudpinyin PRIVATE cxx_std_20)
target_link_libraries(cloudpinyin Fcitx5::Core Fcitx5::Utils)

# Installed into the plugin assets so the host can merge it into its data directory.
configure_file(cloudpinyin.conf.in cloudpinyin.conf @ONLY)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/cloudpinyin.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config)
