# Points Tesseract's find_package(Leptonica CONFIG) at the in-tree leptonica target
# (the upstream build-tree config expects an installed export set).
set(Leptonica_FOUND TRUE)
set(Leptonica_VERSION "${MX_LEPTONICA_VERSION}")
set(Leptonica_INCLUDE_DIRS "${MX_THIRD_PARTY}/leptonica/src" "${CMAKE_BINARY_DIR}/leptonica-public")
set(Leptonica_LIBRARIES leptonica)
