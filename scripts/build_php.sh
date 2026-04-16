#!/bin/bash
# Script to cross-compile PHP 8.3/8.4 for Android ARM64
# This script is intended to be run on a Linux host with Android NDK installed.

NDK_PATH=$ANDROID_NDK_HOME
HOST_TAG=linux-x86_64
TOOLCHAIN=$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG
TARGET=aarch64-linux-android
API=30

export AR=$TOOLCHAIN/bin/llvm-ar
export AS=$TOOLCHAIN/bin/llvm-as
export CC=$TOOLCHAIN/bin/aarch64-linux-android$API-clang
export CXX=$TOOLCHAIN/bin/aarch64-linux-android$API-clang++
export LD=$TOOLCHAIN/bin/ld.lld
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip

./configure \
  --host=$TARGET \
  --prefix=/data/data/com.nextcloud.android.server/files \
  --enable-fpm \
  --with-fpm-user=shell \
  --with-fpm-group=shell \
  --disable-all \
  --enable-ctype \
  --enable-dom \
  --enable-fileinfo \
  --enable-filter \
  --enable-hash \
  --enable-json \
  --enable-libxml \
  --enable-mbstring \
  --enable-pdo \
  --enable-session \
  --enable-simplexml \
  --enable-xmlreader \
  --enable-xmlwriter \
  --enable-opcache \
  --with-openssl \
  --with-curl \
  --with-zip \
  --with-zlib \
  --with-pdo-sqlite \
  --with-iconv \
  --enable-gd \
  --with-external-pcre \
  --enable-bcmath \
  --enable-intl \
  --enable-gmp \
  --enable-exif

make -j$(nproc)
make install
