#!/bin/bash
# Script to cross-compile Nginx for Android ARM64
# Requires PCRE, OpenSSL, and Zlib sources or pre-compiled libraries.

NDK_PATH=$ANDROID_NDK_HOME
HOST_TAG=linux-x86_64
TOOLCHAIN=$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG
TARGET=aarch64-linux-android
API=30

export CC=$TOOLCHAIN/bin/aarch64-linux-android$API-clang

./configure \
  --crossbuild=android-arm64 \
  --with-cc=$CC \
  --with-cpp=$TOOLCHAIN/bin/aarch64-linux-android$API-clang \
  --prefix=/data/data/com.nextcloud.server/files \
  --conf-path=/data/data/com.nextcloud.server/files/conf/nginx.conf \
  --error-log-path=/data/data/com.nextcloud.server/files/logs/error.log \
  --http-log-path=/data/data/com.nextcloud.server/files/logs/access.log \
  --pid-path=/data/data/com.nextcloud.server/files/run/nginx.pid \
  --with-pcre=../pcre2-10.42 \
  --with-openssl=../openssl-3.1.0 \
  --with-zlib=../zlib-1.2.13 \
  --with-http_ssl_module \
  --with-http_v2_module \
  --with-http_gzip_static_module \
  --with-http_stub_status_module

make -j$(nproc)
make install
