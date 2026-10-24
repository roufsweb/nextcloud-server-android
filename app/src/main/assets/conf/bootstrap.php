<?php
/**
 * Nextcloud Server Android - Environment Bootstrap
 * Provides essential polyfills and stubs for embedded mobile server runtime.
 */

// Only provide fallback GD stubs if the native GD extension is not loaded.
// When native gd.so is loaded, native PHP functions handle all thumbnail and image rendering.
if (!extension_loaded('gd')) {
if (!function_exists('gd_info')) {
    function gd_info(): array {
        return [
            'GD Version' => 'bundled (2.1.0 compatible)',
            'FreeType Support' => false,
            'GIF Read Support' => true,
            'GIF Create Support' => true,
            'JPEG Support' => true,
            'PNG Support' => true,
            'WBMP Support' => false,
            'XPM Support' => false,
            'XBM Support' => false,
            'WebP Support' => true,
            'BMP Support' => true,
            'AVIF Support' => false,
            'TGA Read Support' => false,
            'JIS-mapped Japanese Font Support' => false,
        ];
    }
}

if (!function_exists('imagecreatetruecolor')) {
    function imagecreatetruecolor(int $width, int $height) {
        return false;
    }
}

if (!function_exists('imagecreatefromstring')) {
    function imagecreatefromstring(string $data) {
        return false;
    }
}

if (!function_exists('imagecreatefromjpeg')) {
    function imagecreatefromjpeg(string $filename) {
        return false;
    }
}

if (!function_exists('imagecreatefrompng')) {
    function imagecreatefrompng(string $filename) {
        return false;
    }
}

if (!function_exists('imagecreatefromwebp')) {
    function imagecreatefromwebp(string $filename) {
        return false;
    }
}

if (!function_exists('imagecreatefromgif')) {
    function imagecreatefromgif(string $filename) {
        return false;
    }
}

if (!function_exists('imagesx')) {
    function imagesx($image): int {
        return 0;
    }
}

if (!function_exists('imagesy')) {
    function imagesy($image): int {
        return 0;
    }
}

if (!function_exists('imagedestroy')) {
    function imagedestroy($image): bool {
        return true;
    }
}

if (!function_exists('imagecopyresampled')) {
    function imagecopyresampled($dst_image, $src_image, int $dst_x, int $dst_y, int $src_x, int $src_y, int $dst_w, int $dst_h, int $src_w, int $src_h): bool {
        return false;
    }
}

if (!function_exists('imagecopy')) {
    function imagecopy($dst_image, $src_image, int $dst_x, int $dst_y, int $src_x, int $src_y, int $src_w, int $src_h): bool {
        return false;
    }
}

if (!function_exists('imagepng')) {
    function imagepng($image, $file = null, int $quality = -1, int $filters = -1): bool {
        return false;
    }
}

if (!function_exists('imagejpeg')) {
    function imagejpeg($image, $file = null, int $quality = -1): bool {
        return false;
    }
}

if (!function_exists('imagewebp')) {
    function imagewebp($image, $file = null, int $quality = -1): bool {
        return false;
    }
}

if (!function_exists('imagegif')) {
    function imagegif($image, $file = null): bool {
        return false;
    }
}
} // end of !extension_loaded('gd')

