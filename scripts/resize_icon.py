import os
from PIL import Image
import sys

def resize_icon(source_path, res_dir):
    sizes = {
        'mdpi': 48,
        'hdpi': 72,
        'xhdpi': 96,
        'xxhdpi': 144,
        'xxxhdpi': 192
    }
    
    try:
        img = Image.open(source_path)
    except Exception as e:
        print(f"Error opening image: {e}")
        sys.exit(1)
        
    for density, size in sizes.items():
        dir_path = os.path.join(res_dir, f'mipmap-{density}')
        if not os.path.exists(dir_path):
            os.makedirs(dir_path)
            
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        
        # Save normal and round variants
        resized.save(os.path.join(dir_path, 'ic_launcher.png'))
        resized.save(os.path.join(dir_path, 'ic_launcher_round.png'))
        print(f"Saved {density} ({size}x{size})")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: python resize_icon.py <source_png> <res_dir>")
        sys.exit(1)
    resize_icon(sys.argv[1], sys.argv[2])
