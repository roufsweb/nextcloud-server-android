import zipfile
import os
import shutil

zip_path = "app/src/main/assets/nextcloud.zip"
temp_zip_path = "app/src/main/assets/nextcloud_patched.zip"

print(f"Reading {zip_path}...")

patches = {}

with zipfile.ZipFile(zip_path, 'r') as zin:
    # 1. Patch PageController.php
    page_ctrl_path = 'nextcloud/apps/appstore/lib/Controller/PageController.php'
    content = zin.read(page_ctrl_path).decode('utf-8')
    old_csp = "$policy->addAllowedImageDomain('https://usercontent.apps.nextcloud.com');"
    new_csp = """$policy->addAllowedImageDomain('https://usercontent.apps.nextcloud.com');
		$policy->addAllowedImageDomain('https://apps.nextcloud.com');
		$policy->addAllowedImageDomain('https://raw.githubusercontent.com');
		$policy->addAllowedImageDomain('https://github.com');
		$policy->addAllowedImageDomain('https://avatars.githubusercontent.com');
		$policy->addAllowedImageDomain('http:');
		$policy->addAllowedImageDomain('https:');
		$policy->addAllowedImageDomain('data:');
		$policy->addAllowedImageDomain('blob:');"""
    if old_csp in content:
        content = content.replace(old_csp, new_csp)
        patches[page_ctrl_path] = content
        print(f"Patched {page_ctrl_path}")
    else:
        print(f"Target not found in {page_ctrl_path}")

    # 2. Patch ApiController.php
    api_ctrl_path = 'nextcloud/apps/appstore/lib/Controller/ApiController.php'
    content = zin.read(api_ctrl_path).decode('utf-8')
    old_proxy = """	private function createProxyPreviewUrl(string $url): string {
		if ($url === '') {
			return '';
		}

		return 'https://usercontent.apps.nextcloud.com/' . base64_encode($url);
	}"""
    new_proxy = """	private function createProxyPreviewUrl(string $url): string {
		if ($url === '') {
			return '';
		}

		if (str_starts_with($url, 'https://usercontent.apps.nextcloud.com/')) {
			return $url;
		}

		if (!str_starts_with($url, 'http://') && !str_starts_with($url, 'https://')) {
			// Local relative asset path (e.g. img/screenshot.png) - keep local URL
			return $url;
		}

		return 'https://usercontent.apps.nextcloud.com/' . base64_encode($url);
	}"""
    if old_proxy in content:
        content = content.replace(old_proxy, new_proxy)
        print(f"Patched createProxyPreviewUrl in {api_ctrl_path}")
    else:
        print(f"Target createProxyPreviewUrl not found in {api_ctrl_path}")

    old_screenshot = """			if (isset($app['screenshot'][0])) {
				$appScreenshot = $app['screenshot'][0] ?? null;
				if (is_array($appScreenshot)) {
					// Screenshot with thumbnail
					$appScreenshot = $appScreenshot['@value'];
				}

				$app['screenshot'] = $this->createProxyPreviewUrl($appScreenshot);
			}"""
    new_screenshot = """			if (isset($app['screenshot'][0])) {
				$appScreenshot = $app['screenshot'][0] ?? null;
				if (is_array($appScreenshot)) {
					// Screenshot with thumbnail
					if (isset($appScreenshot['@attributes']['small-thumbnail']) && !empty($appScreenshot['@attributes']['small-thumbnail'])) {
						$appScreenshot = $appScreenshot['@attributes']['small-thumbnail'];
					} elseif (isset($appScreenshot['@value'])) {
						$appScreenshot = $appScreenshot['@value'];
					}
				}

				if (is_string($appScreenshot)) {
					$app['screenshot'] = $this->createProxyPreviewUrl($appScreenshot);
				}
			}"""
    if old_screenshot in content:
        content = content.replace(old_screenshot, new_screenshot)
        patches[api_ctrl_path] = content
        print(f"Patched screenshot handling in {api_ctrl_path}")
    else:
        print(f"Target screenshot handling not found in {api_ctrl_path}")

    # 3. Patch IconController.php
    icon_ctrl_path = 'nextcloud/apps/theming/lib/Controller/IconController.php'
    content = zin.read(icon_ctrl_path).decode('utf-8')
    old_themed = """		$color = $this->themingDefaults->getColorPrimary();
		try {
			$iconFileName = $this->imageManager->getCachedImage('icon-' . $app . '-' . $color . str_replace('/', '_', $image));
		} catch (NotFoundException $exception) {
			$icon = $this->iconBuilder->colorSvg($app, $image);
			if ($icon === false || $icon === '') {
				return new NotFoundResponse();
			}
			$iconFileName = $this->imageManager->setCachedImage('icon-' . $app . '-' . $color . str_replace('/', '_', $image), $icon);
		}
		$response = new FileDisplayResponse($iconFileName, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);
		$response->cacheFor(86400, false, true);
		return $response;"""

    new_themed = """		$color = $this->themingDefaults->getColorPrimary();
		try {
			$iconFileName = $this->imageManager->getCachedImage('icon-' . $app . '-' . $color . str_replace('/', '_', $image));
			$response = new FileDisplayResponse($iconFileName, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);
			$response->cacheFor(86400, false, true);
			return $response;
		} catch (\\Throwable $exception) {
			$icon = $this->iconBuilder->colorSvg($app, $image);
			if ($icon === false || $icon === '') {
				return new NotFoundResponse();
			}
			try {
				$iconFileName = $this->imageManager->setCachedImage('icon-' . $app . '-' . $color . str_replace('/', '_', $image), $icon);
				$response = new FileDisplayResponse($iconFileName, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);
				$response->cacheFor(86400, false, true);
				return $response;
			} catch (\\Throwable $e) {
				$response = new DataDisplayResponse($icon, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);
				$response->cacheFor(86400, false, true);
				return $response;
			}
		}"""
    if old_themed in content:
        content = content.replace(old_themed, new_themed)
        patches[icon_ctrl_path] = content
        print(f"Patched {icon_ctrl_path}")
    else:
        print(f"Target not found in {icon_ctrl_path}")

print(f"Rewriting zip with {len(patches)} patched files...")
with zipfile.ZipFile(zip_path, 'r') as zin:
    with zipfile.ZipFile(temp_zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename in patches:
                print(f"Writing patched {item.filename}")
                zout.writestr(item, patches[item.filename].encode('utf-8'))
            else:
                zout.writestr(item, zin.read(item.filename))

os.replace(temp_zip_path, zip_path)
print("Successfully replaced nextcloud.zip with patched version!")
