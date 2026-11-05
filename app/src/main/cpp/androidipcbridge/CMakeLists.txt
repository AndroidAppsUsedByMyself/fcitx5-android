add_definitions(-DFCITX_GETTEXT_DOMAIN=\"fcitx5-android\")

add_library(androidipcbridge MODULE androidipcbridge.cpp)
target_link_libraries(androidipcbridge Fcitx5::Core Fcitx5::Utils)

configure_file(androidipcbridge.conf.in androidipcbridge.conf @ONLY)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/androidipcbridge.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config)
