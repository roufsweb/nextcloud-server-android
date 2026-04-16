import os
import tarfile

def extract_deb(deb_path, output_dir):
    with open(deb_path, 'rb') as f:
        # Skip 'ar' header
        magic = f.read(8)
        if magic != b'!<arch>\n':
            print("Not a valid deb (ar) file")
            return

        while True:
            header = f.read(60)
            if len(header) < 60:
                break
            
            name = header[0:16].strip().decode('ascii')
            # Timestamp (12), Owner (6), Group (6), Mode (8)
            # Size (10)
            size = int(header[48:58].strip().decode('ascii'))
            
            if name.startswith('data.tar'):
                print(f"Found {name}, size {size}")
                data = f.read(size)
                with open('data_tmp.tar.xz', 'wb') as df:
                    df.write(data)
                
                # Extract the tar.xz
                import subprocess
                subprocess.run(['tar', '-xf', 'data_tmp.tar.xz', '-C', output_dir])
                break
            else:
                # Skip other files
                f.seek(size, 1)

if __name__ == "__main__":
    os.makedirs('assets_tmp/nginx', exist_ok=True)
    extract_deb('nginx-android.deb', 'assets_tmp/nginx')
